package ru.lct.arm112.service.debrief;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Разбор текстом от локальной модели.
 *
 * <p>Задача принципиально легче генерации сценариев: нужно изложить данную структуру,
 * а не выдумать обстановку. Замер это подтвердил — 7B справляется содержательно,
 * ничего не путая местами, тогда как 3B меняла местами эталонный и введённый адрес.
 *
 * <p>Выборка ограничена грамматикой по тем же причинам, что и в генерации сценариев:
 * без неё модель срывается на другие языки примерно в трети случаев.
 */
@Component
public class DebriefWriter {
    private static final Logger log = LoggerFactory.getLogger(DebriefWriter.class);

    /**
     * Форма разбора задана грамматикой, а не просьбой в промпте: ровно столько пунктов,
     * сколько видов ошибок (до трёх), в каждом два предложения — что не так и к чему это ведёт.
     * Только кириллица, цифры и пунктуация.
     *
     * <p>Грамматика строк без ограничения числа рвала текст посреди слова и позволяла модели
     * «добивать» объём повторами; грамматика с необязательным вторым предложением давала
     * голые заголовки («Не указан номер дома.») — пересказ рекомендаций по правилам.
     */
    static String shape(int points) {
        return """
                root ::= point{%d}
                point ::= [1-3] ". " sentence " " sentence "\n"
                sentence ::= schar{10,180} [.!?]
                schar ::= [а-яА-ЯёЁ0-9 ,:;()«»/-]
                """.formatted(points);
    }

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public DebriefWriter(@Value("${arm112.llm.url:}") String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.objectMapper = objectMapper;
    }

    public boolean available() {
        if (baseUrl.isEmpty()) return false;
        try {
            return client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception exception) {
            return false;
        }
    }

    /** @return текст разбора либо null, если модель недоступна или не ответила */
    public String write(String structuredIssues) {
        return write(structuredIssues, 3);
    }

    /**
     * @param points сколько пунктов есть о чём написать: лимит токенов от него зависит.
     *               При двух ошибках и запасе в 450 токенов модель «добивала объём» повторами
     *               и служебными репликами вроде «последний пункт был сокращён».
     */
    public String write(String structuredIssues, int points) {
        if (!available()) return null;
        int n = Math.max(1, Math.min(3, points));
        String prompt = """
                Ты наставник оператора службы 112. Ниже структура замечаний по занятию обучающегося
                и рекомендации, которые он уже получил.
                Напиши разбор: ровно %d пункт(а) — по одному на каждую ошибку, начиная с самой важной.
                В каждом пункте два предложения. Первое — что именно сделано не так, с данными
                из замечаний: какая улица, какой тип, какие службы. Второе — к чему это приведёт
                в реальной работе службы и как это отработать. Рекомендации не пересказывай дословно.

                Пиши только по этим данным, ничего не добавляй от себя. Не придумывай названия улиц,
                номера домов, слова с ошибками и другие примеры, которых нет в данных: если конкретики
                нет, пиши без примера.
                Обращайся к обучающемуся на «вы». По-русски.

                %s
                """.formatted(n, structuredIssues);
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.4,
                    // без штрафа модель зацикливалась на «повторите проверку…»
                    "repeat_penalty", 1.15,
                    "max_tokens", 60 + 170 * n,
                    "grammar", shape(n)));
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                            .timeout(Duration.ofMinutes(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Модель ответила {} на запрос разбора", response.statusCode());
                return null;
            }
            JsonNode json = objectMapper.readTree(response.body());
            String text = json.path("choices").path(0).path("message").path("content").asText("").trim();
            if (text.isBlank()) return null;
            // пустой результат после отсева — не «модель недоступна», а текст, который нельзя показать
            return grounded(completeSentences(withoutRepeats(text)), prompt);
        } catch (Exception exception) {
            log.warn("Разбор от модели не получен: {}", exception.getMessage());
            return null;
        }
    }

    /** Повторённые предложения выбрасываются: у 7B на малом числе замечаний бывает петля. */
    public static String withoutRepeats(String text) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[^.!?\\n]+[.!?]?[ \\t]*|\\n").matcher(text);
        while (m.find()) {
            String sentence = m.group();
            String key = sentence.trim().toLowerCase(java.util.Locale.ROOT);
            if (key.length() > 12 && !seen.add(key)) continue;
            out.append(sentence);
        }
        return out.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * Обрезка до последнего законченного предложения: на лимите токенов модель
     * обрывается на полуслове («согласно классифик»), а такое показывать нельзя.
     */

    public static String completeSentences(String text) {
        for (int i = text.length() - 1; i > text.length() / 3; i--) {
            char c = text.charAt(i);
            if (c != '.' && c != '!' && c != '?') continue;
            // «2.» — номер пункта списка, а не конец предложения
            int j = i - 1;
            while (j >= 0 && Character.isDigit(text.charAt(j))) j--;
            boolean listMarker = c == '.' && j < i - 1 && (j < 0 || Character.isWhitespace(text.charAt(j)));
            if (!listMarker) return text.substring(0, i + 1).trim();
        }
        return text;
    }

    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("\\d+[а-яА-Я]?");
    private static final java.util.regex.Pattern QUOTED = java.util.regex.Pattern.compile("«([^»]{2,})»");
    private static final java.util.regex.Pattern POINT_NUMBER = java.util.regex.Pattern.compile("^\\s*\\d+\\.\\s*");

    /**
     * Пункты с выдуманными фактами выбрасываются: число или «цитата», которых нет ни в данных,
     * ни в самом запросе. Так в разбор не попадают «улица Ленина, 20» и «кватрира», когда
     * в карточке ничего подобного не было. Оставшиеся пункты перенумеровываются.
     */
    public static String grounded(String text, String source) {
        String haystack = source.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        List<String> kept = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            String body = POINT_NUMBER.matcher(line).replaceFirst("").trim();
            if (body.isEmpty()) continue;
            boolean invented = false;
            java.util.regex.Matcher n = NUMBER.matcher(body);
            while (!invented && n.find()) {
                invented = !haystack.contains(n.group().toLowerCase(java.util.Locale.ROOT));
            }
            java.util.regex.Matcher q = QUOTED.matcher(body);
            while (!invented && q.find()) {
                invented = !haystack.contains(q.group(1).toLowerCase(java.util.Locale.ROOT).replace('ё', 'е'));
            }
            if (!invented) kept.add(body);
        }
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < kept.size(); k++) out.append(k + 1).append(". ").append(kept.get(k)).append('\n');
        return out.toString().trim();
    }
}
