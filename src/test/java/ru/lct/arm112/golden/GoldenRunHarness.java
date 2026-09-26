package ru.lct.arm112.golden;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.service.TrainingEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Эталонный набор прогонов (задача E1 плана): десять сценариев работы обучающегося
 * с заранее посчитанным по правилам результатом.
 *
 * <p>Назначение двойное:
 * <ul>
 *   <li><b>защита от регресса</b> — любая правка оценщика прогоняется по тем же десяти прогонам;</li>
 *   <li><b>замер стоимости</b> — тайминги каждого этапа пишутся в {@code benchmarks/golden-*.csv},
 *       чтобы видеть, сколько производительности съела очередная доработка.</li>
 * </ul>
 *
 * <p>Запуск:
 * <pre>
 *   mvn test -Dtest=GoldenRunHarness -Dgolden.stage=0-baseline
 *   mvn test -Dtest=GoldenRunHarness -Dgolden.stage=1-issues -Dgolden.strict=true
 * </pre>
 *
 * <p>Без {@code -Dgolden.strict=true} расхождения не валят сборку, а печатаются и пишутся в CSV:
 * на первом прогоне ожидания посчитаны вручную по документации, и расхождение — это находка,
 * которую надо разобрать, а не обязательно ошибка кода.
 *
 * <p>Тайминги сняты на H2 в памяти и на одной машине: они годятся для сравнения этапов между собой,
 * но не являются замером продуктивной производительности — для неё есть отдельный нагрузочный профиль.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GoldenRunHarness {

    private static final String CONTRACT = "0.3";

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TrainingEngine trainingEngine;

    @Autowired
    AssessmentRepository assessmentRepository;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger httpCalls = new AtomicInteger();

    @Test
    void runGoldenSet() throws Exception {
        String stage = System.getProperty("golden.stage", "local");
        boolean strict = Boolean.getBoolean("golden.strict");
        String commit = System.getProperty("golden.commit", "");

        JsonNode runs;
        try (InputStream stream = new ClassPathResource("golden/runs.json").getInputStream()) {
            runs = objectMapper.readTree(stream);
        }

        String admin = login("admin", "admin");
        String teacher = login("teacher", "teacher");

        // Телефония ускорена: боевые значения дают 15 секунд на один доклад руководителю,
        // а прогонов с докладом четыре. На оценку длительность симулятора не влияет —
        // проверяется факт доклада, а нормативы считаются по отметкам таймлайна.
        send("PUT", "/api/v1/admin/settings", "{\"telephony.ringing_ms\":\"100\","
                + "\"telephony.connect_ms\":\"150\",\"telephony.acknowledge_ms\":\"200\"}", admin, null);

        GoldenCsv csv = new GoldenCsv(stage);
        List<GoldenResult> results = new ArrayList<>();
        for (JsonNode run : runs) {
            GoldenResult result = execute(run, admin, teacher);
            results.add(result);
            csv.add(result, stage, commit);
        }
        csv.write();

        report(stage, results, csv.file().toString());

        List<String> mismatched = results.stream().filter(r -> !"OK".equals(r.status()))
                .map(r -> r.runId() + " «" + r.runName() + "»: " + r.status()).toList();
        if (strict && !mismatched.isEmpty()) {
            fail("Эталонные прогоны разошлись с ожиданиями:\n  " + String.join("\n  ", mismatched));
        }
        // без strict проверяем только то, что прогон вообще состоялся
        assertThat(results).hasSize(runs.size());
        assertThat(results).noneMatch(r -> r.status().startsWith("ERROR"));
    }

    // ------------------------------------------------------------------ один прогон

    private GoldenResult execute(JsonNode run, String admin, String teacher) throws Exception {
        String id = run.get("id").asText();
        String mode = run.get("mode").asText();
        String kind = run.get("kind").asText();
        String scenarioId = run.get("scenario").asText();
        httpCalls.set(0);

        long runStart = System.nanoTime();

        // --- подготовка: свой обучающийся на прогон, занятие из одной вводной
        long t0 = System.nanoTime();
        String login = "gold-" + id.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6);
        post("/api/v1/admin/users", "{\"login\":\"" + login + "\",\"password\":\"secret1\",\"displayName\":\"Эталон "
                + id + "\",\"role\":\"TRAINEE\",\"workstationNumber\":\"" + (10 + results(id)) + "\"}", admin, null);
        String token = login(login, "secret1");
        String traineeId = json(get("/api/v1/auth/me", token)).get("id").asText();
        HttpResponse<String> lesson = post("/api/v1/teacher/lessons",
                "{\"title\":\"Эталон " + id + "\",\"kind\":\"" + kind + "\",\"mode\":\"" + mode
                        + "\",\"cardSource\":\"GENERATED\",\"scenarioIds\":[" + scenarioIds(run) + "],\"traineeIds\":[\""
                        + traineeId + "\"]}", teacher, null);
        String lessonId = json(lesson).get("id").asText();
        post("/api/v1/teacher/lessons/" + lessonId + "/start", null, teacher, null);
        String sessionId = json(get("/api/v1/trainee/context", token)).get("activeSession").get("id").asText();
        long setupMs = ms(t0);

        // --- действия обучающегося и оценка
        long workMs;
        long assessMs;
        long t1 = System.nanoTime();
        if ("CARD_FILL".equals(mode)) {
            int cards = 1 + run.path("extraScenarios").size();
            long lastSave = 0;
            for (int i = 0; i < cards; i++) {
                String draftId = fill(run, token, sessionId, i == 0);
                if (i == cards - 1) workMs = ms(t1);
                long t2 = System.nanoTime();
                HttpResponse<String> saved = post("/api/v1/card-drafts/" + draftId + "/save", null, token, key());
                assertThat(saved.statusCode()).as("save " + id + " #" + (i + 1) + ": " + saved.body()).isEqualTo(200);
                lastSave = ms(t2);
            }
            workMs = ms(t1);
            assessMs = lastSave;
        } else {
            act(run, token, sessionId);
            workMs = ms(t1);
            long t2 = System.nanoTime();
            HttpResponse<String> submitted = post("/api/v1/training-sessions/" + sessionId + "/submit", null, token, key());
            assertThat(submitted.statusCode()).as("submit " + id + ": " + submitted.body()).isEqualTo(202);
            assessMs = ms(t2);
        }

        // --- чтение готовой оценки
        long t3 = System.nanoTime();
        JsonNode assessment = assessmentOf(token, sessionId);
        long fetchMs = ms(t3);

        // Замечания должны лечь в assessment_issue: на них держатся агрегаты дашбордов.
        int dbIssues = assessmentRepository.findIssues(UUID.fromString(assessment.get("id").asText())).size();

        return compare(run, assessment, dbIssues, ms(runStart), setupMs, workMs, assessMs, fetchMs);
    }

    /** Режим оператора 112: принять вызов, заполнить поля. Сохранение — снаружи, оно считается отдельно. */
    private String fill(JsonNode run, String token, String sessionId, boolean first) throws Exception {
        if (first && run.path("missRingingCall").asBoolean(false)) {
            trainingEngine.forceRingTimeout(UUID.fromString(sessionId));
            trainingEngine.forceArrival(UUID.fromString(sessionId));
        }
        HttpResponse<String> created = post("/api/v1/card-drafts", "{\"sessionId\":\"" + sessionId + "\"}", token, null);
        assertThat(created.statusCode()).as("draft: " + created.body()).isEqualTo(201);
        String draftId = json(created).get("id").asText();

        int shift = run.path("startedAtShiftSeconds").asInt(0);
        if (shift > 0) {
            trainingEngine.forceDraftStartedAt(UUID.fromString(draftId), Instant.now().minusSeconds(shift));
        }

        // поля берутся по сценарию пришедшей вводной: очередь занятия задаёт порядок, а не список в прогоне
        String arrived = json(created).get("scenarioId").asText();
        JsonNode f = run.path("perScenarioFill").path(arrived);
        if (f.isMissingNode()) f = run.get("fill");
        String body = "{\"address\":" + f.get("address").toString()
                + ",\"incidentTypeIds\":" + f.get("incidentTypeIds").toString()
                + ",\"description\":" + objectMapper.writeValueAsString(f.get("description").asText()) + "}";
        HttpResponse<String> patched = patch("/api/v1/card-drafts/" + draftId, body, token);
        assertThat(patched.statusCode()).as("patch: " + patched.body()).isEqualTo(200);
        return draftId;
    }

    /** Режим диспетчера ДДС: цепочка статусов из «acts», при необходимости — обязательный доклад. */
    private void act(JsonNode run, String token, String sessionId) throws Exception {
        JsonNode cards = json(get("/api/v1/cards?sessionId=" + sessionId, token)).get("items");
        assertThat(cards.size()).as("карточка не пришла в журнал").isGreaterThan(0);
        String cardId = cards.get(0).get("id").asText();
        openCard(token, cardId);

        String comment = run.path("comment").asText("");
        for (JsonNode act : run.get("acts")) {
            String action = act.asText();
            switch (action) {
                case "ACCEPT" -> post("/api/v1/cards/" + cardId + "/acceptance",
                        "{\"action\":\"ACCEPT\"}", token, key());
                case "DECLINE" -> post("/api/v1/cards/" + cardId + "/acceptance",
                        "{\"action\":\"DECLINE\",\"reasonCode\":\"" + run.path("declineReason").asText("NOT_COMPETENCE")
                                + "\",\"comment\":" + objectMapper.writeValueAsString(comment) + "}", token, key());
                case "CALL" -> awaitAcknowledged(token, cardId);
                case "COMPLETE" -> post("/api/v1/cards/" + cardId + "/reaction-events",
                        "{\"action\":\"COMPLETE\",\"comment\":" + objectMapper.writeValueAsString(comment) + "}",
                        token, key());
                default -> post("/api/v1/cards/" + cardId + "/reaction-events",
                        "{\"action\":\"" + action + "\"}", token, key());
            }
        }
        int spacing = run.path("timelineSpacingSeconds").asInt(0);
        if (spacing > 0) {
            // прогон проставляет статусы за миллисекунды; без этого любая работа выглядит прокликиванием
            trainingEngine.forceTimelineSpacing(UUID.fromString(cardId), spacing);
        }
    }

    /**
     * Открыть карточку. Открытие занимает реальное время ({@code simulation.card_open_ms}),
     * и до его конца действия недоступны — поэтому здесь ожидание, а не один запрос.
     */
    private void openCard(String token, String cardId) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (!"RECEIVED".equals(json(get("/api/v1/cards/" + cardId, token)).get("status").asText())) return;
            Thread.sleep(25);
        }
        fail("карточка так и не открылась");
    }

    /** Обязательный доклад руководителю: дождаться, пока симулятор телефонии дойдёт до «принято». */
    private void awaitAcknowledged(String token, String cardId) throws Exception {
        HttpResponse<String> started = post("/api/v1/cards/" + cardId + "/outbound-calls",
                "{\"shortNumber\":\"1102\"}", token, key());
        assertThat(started.statusCode()).as("звонок: " + started.body()).isEqualTo(201);
        String callId = json(started).get("id").asText();
        for (int i = 0; i < 100; i++) {
            String state = json(get("/api/v1/outbound-calls/" + callId, token)).get("state").asText();
            if ("ACKNOWLEDGED".equals(state) || "ENDED".equals(state)) return;
            Thread.sleep(50);
        }
        fail("доклад руководителю не дошёл до статуса «принято»");
    }

    /** Оценка появляется после завершения сессии; в списке результатов она уже видима для тренировки. */
    private JsonNode assessmentOf(String token, String sessionId) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (JsonNode item : json(get("/api/v1/trainee/results", token))) {
                if (item.get("sessionId").asText().equals(sessionId) && item.hasNonNull("assessmentId")) {
                    return json(get("/api/v1/assessments/" + item.get("assessmentId").asText(), token));
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("оценка по сессии " + sessionId + " не появилась");
    }

    // ------------------------------------------------------------------ сравнение с эталоном

    private GoldenResult compare(JsonNode run, JsonNode assessment, int dbIssues, long runMs, long setupMs,
                                 long workMs, long assessMs, long fetchMs) {
        JsonNode expected = run.get("expected");
        double expectedTotal = expected.get("total").asDouble();
        double actualTotal = assessment.get("totalScore").asDouble();

        Map<String, Double> expectedCriteria = new LinkedHashMap<>();
        expected.get("criteria").properties().forEach(e -> expectedCriteria.put(e.getKey(), e.getValue().asDouble()));
        Map<String, Double> actualCriteria = new LinkedHashMap<>();
        double maxDelta = 0;
        for (String code : expectedCriteria.keySet()) {
            JsonNode node = assessment.get(fieldOf(code));
            double value = node == null || node.isNull() ? Double.NaN : node.asDouble();
            actualCriteria.put(code, value);
            if (!Double.isNaN(value)) maxDelta = Math.max(maxDelta, Math.abs(value - expectedCriteria.get(code)));
        }

        Set<String> expectedIssues = new TreeSet<>();
        expected.get("issues").forEach(node -> expectedIssues.add(node.asText()));
        Set<String> actualIssues = new TreeSet<>();
        assessment.get("issues").forEach(node -> actualIssues.add(node.get("code").asText()));

        Set<String> missing = new LinkedHashSet<>(expectedIssues);
        missing.removeAll(actualIssues);
        Set<String> unexpected = new LinkedHashSet<>(actualIssues);
        unexpected.removeAll(expectedIssues);

        List<String> problems = new ArrayList<>();
        if (Math.abs(actualTotal - expectedTotal) > 0.5) problems.add("итог");
        if (maxDelta > 0.5) problems.add("критерии");
        if (!missing.isEmpty()) problems.add("нет замечаний: " + String.join(",", missing));
        if (!unexpected.isEmpty()) problems.add("лишние замечания: " + String.join(",", unexpected));
        if (dbIssues != assessment.get("issues").size()) {
            problems.add("в БД " + dbIssues + " замечаний вместо " + assessment.get("issues").size());
        }
        String status = problems.isEmpty() ? "OK" : "MISMATCH(" + String.join("; ", problems) + ")";

        return new GoldenResult(run.get("id").asText(), run.get("name").asText(), run.get("mode").asText(),
                run.get("kind").asText(), run.get("scenario").asText(),
                expectedTotal, actualTotal, format(expectedCriteria), format(actualCriteria), maxDelta,
                String.join(",", expectedIssues), String.join(",", actualIssues),
                String.join(",", missing), String.join(",", unexpected),
                assessment.path("syntaxErrors").asInt(0), dbIssues, status,
                runMs, setupMs, workMs, assessMs, fetchMs, httpCalls.get());
    }

    private static String fieldOf(String code) {
        return switch (code) {
            case "address" -> "addressScore";
            case "classification" -> "classificationScore";
            case "services" -> "servicesScore";
            case "timing" -> "timingScore";
            case "language" -> "languageScore";
            case "actions" -> "actionsScore";
            case "communication" -> "communicationScore";
            default -> code;
        };
    }

    private static String format(Map<String, Double> values) {
        List<String> parts = new ArrayList<>();
        values.forEach((k, v) -> parts.add(k + "=" + (Double.isNaN(v) ? "—" : String.format(java.util.Locale.ROOT, "%.1f", v))));
        return String.join(" ", parts);
    }

    private void report(String stage, List<GoldenResult> results, String file) {
        StringBuilder out = new StringBuilder("\n=== Эталонные прогоны, этап: " + stage + " ===\n");
        out.append(String.format("%-5s %-38s %7s %7s %7s %6s %6s  %s%n",
                "ID", "Прогон", "ожид.", "факт", "Δ", "оц.мс", "всего", "статус"));
        for (GoldenResult r : results) {
            out.append(String.format("%-5s %-38s %7.1f %7.1f %+7.1f %6d %6d  %s%n",
                    r.runId(), trim(r.runName()), r.expectedTotal(), r.actualTotal(), r.totalDelta(),
                    r.assessMs(), r.runMs(), r.status()));
        }
        long assessTotal = results.stream().mapToLong(GoldenResult::assessMs).sum();
        long runTotal = results.stream().mapToLong(GoldenResult::runMs).sum();
        long ok = results.stream().filter(r -> "OK".equals(r.status())).count();
        out.append(String.format("%nСовпало с эталоном: %d из %d. Оценка суммарно %d мс, прогон суммарно %d мс.%n",
                ok, results.size(), assessTotal, runTotal));
        out.append("CSV: ").append(file).append('\n');
        System.out.println(out);
    }

    private static String trim(String value) {
        return value.length() <= 38 ? value : value.substring(0, 37) + "…";
    }

    // ------------------------------------------------------------------ HTTP

    /** Сценарии занятия: основной плюс дополнительные, если прогон проверяет несколько вводных. */
    private static String scenarioIds(JsonNode run) {
        List<String> ids = new ArrayList<>();
        ids.add("\"" + run.get("scenario").asText() + "\"");
        run.path("extraScenarios").forEach(node -> ids.add("\"" + node.asText() + "\""));
        return String.join(",", ids);
    }

    private int results(String runId) {
        return Math.abs(runId.hashCode()) % 10;
    }

    private String login(String username, String password) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null, null);
        assertThat(response.statusCode()).as("login " + username + ": " + response.body()).isEqualTo(200);
        return json(response).get("accessToken").asText();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send("GET", path, null, token, null);
    }

    private HttpResponse<String> post(String path, String body, String token, String idempotencyKey) throws Exception {
        return send("POST", path, body, token, idempotencyKey);
    }

    private HttpResponse<String> patch(String path, String body, String token) throws Exception {
        return send("PATCH", path, body, token, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String token, String idempotencyKey)
            throws Exception {
        httpCalls.incrementAndGet();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Contract-Version", CONTRACT);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
