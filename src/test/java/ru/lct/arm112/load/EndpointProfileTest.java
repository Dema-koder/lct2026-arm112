package ru.lct.arm112.load;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Профиль всех значимых ручек чтения на заданном объёме базы.
 *
 * <p>Отличие от {@link AnalyticsLoadTest}: тот меряет экраны разбора на одной базе,
 * здесь — <b>каждая</b> ручка чтения, и замер делается дважды, на малой и на большой
 * базе. Смысл не в абсолютных миллисекундах, а в <b>отношении</b>: ручка, выросшая
 * вместе с базой, читает больше, чем ей нужно, и рано или поздно упрётся в норматив.
 *
 * <p>Абсолютная задержка такого не показывает: на пустой базе полный проход по таблице
 * неотличим от выборки по индексу.
 *
 * <pre>
 *   mvn test -Dtest=EndpointProfileTest -Dload.url=http://localhost:8085 \
 *            -Dprofile.lessons=2 -Dprofile.trainees=10 -Dprofile.label=small
 *   mvn test -Dtest=EndpointProfileTest -Dload.url=http://localhost:8085 \
 *            -Dprofile.lessons=20 -Dprofile.trainees=10 -Dprofile.label=large
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndpointProfileTest {

    private static final int REPEATS = 200;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<Long>> latencies = new ConcurrentHashMap<>();

    @Test
    void profileEveryReadEndpoint() throws Exception {
        String base = System.getProperty("load.url", "");
        Assumptions.assumeTrue(!base.isBlank(), "Профиль пропущен: не задан -Dload.url");
        int lessons = Integer.getInteger("profile.lessons", 2);
        int trainees = Integer.getInteger("profile.trainees", 10);
        String label = System.getProperty("profile.label", "profile");

        String admin = login(base, "admin", "admin");
        String teacher = login(base, "teacher", "teacher");

        // --- наполнение базы вне замера
        List<String[]> group = new ArrayList<>();
        for (int i = 0; i < trainees; i++) group.add(createTrainee(base, admin, i));
        // Занятия идут по разным вводным: иначе в библиотеке накапливаются наблюдения
        // всего по двум сценариям, и стоимость экрана качества не проявляется.
        int distinct = Integer.getInteger("profile.scenarios", 2);
        List<String> pool = pickScenarios(base, teacher, distinct);
        expected.putAll(expectedFills(base, teacher, pool));
        List<String> scenarioIds = pool.subList(0, Math.min(2, pool.size()));
        String lastLesson = null;
        for (int l = 0; l < lessons; l++) {
            int from = (l * 2) % Math.max(1, pool.size() - 1);
            List<String> forLesson = pool.subList(from, Math.min(from + 2, pool.size()));
            if (forLesson.size() < 2) forLesson = pool.subList(0, Math.min(2, pool.size()));
            scenarioIds = forLesson;
            lastLesson = startLesson(base, teacher, group, scenarioIds);
            // Часть группы работает верно, часть — с ошибкой адреса. Без разброса
            // результатов модель Раша отбрасывает сценарий целиком, и весь её путь
            // (полный проход по карточкам плюс запрос на сценарий) остаётся неизмеренным.
            for (int t = 0; t < group.size(); t++) fillAndSubmit(base, group.get(t)[0], (t + l) % 3 != 0);
            post(base, "/api/v1/teacher/lessons/" + lastLesson + "/complete", null, teacher, null);
        }

        String traineeToken = group.get(0)[0];
        String traineeId = group.get(0)[1];
        String assessmentId = firstAssessment(base, traineeToken);
        String scenarioId = scenarioIds.get(0);

        // --- замер: каждая ручка REPEATS раз
        Map<String, String[]> endpoints = new LinkedHashMap<>();
        endpoints.put("teacher/lessons", new String[]{"/api/v1/teacher/lessons", teacher});
        endpoints.put("teacher/lessons/{id}", new String[]{"/api/v1/teacher/lessons/" + lastLesson, teacher});
        endpoints.put("teacher/lessons/{id}/monitor", new String[]{"/api/v1/teacher/lessons/" + lastLesson + "/monitor", teacher});
        endpoints.put("teacher/lessons/{id}/report", new String[]{"/api/v1/teacher/lessons/" + lastLesson + "/report", teacher});
        endpoints.put("teacher/lessons/{id}/overview", new String[]{"/api/v1/teacher/lessons/" + lastLesson + "/overview", teacher});
        endpoints.put("teacher/trainees/{id}/profile", new String[]{"/api/v1/teacher/trainees/" + traineeId + "/profile", teacher});
        endpoints.put("teacher/scenarios/quality", new String[]{"/api/v1/teacher/scenarios/quality", teacher});
        endpoints.put("teacher/calibration", new String[]{"/api/v1/teacher/calibration?mode=CARD_FILL", teacher});
        endpoints.put("teacher/scenarios", new String[]{"/api/v1/teacher/scenarios", teacher});
        endpoints.put("teacher/scenarios/{id}", new String[]{"/api/v1/teacher/scenarios/" + scenarioId, teacher});
        endpoints.put("teacher/groups", new String[]{"/api/v1/teacher/groups", teacher});
        endpoints.put("teacher/materials", new String[]{"/api/v1/teacher/materials", teacher});
        endpoints.put("trainee/context", new String[]{"/api/v1/trainee/context", traineeToken});
        endpoints.put("trainee/results", new String[]{"/api/v1/trainee/results", traineeToken});
        endpoints.put("trainee/rating", new String[]{"/api/v1/trainee/rating", traineeToken});
        endpoints.put("trainee/materials", new String[]{"/api/v1/trainee/materials", traineeToken});
        endpoints.put("assessments/{id}", new String[]{"/api/v1/assessments/" + assessmentId, traineeToken});
        endpoints.put("assessments/{id}/debrief", new String[]{"/api/v1/assessments/" + assessmentId + "/debrief", traineeToken});
        endpoints.put("admin/users", new String[]{"/api/v1/admin/users", admin});
        endpoints.put("admin/audit", new String[]{"/api/v1/admin/audit", admin});
        endpoints.put("admin/settings", new String[]{"/api/v1/admin/settings", admin});
        endpoints.put("references", new String[]{"/api/v1/references", teacher});

        for (int i = 0; i < REPEATS; i++) {
            for (Map.Entry<String, String[]> e : endpoints.entrySet()) {
                String name = e.getKey();
                String path = e.getValue()[0];
                String token = e.getValue()[1];
                timed(name, () -> get(base, path, token));
            }
        }

        report(base, label, lessons, trainees);
    }

    // ------------------------------------------------------------------ подготовка

    private String firstAssessment(String base, String token) throws Exception {
        for (JsonNode item : json(get(base, "/api/v1/trainee/results", token))) {
            if (item.hasNonNull("assessmentId")) return item.get("assessmentId").asText();
        }
        throw new IllegalStateException("оценка не появилась");
    }

    /** Эталонное заполнение по вводной: улица, дом и тип происшествия. */
    private final Map<String, String> expected = new ConcurrentHashMap<>();

    private Map<String, String> expectedFills(String base, String teacher, List<String> ids) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode s : json(get(base, "/api/v1/teacher/scenarios?source=TICKET", teacher))) {
            String id = s.get("id").asText();
            if (!ids.contains(id)) continue;
            JsonNode a = s.get("expectedAddress");
            JsonNode types = s.get("expectedIncidentTypes");
            if (a == null || a.isNull() || types == null || types.isEmpty()) continue;
            result.put(id, "{\"address\":{\"country\":\"Россия\",\"locality\":" + text(a, "locality")
                    + ",\"street\":" + text(a, "street") + ",\"house\":" + text(a, "house")
                    + "},\"incidentTypeIds\":[\"" + types.get(0).asText() + "\"],"
                    + "\"description\":\"Обстановка уточнена со слов заявителя, угрозы людям нет.\"}");
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "\"\"" : "\"" + v.asText().replace("\"", "") + "\"";
    }

    private void fillAndSubmit(String base, String token, boolean withMistake) throws Exception {
        JsonNode context = json(get(base, "/api/v1/trainee/context", token));
        JsonNode session = context.get("activeSession");
        if (session == null || session.isNull()) return;
        String sessionId = session.get("id").asText();
        for (int card = 0; card < 2; card++) {
            HttpResponse<String> created = post(base, "/api/v1/card-drafts",
                    "{\"sessionId\":\"" + sessionId + "\"}", token, null);
            if (created.statusCode() != 201) break;
            String draftId = json(created).get("id").asText();
            // Верное заполнение берётся из эталона вводной: одна «правильная» улица
            // на все вводные разброса не даёт — она верна в лучшем случае для одной.
            String scenarioId = json(created).path("scenarioId").asText("");
            String good = expected.get(scenarioId);
            String body = (withMistake || good == null)
                    ? "{\"address\":{\"country\":\"Россия\",\"locality\":\"Москва\",\"street\":\"Тверская\","
                            + "\"house\":\"21\"},\"incidentTypeIds\":[\"fire.smoke\"],"
                            + "\"description\":\"Задымление в жилом доме, открытого пламени нет.\"}"
                    : good;
            patch(base, "/api/v1/card-drafts/" + draftId, body, token);
            post(base, "/api/v1/card-drafts/" + draftId + "/save", null, token, UUID.randomUUID().toString());
        }
    }

    private String[] createTrainee(String base, String admin, int index) throws Exception {
        String login = "prof-" + UUID.randomUUID().toString().substring(0, 8);
        post(base, "/api/v1/admin/users", "{\"login\":\"" + login + "\",\"password\":\"secret1\","
                + "\"displayName\":\"Профиль " + index + "\",\"role\":\"TRAINEE\","
                + "\"workstationNumber\":\"" + (index % 100 + 1) + "\"}", admin, null);
        String token = login(base, login, "secret1");
        String id = json(get(base, "/api/v1/auth/me", token)).get("id").asText();
        return new String[]{token, id};
    }

    private List<String> pickScenarios(String base, String teacher, int count) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode s : json(get(base, "/api/v1/teacher/scenarios?source=TICKET", teacher))) {
            if (ids.size() >= count) break;
            ids.add(s.get("id").asText());
        }
        return ids;
    }

    private String startLesson(String base, String teacher, List<String[]> trainees, List<String> scenarioIds)
            throws Exception {
        String ids = String.join(",", scenarioIds.stream().map(id -> "\"" + id + "\"").toList());
        String people = String.join(",", trainees.stream().map(t -> "\"" + t[1] + "\"").toList());
        HttpResponse<String> created = post(base, "/api/v1/teacher/lessons",
                "{\"title\":\"Профиль\",\"kind\":\"TRAINING\",\"mode\":\"CARD_FILL\","
                        + "\"cardSource\":\"GENERATED\",\"scenarioIds\":[" + ids + "],"
                        + "\"traineeIds\":[" + people + "]}", teacher, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String lessonId = json(created).get("id").asText();
        post(base, "/api/v1/teacher/lessons/" + lessonId + "/start", null, teacher, null);
        return lessonId;
    }

    // ------------------------------------------------------------------ замер

    private interface Call {
        HttpResponse<String> run() throws Exception;
    }

    private void timed(String name, Call call) throws Exception {
        long startedAt = System.nanoTime();
        HttpResponse<String> response = call.run();
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        if (response.statusCode() < 400) {
            latencies.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(ms);
        }
    }

    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private void report(String base, String label, int lessons, int trainees) throws Exception {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "%n=== Профиль ручек: %s (занятий %d, обучающихся %d) ===%nадрес: %s%n%n%-32s %6s %7s %7s %7s%n",
                label, lessons, trainees, base, "ручка", "кол-во", "p50", "p95", "макс"));
        List<Map.Entry<String, List<Long>>> ordered = new ArrayList<>(latencies.entrySet());
        ordered.sort((a, b) -> Long.compare(percentile(b.getValue(), 95), percentile(a.getValue(), 95)));
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : ordered) {
            List<Long> values = List.copyOf(entry.getValue());
            long p50 = percentile(values, 50);
            long p95 = percentile(values, 95);
            long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
            out.append(String.format(Locale.ROOT, "%-32s %6d %7d %7d %7d%n",
                    entry.getKey(), values.size(), p50, p95, max));
            rows.add(String.join(";", label, String.valueOf(lessons), String.valueOf(trainees),
                    entry.getKey(), String.valueOf(values.size()),
                    String.valueOf(p50), String.valueOf(p95), String.valueOf(max)));
        }
        System.out.println(out);
        Path file = Path.of("benchmarks", "endpoints-" + label + ".csv");
        Files.createDirectories(file.getParent());
        Files.write(file, ("﻿" + "label;lessons;trainees;endpoint;count;p50_ms;p95_ms;max_ms\n"
                + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("CSV: " + file);
    }

    // ------------------------------------------------------------------ HTTP

    private String login(String base, String username, String password) throws Exception {
        HttpResponse<String> response = post(base, "/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null, null);
        assertThat(response.statusCode()).as("вход %s: %s", username, response.body()).isEqualTo(200);
        return json(response).get("accessToken").asText();
    }

    private HttpResponse<String> get(String base, String path, String token) throws Exception {
        return send(base, "GET", path, null, token, null);
    }

    private HttpResponse<String> post(String base, String path, String body, String token, String key)
            throws Exception {
        return send(base, "POST", path, body, token, key);
    }

    private HttpResponse<String> patch(String base, String path, String body, String token) throws Exception {
        return send(base, "PATCH", path, body, token, null);
    }

    private HttpResponse<String> send(String base, String method, String path, String body,
                                      String token, String key) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("X-Contract-Version", "0.3");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }
}
