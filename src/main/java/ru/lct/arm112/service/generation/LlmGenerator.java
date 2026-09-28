package ru.lct.arm112.service.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.FormalAddress;
import ru.lct.arm112.api.ApiModels.ScenarioCaller;
import ru.lct.arm112.api.ApiModels.ScenarioListItem;
import ru.lct.arm112.api.ApiModels.ScenarioUpsert;
import ru.lct.arm112.service.ReferenceDataService;
import ru.lct.arm112.service.ScenarioService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Генератор сценариев на локальной языковой модели.
 *
 * <p>Обращается к сайдкару {@code llama.cpp} по OpenAI-совместимому интерфейсу
 * (см. {@code compose.llm.yaml}). Сайдкар не обязателен: если он не отвечает,
 * {@link #generate} возвращает пустой список, и {@link GenerationHandler}
 * остаётся без кандидатов — приложение при этом работает.
 *
 * <h2>Что модель решает, а что решено за неё</h2>
 * Модель пишет <b>только текст заявителя</b>. Адрес выбирается из справочника кодом,
 * тип — задаётся кодом, службы выводятся по ЕКП. Причина не в недоверии, а в раскладе
 * рисков: выдуманная улица или тип не из классификатора — это брак, который придётся
 * отсеивать, а живой текст заявителя машина пишет лучше перестановки билетов.
 * Поэтому модели отдаётся ровно то, в чём она сильна.
 *
 * <p>Результат всё равно проходит {@link ScenarioValidator}: правило «в библиотеку
 * попадает только сошедшееся со справочниками» не зависит от того, кто генерировал.
 */
@Component
public class LlmGenerator implements ScenarioGenerator {
    private static final Logger log = LoggerFactory.getLogger(LlmGenerator.class);

    /**
     * Ограничение выборки токенов: в ответе допустимы только кириллица, цифры и пунктуация.
     *
     * <p>Без него и 3B, и 7B срываются на китайский прямо посреди фразы — больше половины
     * генераций уходило в брак. Системное сообщение «отвечай по-русски» не помогает,
     * снижение температуры убирает срывы ценой одинаковых текстов.
     *
     * <p>Грамматика решает задачу на уровне выборки: токен с иероглифом просто не может
     * быть выбран. Замер показал 0 срывов из 6 при сохранённом разнообразии и даже
     * небольшом ускорении — словарь-кандидат сужается.
     */
    private static final String CYRILLIC_ONLY = """
            root ::= line
            line ::= char{40,400}
            char ::= [а-яА-ЯёЁ0-9 ,.:;()«»!?/-]
            """;

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final ScenarioService scenarios;
    private final ReferenceDataService references;
    private final GenerationBases bases;
    private final Random random = new Random();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public LlmGenerator(@Value("${arm112.llm.url:}") String baseUrl, ObjectMapper objectMapper,
                        ScenarioService scenarios, ReferenceDataService references, GenerationBases bases) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.objectMapper = objectMapper;
        this.scenarios = scenarios;
        this.references = references;
        this.bases = bases;
    }

    @Override
    public String name() {
        return "llm";
    }

    public boolean available() {
        if (baseUrl.isEmpty()) return false;
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception exception) {
            return false;
        }
    }

    @Override
    public List<ScenarioUpsert> generate(String category, int count, int difficulty) {
        if (!available()) {
            log.info("Языковая модель недоступна, кандидатов не будет");
            return List.of();
        }
        // основа — билет или сценарий банка той же категории с чистым эталоном:
        // от неё берутся адрес и тип, а модель пишет только текст заявителя
        List<ScenarioListItem> pool = scenarios.list(category, null, null).stream()
                .filter(bases::usable)
                .toList();
        if (pool.isEmpty()) return List.of();

        List<ScenarioUpsert> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ScenarioListItem base = pool.get(random.nextInt(pool.size()));
            String typeId = base.expectedIncidentTypes().get(0);
            ReferenceDataService.IncidentType type = references.incidentType(typeId);
            if (type == null) continue;
            String callerText = write(type.label(), bases.placeOf(base));
            if (callerText == null) continue;
            result.add(new ScenarioUpsert(null, "Сгенерировано: " + type.label(), category, difficulty,
                    callerText, caller(), base.rawAddress(), base.expectedAddress(),
                    List.of(typeId), null, "ACCEPT", null, true));
        }
        return result;
    }

    /** Один вызов модели: текст заявителя по заданному типу происшествия и месту. */
    private String write(String typeLabel, String place) {
        String prompt = """
                Ты пишешь учебные вводные для тренажёра операторов службы 112.
                Напиши, что сообщает заявитель по телефону о происшествии типа «%s».
                Место происшествия: %s. Описание должно подходить к этому месту.

                Требования:
                - одна-две фразы, как записал бы оператор: сжато, по делу, без приветствий;
                - только обстановка: что случилось, есть ли пострадавшие, что видно на месте;
                - НЕ называй адрес, улицу и номер дома;
                - НЕ используй формулировку «%s» дословно — заявитель говорит обычными словами;
                - без кавычек и пояснений, только сам текст.
                """.formatted(typeLabel, place, typeLabel);
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "messages", List.of(java.util.Map.of("role", "user", "content", prompt)),
                    "temperature", 0.8,
                    "max_tokens", 160,
                    "grammar", CYRILLIC_ONLY));
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                            .timeout(Duration.ofMinutes(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Модель ответила {}: {}", response.statusCode(), response.body());
                return null;
            }
            JsonNode json = objectMapper.readTree(response.body());
            String text = json.path("choices").path(0).path("message").path("content").asText("").trim();
            return clean(text);
        } catch (Exception exception) {
            log.warn("Обращение к модели не удалось: {}", exception.getMessage());
            return null;
        }
    }

    /** Модель любит обрамлять ответ кавычками и пояснениями — оставляем только текст. */
    private static String clean(String text) {
        String result = text.replaceAll("^[\"«»\\s]+|[\"«»\\s]+$", "").trim();
        int newline = result.indexOf('\n');
        if (newline > 0) result = result.substring(0, newline).trim();
        return result.isBlank() ? null : result;
    }

    private ScenarioCaller caller() {
        List<String> names = List.of("Иванова Мария Сергеевна", "Петров Алексей Николаевич",
                "Кузнецова Ольга Ивановна", "Смирнов Дмитрий Павлович", "Волкова Анна Андреевна");
        return new ScenarioCaller(names.get(random.nextInt(names.size())),
                "7916" + (1000000 + random.nextInt(8999999)));
    }
}
