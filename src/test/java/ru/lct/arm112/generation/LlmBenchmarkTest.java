package ru.lct.arm112.generation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.lct.arm112.api.ApiModels.ScenarioUpsert;
import ru.lct.arm112.service.generation.LlmGenerator;
import ru.lct.arm112.service.generation.ScenarioValidator;
import ru.lct.arm112.service.generation.ScenarioValidator.Verdict;
import ru.lct.arm112.service.generation.ScenarioValidator.Violation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Замер языковой модели на том железе, где будет работать система.
 *
 * <p>Отвечает на два вопроса, от которых зависит, годится ли локальная модель без GPU:
 * <ol>
 *   <li><b>скорость</b> — сколько секунд стоит одна вводная и укладывается ли пачка
 *       в «минуты», которые заказчик назвал приемлемыми для офлайновой генерации;</li>
 *   <li><b>годность</b> — какая доля сгенерированного проходит {@link ScenarioValidator},
 *       то есть сколько работы останется преподавателю.</li>
 * </ol>
 *
 * <p>Не запускается без модели и не входит в сборку CI: это измерение, а не проверка.
 *
 * <pre>
 *   docker compose -f compose.llm.yaml up -d llm
 *   mvn test -Dtest=LlmBenchmarkTest -Dllm.url=http://localhost:8090 -Dllm.name=qwen2.5-7b-q4
 * </pre>
 */
@SpringBootTest(properties = "arm112.llm.url=${llm.url:}")
class LlmBenchmarkTest {

    @Autowired
    LlmGenerator generator;

    @Autowired
    ScenarioValidator validator;

    @Autowired
    ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void measureSpeedAndYield() throws Exception {
        String url = System.getProperty("llm.url", "");
        Assumptions.assumeTrue(!url.isBlank() && generator.available(),
                "Модель не запущена — замер пропущен");
        String model = System.getProperty("llm.name", "unknown");
        int count = Integer.getInteger("llm.count", 10);

        // --- 1. Сырая скорость: прогрев и замер по данным самого сервера
        warmUp(url);
        Timing timing = timeOne(url);
        System.out.printf(Locale.ROOT,
                "%n=== Скорость модели «%s» ===%n"
                        + "чтение запроса: %.1f ток/с   генерация: %.1f ток/с%n"
                        + "выдано токенов: %d за %.1f с%n",
                model, timing.promptTokensPerSecond(), timing.generatedTokensPerSecond(),
                timing.generatedTokens(), timing.totalSeconds());

        // --- 2. Годность: сколько кандидатов переживают проверку
        long startedAt = System.nanoTime();
        List<ScenarioUpsert> candidates = generator.generate("FIRE", count, 5);
        double batchSeconds = (System.nanoTime() - startedAt) / 1e9;

        Map<String, Integer> rejections = new LinkedHashMap<>();
        Map<String, Integer> warnings = new LinkedHashMap<>();
        List<String> samples = new ArrayList<>();
        int accepted = 0;
        for (ScenarioUpsert candidate : candidates) {
            Verdict verdict = validator.validate(candidate);
            verdict.warnings().forEach(v -> warnings.merge(v.code(), 1, Integer::sum));
            if (verdict.valid()) {
                accepted++;
            } else {
                verdict.fatal().forEach(v -> rejections.merge(v.code(), 1, Integer::sum));
            }
            if (samples.size() < 5) {
                samples.add((verdict.valid() ? "[принят]  " : "[отсеян]  ")
                        + candidate.callerText()
                        + (verdict.valid() ? "" : " → " + verdict.fatal().stream()
                        .map(Violation::code).toList()));
            }
        }

        double perScenario = candidates.isEmpty() ? 0 : batchSeconds / candidates.size();
        System.out.printf(Locale.ROOT,
                "%n=== Годность: %d кандидатов ===%n"
                        + "принято %d (%.0f %%), отсеяно %d%n"
                        + "пачка за %.1f с, по %.1f с на вводную%n"
                        + "причины отсева: %s%nзамечания:      %s%n%nпримеры:%n",
                candidates.size(), accepted,
                candidates.isEmpty() ? 0.0 : 100.0 * accepted / candidates.size(),
                candidates.size() - accepted, batchSeconds, perScenario,
                rejections.isEmpty() ? "нет" : rejections, warnings.isEmpty() ? "нет" : warnings);
        samples.forEach(s -> System.out.println("  " + s));

        writeCsv(model, timing, candidates.size(), accepted, batchSeconds, perScenario, rejections);

        assertThat(candidates).as("модель не выдала ни одного кандидата").isNotEmpty();
    }

    private record Timing(double promptTokensPerSecond, double generatedTokensPerSecond,
                          int generatedTokens, double totalSeconds) {}

    /** Первый запрос грузит веса в страничный кэш и мерить его бессмысленно. */
    private void warmUp(String url) throws Exception {
        ask(url, "Ответь одним словом: готов?", 8);
    }

    private Timing timeOne(String url) throws Exception {
        JsonNode response = ask(url,
                "Напиши одной фразой, что сообщает заявитель о возгорании мусорного контейнера.", 160);
        JsonNode t = response.path("timings");
        double generated = t.path("predicted_per_second").asDouble(0);
        double prompt = t.path("prompt_per_second").asDouble(0);
        int tokens = response.path("usage").path("completion_tokens").asInt(0);
        double seconds = (t.path("prompt_ms").asDouble(0) + t.path("predicted_ms").asDouble(0)) / 1000.0;
        return new Timing(prompt, generated, tokens, seconds);
    }

    private JsonNode ask(String url, String prompt, int maxTokens) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.7, "max_tokens", maxTokens));
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url + "/v1/chat/completions"))
                        .timeout(Duration.ofMinutes(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private void writeCsv(String model, Timing timing, int produced, int accepted,
                          double batchSeconds, double perScenario, Map<String, Integer> rejections)
            throws Exception {
        Path file = Path.of("benchmarks", "llm-" + model + ".csv");
        Files.createDirectories(file.getParent());
        String header = "model;prompt_tok_per_s;gen_tok_per_s;produced;accepted;accept_pct;"
                + "batch_seconds;seconds_per_scenario;rejections";
        String row = String.join(";", model,
                num(timing.promptTokensPerSecond()), num(timing.generatedTokensPerSecond()),
                String.valueOf(produced), String.valueOf(accepted),
                num(produced == 0 ? 0 : 100.0 * accepted / produced),
                num(batchSeconds), num(perScenario),
                rejections.toString().replace(';', ','));
        Files.write(file, ("﻿" + header + "\n" + row + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("\nCSV: " + file);
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replace('.', ',');
    }
}
