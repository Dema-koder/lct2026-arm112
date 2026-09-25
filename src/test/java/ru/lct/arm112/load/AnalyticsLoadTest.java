package ru.lct.arm112.load;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Замер экранов аналитики по размеру группы.
 *
 * <p>Отдельно от {@link LoadProfileTest}: тот меряет работу обучающегося на занятии,
 * здесь — то, что происходит <b>после</b> занятия, когда преподаватель открывает разбор.
 * Нагрузка принципиально другая: не много мелких записей, а несколько тяжёлых чтений
 * с агрегацией по всей группе.
 *
 * <p>Проверяются две картины:
 * <ol>
 *   <li><b>последовательно</b> — преподаватель один, открывает экраны по очереди;
 *       это обычный случай, и он показывает чистую стоимость каждой ручки;</li>
 *   <li><b>одновременно</b> — вся группа открывает свой разбор, пока преподаватель
 *       смотрит обзор занятия; так бывает в конце занятия и это худший случай.</li>
 * </ol>
 *
 * <p>Не входит в сборку CI: это измерение, а не проверка.
 *
 * <pre>
 *   mvn test -Dtest=AnalyticsLoadTest -Dload.url=http://localhost:8082 -Danalytics.users=30
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsLoadTest {

    /** Сколько раз дёргается каждая ручка в последовательной части. */
    private static final int REPEATS = 7;

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<Long>> latencies = new ConcurrentHashMap<>();
    private final AtomicInteger errors = new AtomicInteger();

    @Test
    void analyticsScreensByGroupSize() throws Exception {
        String external = System.getProperty("load.url", "");
        // Без внешнего адреса замер пропускается: внутри сборки образа он поднимает
        // тридцать сессий и мешает тестам очереди задач — это измерение, а не проверка.
        Assumptions.assumeTrue(!external.isBlank(), "Замер аналитики пропущен: не задан -Dload.url");
        String base = external;
        int users = Integer.getInteger("analytics.users", 30);

        String admin = login(base, "admin", "admin");
        String teacher = login(base, "teacher", "teacher");

        // --- подготовка вне замера: группа проводит занятие и получает оценки
        long preparedAt = System.nanoTime();
        List<String[]> trainees = new ArrayList<>();
        for (int i = 0; i < users; i++) trainees.add(createTrainee(base, admin, i));
        List<String> scenarioIds = pickScenarios(base, teacher, 2);
        String lessonId = startLesson(base, teacher, trainees, scenarioIds);
        for (String[] trainee : trainees) fillAndSubmit(base, trainee[0]);
        double setupSeconds = (System.nanoTime() - preparedAt) / 1e9;

        // --- 1. преподаватель открывает экраны по очереди
        for (int i = 0; i < REPEATS; i++) {
            timed("GET обзор занятия", () -> get(base, "/api/v1/teacher/lessons/" + lessonId + "/overview", teacher));
            timed("GET профиль обучающегося",
                    () -> get(base, "/api/v1/teacher/trainees/" + trainees.get(0)[1] + "/profile", teacher));
            timed("GET качество сценариев", () -> get(base, "/api/v1/teacher/scenarios/quality", teacher));
            timed("GET калибровка", () -> get(base, "/api/v1/teacher/calibration?mode=CARD_FILL", teacher));
            timed("GET отчёт занятия", () -> get(base, "/api/v1/teacher/lessons/" + lessonId + "/report", teacher));
        }

        // --- 2. вся группа открывает свой разбор, преподаватель смотрит обзор
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users + 1);
        long burstAt = System.nanoTime();
        for (String[] trainee : trainees) {
            pool.submit(() -> {
                try {
                    start.await();
                    timed("GET результаты обучающегося", () -> get(base, "/api/v1/trainee/results", trainee[0]));
                    timed("GET рейтинг обучающегося", () -> get(base, "/api/v1/trainee/rating", trainee[0]));
                } catch (Exception exception) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                timed("GET обзор занятия под нагрузкой",
                        () -> get(base, "/api/v1/teacher/lessons/" + lessonId + "/overview", teacher));
            } catch (Exception exception) {
                errors.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        boolean finished = done.await(5, TimeUnit.MINUTES);
        double burstSeconds = (System.nanoTime() - burstAt) / 1e9;
        pool.shutdown();

        report(base, users, setupSeconds, burstSeconds, finished);

        assertThat(finished).as("группа не открыла разбор за 5 минут").isTrue();
        assertThat(errors.get()).as("ошибок при чтении аналитики").isZero();
    }

    // ------------------------------------------------------------------ подготовка

    private void fillAndSubmit(String base, String token) throws Exception {
        JsonNode context = json(get(base, "/api/v1/trainee/context", token));
        JsonNode session = context.get("activeSession");
        if (session == null || session.isNull()) return;
        String sessionId = session.get("id").asText();
        for (int card = 0; card < 2; card++) {
            HttpResponse<String> created = post(base, "/api/v1/card-drafts",
                    "{\"sessionId\":\"" + sessionId + "\"}", token, null);
            if (created.statusCode() != 201) break;
            String draftId = json(created).get("id").asText();
            // адрес намеренно с ошибкой: без замечаний агрегатам нечего показывать
            patch(base, "/api/v1/card-drafts/" + draftId,
                    "{\"address\":{\"country\":\"Россия\",\"locality\":\"Москва\",\"street\":\"Тверская\","
                            + "\"house\":\"21\"},\"incidentTypeIds\":[\"fire.smoke\"],"
                            + "\"description\":\"Задымление в жилом доме, открытого пламени нет.\"}", token);
            post(base, "/api/v1/card-drafts/" + draftId + "/save", null, token, UUID.randomUUID().toString());
        }
    }

    private String[] createTrainee(String base, String admin, int index) throws Exception {
        String login = "anl-" + UUID.randomUUID().toString().substring(0, 8);
        post(base, "/api/v1/admin/users", "{\"login\":\"" + login + "\",\"password\":\"secret1\","
                + "\"displayName\":\"Аналитика " + index + "\",\"role\":\"TRAINEE\","
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

    /** Одно занятие на всю группу: агрегаты считаются по нему, значит группа должна быть в нём целиком. */
    private String startLesson(String base, String teacher, List<String[]> trainees, List<String> scenarioIds)
            throws Exception {
        String ids = String.join(",", scenarioIds.stream().map(id -> "\"" + id + "\"").toList());
        String people = String.join(",", trainees.stream().map(t -> "\"" + t[1] + "\"").toList());
        HttpResponse<String> created = post(base, "/api/v1/teacher/lessons",
                "{\"title\":\"Замер аналитики\",\"kind\":\"TRAINING\",\"mode\":\"CARD_FILL\","
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

    private HttpResponse<String> timed(String name, Call call) throws Exception {
        long startedAt = System.nanoTime();
        HttpResponse<String> response = call.run();
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        latencies.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(ms);
        if (response.statusCode() >= 500) errors.incrementAndGet();
        return response;
    }

    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private void report(String base, int users, double setupSeconds, double burstSeconds, boolean finished)
            throws Exception {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "%n=== Экраны аналитики: группа %d человек ===%n"
                        + "адрес: %s%nзанятие проведено за %.1f с (вне замера)%n"
                        + "разбор всей группой разом: %.1f с, ошибок %d, завершились %s%n%n"
                        + "%-36s %6s %7s %7s %7s %7s%n",
                users, base, setupSeconds, burstSeconds, errors.get(), finished ? "все" : "НЕ ВСЕ",
                "запрос", "кол-во", "p50", "p95", "p99", "макс"));

        List<String> rows = new ArrayList<>();
        List<Map.Entry<String, List<Long>>> ordered = new ArrayList<>(latencies.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, List<Long>> entry : ordered) {
            List<Long> values = List.copyOf(entry.getValue());
            long p50 = percentile(values, 50);
            long p95 = percentile(values, 95);
            long p99 = percentile(values, 99);
            long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
            out.append(String.format(Locale.ROOT, "%-36s %6d %7d %7d %7d %7d%n",
                    entry.getKey(), values.size(), p50, p95, p99, max));
            rows.add(String.join(";", String.valueOf(users), entry.getKey(), String.valueOf(values.size()),
                    String.valueOf(p50), String.valueOf(p95), String.valueOf(p99), String.valueOf(max)));
        }
        System.out.println(out);

        Path file = Path.of("benchmarks", "analytics-" + users + "-users.csv");
        Files.createDirectories(file.getParent());
        Files.write(file, ("﻿" + "users;request;count;p50_ms;p95_ms;p99_ms;max_ms\n"
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
