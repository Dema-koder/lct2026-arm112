package ru.lct.arm112.golden;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.service.TrainingEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
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
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Синтетическая когорта: N обучающихся с заданной способностью проходят K занятий.
 * Нужна там, где задача плана ждёт накопления данных, а живой группы пока нет
 * (сложность по Рашу, калибровка, объём данных для экранов, нагрузка).
 *
 * <h2>Что здесь синтетическое, а что настоящее</h2>
 * <ul>
 *   <li><b>Синтетическое</b> — поведение: с какой вероятностью обучающийся ошибётся и какой
 *       именно ошибкой. Задаётся моделью ниже и целиком определяется её допущениями.</li>
 *   <li><b>Настоящее</b> — оценка: баллы считает боевой оценщик через боевой API,
 *       замечания ложатся в ту же таблицу, что и на живом занятии.</li>
 * </ul>
 *
 * <p><b>Отсюда главное ограничение.</b> Распределение ошибок в выгрузке — следствие того,
 * что заложено в {@link Behaviour}, а не наблюдение о людях. Если синтетическая когорта
 * чаще всего ошибается в адресе, это значит ровно то, что так написано в модели.
 * Выводы вида «группа слаба в адресах» на этих данных делать нельзя; проверять на них
 * работу алгоритмов и экранов — можно и нужно.
 *
 * <h2>Модель способности</h2>
 * Однопараметрическая логистическая модель, как в Раше: вероятность справиться с критерием
 * равна {@code 1 / (1 + exp(-(θ - b)))}, где θ — способность обучающегося, b — трудность.
 * Истинные θ и b сохраняются в выгрузку: по ним проверяется, восстанавливает ли их
 * будущая реализация Раша (задача B4 плана).
 *
 * <p>Запуск:
 * <pre>
 *   mvn test -Dtest=CohortSimulator -Dcohort.name=c1 -Dcohort.trainees=24 -Dcohort.sessions=3 -Dcohort.cards=3
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CohortSimulator {

    private static final String CONTRACT = "0.3";

    /** Трудность критериев: подобрана так, чтобы средний обучающийся ошибался в адресе чаще, чем в типе. */
    private static final double B_ADDRESS = 0.4, B_TYPE = -0.2, B_LANGUAGE = -0.6, B_TIMING = 0.1;

    /** Способность когорты и темп обучения. Меняются через -Dcohort.ability / -Dcohort.learning. */
    private static final double ABILITY_MEAN = Double.parseDouble(System.getProperty("cohort.ability", "1.1"));
    private static final double ABILITY_SPREAD = Double.parseDouble(System.getProperty("cohort.spread", "0.9"));
    private static final double LEARNING_MEAN = Double.parseDouble(System.getProperty("cohort.learning", "0.5"));

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AssessmentRepository assessments;

    @Autowired
    TrainingEngine trainingEngine;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Способность, темп обучения и «почерк» одного синтетического обучающегося. */
    private record Trainee(String login, String token, String id, double ability, double learningRate) {}

    /** Одна сгенерированная попытка: что обучающийся сделает с карточкой. */
    private record Behaviour(boolean addressOk, boolean typeOk, boolean languageOk, boolean inTime,
                             String addressError) {}

    @Test
    void simulateCohort() throws Exception {
        String name = System.getProperty("cohort.name", "c1");
        int traineeCount = Integer.getInteger("cohort.trainees", 24);
        int sessionCount = Integer.getInteger("cohort.sessions", 3);
        int cardsPerSession = Integer.getInteger("cohort.cards", 3);
        long seed = Long.getLong("cohort.seed", 20260923L);
        Random random = new Random(seed);

        String admin = login("admin", "admin");
        String teacher = login("teacher", "teacher");

        List<JsonNode> pool = scenarioPool(teacher);
        assertThat(pool.size()).as("сценариев с улицей, домом и типом").isGreaterThanOrEqualTo(cardsPerSession);

        List<Trainee> cohort = new ArrayList<>();
        for (int i = 0; i < traineeCount; i++) {
            cohort.add(createTrainee(admin, i, random));
        }

        List<String> sessionRows = new ArrayList<>();
        List<String> cardRows = new ArrayList<>();
        long startedAt = System.nanoTime();

        for (int session = 1; session <= sessionCount; session++) {
            for (Trainee trainee : cohort) {
                // способность растёт от занятия к занятию — отсюда берётся кривая обучения
                double ability = trainee.ability() + trainee.learningRate() * (session - 1);
                List<JsonNode> scenarios = pick(pool, cardsPerSession, random);
                runSession(teacher, trainee, ability, session, scenarios, random, sessionRows, cardRows);
            }
        }
        long spentMs = (System.nanoTime() - startedAt) / 1_000_000;

        write(Path.of("benchmarks", "cohort-" + name + "-sessions.csv"),
                "cohort;session_no;trainee;true_ability;mode;total;address;classification;services;timing;language;"
                        + "syntax_errors;issues;critical_issues;assess_ms",
                sessionRows);
        write(Path.of("benchmarks", "cohort-" + name + "-cards.csv"),
                "cohort;session_no;trainee;true_ability;scenario_id;true_difficulty;"
                        + "planned_address_ok;planned_type_ok;planned_language_ok;planned_in_time;address_error",
                cardRows);

        System.out.printf(Locale.ROOT,
                "%n=== Когорта %s: %d обучающихся × %d занятий × %d карточек = %d сессий, %d карточек ===%n"
                        + "Сгенерировано за %d мс (%.0f мс на сессию). Выгрузка: benchmarks/cohort-%s-*.csv%n"
                        + "ВНИМАНИЕ: распределение ошибок задано моделью поведения, а не наблюдением за людьми.%n",
                name, traineeCount, sessionCount, cardsPerSession,
                traineeCount * sessionCount, traineeCount * sessionCount * cardsPerSession,
                spentMs, (double) spentMs / (traineeCount * sessionCount), name);

        assertThat(sessionRows).hasSize(traineeCount * sessionCount);
    }

    // ------------------------------------------------------------------ одно занятие

    private void runSession(String teacher, Trainee trainee, double ability, int sessionNo,
                            List<JsonNode> scenarios, Random random,
                            List<String> sessionRows, List<String> cardRows) throws Exception {
        String ids = String.join(",", scenarios.stream().map(s -> "\"" + s.get("id").asText() + "\"").toList());
        HttpResponse<String> created = post("/api/v1/teacher/lessons",
                "{\"title\":\"Когорта, занятие " + sessionNo + "\",\"kind\":\"TRAINING\",\"mode\":\"CARD_FILL\""
                        + ",\"cardSource\":\"GENERATED\",\"scenarioIds\":[" + ids + "],\"traineeIds\":[\""
                        + trainee.id() + "\"]}", teacher, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String lessonId = json(created).get("id").asText();
        post("/api/v1/teacher/lessons/" + lessonId + "/start", null, teacher, null);
        String sessionId = json(get("/api/v1/trainee/context", trainee.token()))
                .get("activeSession").get("id").asText();

        long assessStart = 0;
        for (JsonNode scenario : scenarios) {
            HttpResponse<String> draft = post("/api/v1/card-drafts",
                    "{\"sessionId\":\"" + sessionId + "\"}", trainee.token(), null);
            if (draft.statusCode() != 201) break; // вводные кончились раньше — оценка уже посчитана
            JsonNode node = json(draft);
            String draftId = node.get("id").asText();
            // сценарий вводной определяется очередью занятия, а не порядком в списке
            JsonNode actual = byId(scenarios, node.get("scenarioId").asText(), scenario);

            double difficulty = difficultyOf(actual);
            Behaviour behaviour = plan(ability, difficulty, random);
            cardRows.add(String.join(";", q(trainee.login()), String.valueOf(sessionNo), q(trainee.login()),
                    num(ability), q(actual.get("id").asText()), num(difficulty),
                    String.valueOf(behaviour.addressOk()), String.valueOf(behaviour.typeOk()),
                    String.valueOf(behaviour.languageOk()), String.valueOf(behaviour.inTime()),
                    q(behaviour.addressError())));

            if (!behaviour.inTime()) {
                // «не уложился» реализуется сдвигом начала черновика: ждать три минуты в тесте нельзя
                trainingEngine.forceDraftStartedAt(UUID.fromString(draftId),
                        java.time.Instant.now().minusSeconds(190 + random.nextInt(170)));
            }
            patch("/api/v1/card-drafts/" + draftId, fillBody(actual, behaviour), trainee.token());
            assessStart = System.nanoTime();
            post("/api/v1/card-drafts/" + draftId + "/save", null, trainee.token(), UUID.randomUUID().toString());
        }
        long assessMs = assessStart == 0 ? 0 : (System.nanoTime() - assessStart) / 1_000_000;

        JsonNode assessment = assessmentOf(trainee.token(), sessionId);
        sessionRows.add(String.join(";", q("cohort"), String.valueOf(sessionNo), q(trainee.login()), num(ability),
                q("CARD_FILL"), num(assessment.get("totalScore").asDouble()),
                score(assessment, "addressScore"), score(assessment, "classificationScore"),
                score(assessment, "servicesScore"), score(assessment, "timingScore"),
                score(assessment, "languageScore"),
                String.valueOf(assessment.path("syntaxErrors").asInt(0)),
                String.valueOf(assessment.get("issues").size()),
                String.valueOf(critical(assessment)), String.valueOf(assessMs)));
    }

    /** Разыгрывает по логистической модели, что обучающийся сделает верно, а где ошибётся. */
    private Behaviour plan(double ability, double difficulty, Random random) {
        boolean addressOk = roll(ability, difficulty + B_ADDRESS, random);
        String addressError = null;
        if (!addressOk) {
            // распределение ошибок адреса: чаще опечатка, реже пропуск дома и совсем другая улица
            double kind = random.nextDouble();
            addressError = kind < 0.55 ? "TYPO" : kind < 0.85 ? "NO_HOUSE" : "WRONG_STREET";
        }
        return new Behaviour(addressOk,
                roll(ability, difficulty + B_TYPE, random),
                roll(ability, difficulty + B_LANGUAGE, random),
                roll(ability, difficulty + B_TIMING, random),
                addressError);
    }

    private static boolean roll(double ability, double difficulty, Random random) {
        return random.nextDouble() < 1.0 / (1.0 + Math.exp(-(ability - difficulty)));
    }

    /** Тело правки черновика: эталон сценария, испорченный ровно там, где модель предписала ошибку. */
    private String fillBody(JsonNode scenario, Behaviour behaviour) {
        JsonNode expected = scenario.get("expectedAddress");
        String street = expected.path("street").asText("");
        String house = expected.path("house").asText("");
        if (!behaviour.addressOk()) {
            switch (behaviour.addressError()) {
                case "TYPO" -> street = typo(street);
                case "WRONG_STREET" -> street = "Тверская";
                case "NO_HOUSE" -> house = "";
                default -> { }
            }
        }
        String types = behaviour.typeOk()
                ? scenario.get("expectedIncidentTypes").toString()
                : "[\"person.missing\"]";
        String description = behaviour.languageOk()
                ? "Происшествие подтверждено заявителем, уточнён адрес и характер вызова."
                : "происшествие подтверждено заявителем,, уточнён адрес и характер вызова";

        StringBuilder address = new StringBuilder("{\"country\":\"Россия\",\"locality\":")
                .append(quote(expected.path("locality").asText("Москва")))
                .append(",\"street\":").append(quote(street));
        if (!house.isEmpty()) address.append(",\"house\":").append(quote(house));
        if (expected.hasNonNull("building")) address.append(",\"building\":").append(quote(expected.get("building").asText()));
        address.append('}');

        return "{\"address\":" + address + ",\"incidentTypeIds\":" + types
                + ",\"description\":" + quote(description) + "}";
    }

    /** Опечатка на одну букву — тот самый случай «Дубнинская / Дубининская». */
    private static String typo(String street) {
        if (street.length() < 4) return street + "а";
        int at = street.length() / 2;
        char replacement = street.charAt(at) == 'о' ? 'а' : 'о';
        return street.substring(0, at) + replacement + street.substring(at + 1);
    }

    /**
     * Истинная трудность сценария: складывается из числа ожидаемых служб и длины названия улицы.
     * Величина условная — важно, что она известна и сохранена, чтобы проверить восстановление Рашем.
     */
    private static double difficultyOf(JsonNode scenario) {
        int services = scenario.get("expectedServices").size();
        int streetLength = scenario.path("expectedAddress").path("street").asText("").length();
        return Math.round((services / 12.0 - 0.5 + streetLength / 40.0) * 100) / 100.0;
    }

    // ------------------------------------------------------------------ вспомогательное

    private Trainee createTrainee(String admin, int index, Random random) throws Exception {
        String login = "sim-" + UUID.randomUUID().toString().substring(0, 8);
        // Параметры подобраны так, чтобы медианный обучающийся начинал около 70 баллов и доходил
        // до ~85 к третьему занятию: это правдоподобная траектория обучения, а не замер по людям.
        double ability = Math.round((random.nextGaussian() * ABILITY_SPREAD + ABILITY_MEAN) * 100) / 100.0;
        double learningRate = Math.round(Math.max(0, random.nextGaussian() * 0.15 + LEARNING_MEAN) * 100) / 100.0;
        HttpResponse<String> created = post("/api/v1/admin/users",
                "{\"login\":\"" + login + "\",\"password\":\"secret1\",\"displayName\":\"Симуляция " + (index + 1)
                        + "\",\"role\":\"TRAINEE\",\"workstationNumber\":\"" + (index % 30 + 1) + "\"}", admin, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String token = login(login, "secret1");
        String id = json(get("/api/v1/auth/me", token)).get("id").asText();
        return new Trainee(login, token, id, ability, learningRate);
    }

    /** Сценарии с улицей, домом и типом — только на них ошибка адреса и типа осмысленна. */
    private List<JsonNode> scenarioPool(String teacher) throws Exception {
        List<JsonNode> pool = new ArrayList<>();
        for (JsonNode s : json(get("/api/v1/teacher/scenarios?source=TICKET", teacher))) {
            JsonNode address = s.path("expectedAddress");
            if (address.isMissingNode() || address.isNull()) continue;
            if (address.path("street").asText("").isBlank()) continue;
            if (address.path("house").asText("").isBlank()) continue;
            if (s.path("expectedIncidentTypes").isEmpty()) continue;
            pool.add(s);
        }
        return pool;
    }

    private static List<JsonNode> pick(List<JsonNode> pool, int count, Random random) {
        List<JsonNode> copy = new ArrayList<>(pool);
        java.util.Collections.shuffle(copy, random);
        return copy.subList(0, Math.min(count, copy.size()));
    }

    private static JsonNode byId(List<JsonNode> scenarios, String id, JsonNode fallback) {
        return scenarios.stream().filter(s -> s.get("id").asText().equals(id)).findFirst().orElse(fallback);
    }

    private JsonNode assessmentOf(String token, String sessionId) throws Exception {
        for (int i = 0; i < 100; i++) {
            for (JsonNode item : json(get("/api/v1/trainee/results", token))) {
                if (item.get("sessionId").asText().equals(sessionId) && item.hasNonNull("assessmentId")) {
                    return json(get("/api/v1/assessments/" + item.get("assessmentId").asText(), token));
                }
            }
            Thread.sleep(30);
        }
        throw new IllegalStateException("оценка по сессии " + sessionId + " не появилась");
    }

    private static long critical(JsonNode assessment) {
        long count = 0;
        for (JsonNode issue : assessment.get("issues")) {
            if ("CRITICAL".equals(issue.path("severity").asText())) count++;
        }
        return count;
    }

    private static String score(JsonNode assessment, String field) {
        JsonNode node = assessment.get(field);
        return node == null || node.isNull() ? "" : num(node.asDouble());
    }

    private static void write(Path path, String header, List<String> rows) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, ("﻿" + header + "\n" + String.join("\n", rows) + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replace('.', ',');
    }

    private static String q(String value) {
        return value == null ? "" : value.replace(';', ',');
    }

    private String quote(String value) {
        return objectMapper.writeValueAsString(value);
    }

    // ------------------------------------------------------------------ HTTP

    private String login(String username, String password) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null, null);
        assertThat(response.statusCode()).as("login " + username + ": " + response.body()).isEqualTo(200);
        return json(response).get("accessToken").asText();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send("GET", path, null, token, null);
    }

    private HttpResponse<String> post(String path, String body, String token, String key) throws Exception {
        return send("POST", path, body, token, key);
    }

    private HttpResponse<String> patch(String path, String body, String token) throws Exception {
        return send("PATCH", path, body, token, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String token, String key)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Contract-Version", CONTRACT);
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
