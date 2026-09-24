package ru.lct.arm112.load;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Нагрузочный профиль занятия (задача E2 плана).
 *
 * <p><b>Результат.</b> Сто обучающихся с разбросом начала работы: сохранение карточки
 * p95 = 313 мс, всё остальное не выше 8 мс, ошибок нет — норматив ТЗ выдержан.
 * Тридцать обучающихся, завершающих сессию одновременно: p95 = 2.4 с, норматив
 * превышен. Подробности и выводы — в docs/assessment/LOAD_PROFILE.md.
 *
 * <p>Требование ТЗ: отклик интерфейса не хуже 2 секунд при нагрузке до 100 пользователей.
 * Заказчик уточнил, что жёстко проверять не будут, но «отваливаться на десяти
 * одновременных пользователях нельзя — в реальном классе занимаются 20–30 человек».
 *
 * <h2>Почему профиль занятия, а не синтетика</h2>
 * Гонять один эндпоинт в цикле бессмысленно: узкое место проявляется на сочетании
 * запросов. Здесь воспроизводится то, что делает обучающийся на самом деле —
 * принял вызов, несколько раз поправил поля, сохранил, — потому что именно эта
 * последовательность держит состояние сессии в памяти и пишет снимок в базу.
 *
 * <h2>Что меряется</h2>
 * Задержки по видам запросов раздельно. Средняя по всем запросам скрыла бы
 * проблему: частые дешёвые правки размывают редкое дорогое сохранение.
 *
 * <p>По умолчанию бьёт по поднятому в тесте приложению на H2. Для замера боевой
 * конфигурации указывается внешний адрес:
 * <pre>
 *   mvn test -Dtest=LoadProfileTest -Dload.url=http://localhost:8080 -Dload.users=100
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoadProfileTest {

    /** Требование ТЗ по отклику интерфейса. */
    private static final long RESPONSE_LIMIT_MS = 2000;
    /** Сколько правок карточки делает обучающийся, прежде чем сохранить. */
    private static final int PATCHES_PER_CARD = 4;

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    /** Задержки одного вида запросов; хранится всё, перцентили считаются в конце. */
    private final Map<String, List<Long>> latencies = new ConcurrentHashMap<>();
    private final AtomicInteger errors = new AtomicInteger();

    @Test
    void classroomUnderLoad() throws Exception {
        String external = System.getProperty("load.url", "");
        String base = external.isBlank() ? "http://localhost:" + port : external;
        int users = Integer.getInteger("load.users", 30);
        int cardsEach = Integer.getInteger("load.cards", 2);
        // По умолчанию — профиль занятия: обучающиеся работают вразнобой в пределах
        // получаса. Худший мыслимый случай (все завершают сессию в одну миллисекунду)
        // задаётся явно через -Dload.stagger=0 и норматив не выдерживает; см. документ.
        int stagger = Integer.getInteger("load.stagger", 30_000);

        String admin = login(base, "admin", "admin");
        String teacher = login(base, "teacher", "teacher");

        // --- подготовка вне замера: пользователи и занятия создаются заранее,
        //     иначе в цифры попадёт то, чего на занятии не происходит
        List<String[]> trainees = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            trainees.add(createTrainee(base, admin, i));
        }
        List<String> scenarioIds = pickScenarios(base, teacher, cardsEach);
        for (String[] trainee : trainees) {
            startLesson(base, teacher, trainee[1], scenarioIds);
        }

        // --- замер: все обучающиеся работают одновременно
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        long startedAt = System.nanoTime();
        for (String[] trainee : trainees) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (stagger > 0) {
                        // Разброс начала работы. В настоящем классе занятия не завершаются
                        // в одну миллисекунду; без разброса замеряется худший мыслимый
                        // случай, а не то, что бывает на занятии.
                        Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(stagger));
                    }
                    work(base, trainee[0], cardsEach);
                } catch (Exception exception) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(10, TimeUnit.MINUTES);
        double wallSeconds = (System.nanoTime() - startedAt) / 1e9;
        pool.shutdown();

        report(base, users, cardsEach, stagger, wallSeconds, finished);

        assertThat(finished).as("обучающиеся не закончили за 10 минут").isTrue();
        assertThat(errors.get()).as("ошибок при работе").isZero();
        for (Map.Entry<String, List<Long>> entry : latencies.entrySet()) {
            assertThat(percentile(entry.getValue(), 95))
                    .as("отклик p95 по запросу «%s» — требование ТЗ не хуже %d мс",
                            entry.getKey(), RESPONSE_LIMIT_MS)
                    .isLessThanOrEqualTo(RESPONSE_LIMIT_MS);
        }
    }

    /** Что делает один обучающийся: принял вызов, поправил поля, сохранил. */
    private void work(String base, String token, int cards) throws Exception {
        JsonNode context = json(timed("GET context", () -> get(base, "/api/v1/trainee/context", token)));
        String sessionId = context.get("activeSession").get("id").asText();

        for (int card = 0; card < cards; card++) {
            HttpResponse<String> created = timed("POST card-drafts",
                    () -> post(base, "/api/v1/card-drafts", "{\"sessionId\":\"" + sessionId + "\"}", token, null));
            if (created.statusCode() != 201) return; // вводные кончились
            String draftId = json(created).get("id").asText();

            for (int i = 0; i < PATCHES_PER_CARD; i++) {
                String body = "{\"description\":\"Уточнение обстановки, правка номер " + i + "\"}";
                timed("PATCH card-drafts", () -> patch(base, "/api/v1/card-drafts/" + draftId, body, token));
            }
            timed("PATCH card-drafts", () -> patch(base, "/api/v1/card-drafts/" + draftId,
                    "{\"address\":{\"country\":\"Россия\",\"locality\":\"Москва\",\"street\":\"Берзарина\","
                            + "\"house\":\"21\"},\"incidentTypeIds\":[\"fire.smoke\"]}", token));
            timed("POST save", () -> post(base, "/api/v1/card-drafts/" + draftId + "/save",
                    null, token, UUID.randomUUID().toString()));
        }
    }

    // ------------------------------------------------------------------ замер

    private interface Call {
        HttpResponse<String> run() throws Exception;
    }

    private HttpResponse<String> timed(String name, Call call) throws Exception {
        long startedAt = System.nanoTime();
        HttpResponse<String> response = call.run();
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        latencies.computeIfAbsent(name, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(ms);
        if (response.statusCode() >= 500) errors.incrementAndGet();
        return response;
    }

    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private void report(String base, int users, int cards, int stagger, double wallSeconds, boolean finished)
            throws Exception {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "%n=== Нагрузочный профиль занятия ===%n"
                        + "адрес: %s%nобучающихся одновременно: %d, карточек на каждого: %d%n"
                        + "разброс начала работы: %s%n"
                        + "всё занятие заняло %.1f с, ошибок %d, завершились %s%n%n"
                        + "%-22s %7s %7s %7s %7s %7s%n",
                base, users, cards,
                stagger == 0 ? "нет — худший случай, все разом" : stagger / 1000 + " с",
                wallSeconds, errors.get(), finished ? "все" : "НЕ ВСЕ",
                "запрос", "кол-во", "p50", "p95", "p99", "макс"));

        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : latencies.entrySet()) {
            List<Long> values = entry.getValue();
            long p50 = percentile(values, 50), p95 = percentile(values, 95);
            long p99 = percentile(values, 99), max = percentile(values, 100);
            out.append(String.format(Locale.ROOT, "%-22s %7d %7d %7d %7d %7d%s%n",
                    entry.getKey(), values.size(), p50, p95, p99, max,
                    p95 > RESPONSE_LIMIT_MS ? "  ПРЕВЫШЕН НОРМАТИВ" : ""));
            rows.add(String.join(";", entry.getKey(), String.valueOf(values.size()),
                    String.valueOf(p50), String.valueOf(p95), String.valueOf(p99), String.valueOf(max)));
        }
        System.out.println(out);

        Path file = Path.of("benchmarks",
                "load-" + users + "-users" + (stagger > 0 ? "-spread" : "") + ".csv");
        Files.createDirectories(file.getParent());
        Files.write(file, ("﻿" + "request;count;p50_ms;p95_ms;p99_ms;max_ms\n"
                + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("CSV: " + file);
    }

    // ------------------------------------------------------------------ подготовка

    private String[] createTrainee(String base, String admin, int index) throws Exception {
        String login = "load-" + UUID.randomUUID().toString().substring(0, 8);
        post(base, "/api/v1/admin/users", "{\"login\":\"" + login + "\",\"password\":\"secret1\","
                + "\"displayName\":\"Нагрузка " + index + "\",\"role\":\"TRAINEE\","
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

    private void startLesson(String base, String teacher, String traineeId, List<String> scenarioIds)
            throws Exception {
        String ids = String.join(",", scenarioIds.stream().map(id -> "\"" + id + "\"").toList());
        HttpResponse<String> created = post(base, "/api/v1/teacher/lessons",
                "{\"title\":\"Нагрузка\",\"kind\":\"TRAINING\",\"mode\":\"CARD_FILL\","
                        + "\"cardSource\":\"GENERATED\",\"scenarioIds\":[" + ids + "],"
                        + "\"traineeIds\":[\"" + traineeId + "\"]}", teacher, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        post(base, "/api/v1/teacher/lessons/" + json(created).get("id").asText() + "/start",
                null, teacher, null);
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

    private HttpResponse<String> patch(String base, String path, String body, String token)
            throws Exception {
        return send(base, "PATCH", path, body, token, null);
    }

    private HttpResponse<String> send(String base, String method, String path, String body,
                                      String token, String key) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
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
