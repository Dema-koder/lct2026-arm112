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

    /** Кириллица, цифры и пунктуация; перевод строки нужен для списка из пунктов. */
    private static final String CYRILLIC_ONLY = """
            root ::= line+
            line ::= char{10,400} "\n"
            char ::= [а-яА-ЯёЁ0-9 ,.:;()«»!?/-]
            """;

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
        if (!available()) return null;
        String prompt = """
                Ты наставник оператора службы 112. Ниже структура замечаний по занятию обучающегося.
                Напиши разбор: три-четыре коротких пункта. В каждом — что сделано не так
                и почему это важно в реальной работе службы.

                Пиши только по этим данным, ничего не добавляй от себя.
                Обращайся к обучающемуся на «вы». По-русски.

                %s
                """.formatted(structuredIssues);
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.4,
                    "max_tokens", 320,
                    "grammar", CYRILLIC_ONLY));
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
            return text.isBlank() ? null : text;
        } catch (Exception exception) {
            log.warn("Разбор от модели не получен: {}", exception.getMessage());
            return null;
        }
    }
}
