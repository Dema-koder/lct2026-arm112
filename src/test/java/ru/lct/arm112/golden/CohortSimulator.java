package ru.lct.arm112.golden;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.service.TrainingEngine;
import ru.lct.arm112.service.analytics.CalibrationService;
import ru.lct.arm112.service.analytics.RaschService;
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

    @Autowired
    RaschService rasch;

    @Autowired
    CalibrationService calibration;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * Политика синтетического преподавателя: во сколько раз он растягивает шкалу ИИ
     * и на сколько сдвигает. Значения выдуманы — проверяется, восстановит ли их калибровка,
     * а не то, насколько они похожи на настоящего преподавателя.
     *
     * <p>Смысл заложенного: по адресу преподаватель строже (сдвиг вниз), по грамотности
     * мягче (сдвиг вверх), по остальным критериям близок к ИИ.
     */
    private static final java.util.Map<String, double[]> TEACHER_POLICY = java.util.Map.of(
            "address", new double[]{0.9, -8},
            "classification", new double[]{1.0, 0},
            "services", new double[]{1.0, 2},
            "timing", new double[]{0.8, 5},
            "language", new double[]{1.1, 6});

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
        // Пул намеренно ограничен: модель Раша требует не меньше 10 наблюдений на сценарий,
        // а преподаватель и в жизни гоняет группу по ограниченному набору вводных.
        int poolLimit = Integer.getInteger("cohort.pool", 10);
        if (pool.size() > poolLimit) pool = pick(pool, poolLimit, new Random(seed));
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

        checkRaschRecovery(name, cardRows, cohort);
        checkCalibrationRecovery(name, teacher, random);

        assertThat(sessionRows).hasSize(traineeCount * sessionCount);
    }

    /**
     * Сверка оценки Раша с истинными параметрами, с которыми генерировалась когорта.
     *
     * <p>На реальных данных истинной сложности не существует, поэтому проверить восстановление
     * можно только на синтетике. Ожидается высокая ранговая корреляция, а не совпадение
     * в логитах: «справился с карточкой» складывается из четырёх независимых испытаний
     * со своими трудностями, поэтому эффективная сложность — монотонная функция заданной,
     * но не равная ей.
     */
    private void checkRaschRecovery(String name, List<String> cardRows, List<Trainee> cohort) throws IOException {
        java.util.Map<String, Double> trueDifficulty = new java.util.LinkedHashMap<>();
        for (String row : cardRows) {
            String[] parts = row.split(";");
            trueDifficulty.put(parts[4], Double.parseDouble(parts[5].replace(',', '.')));
        }

        long startedAt = System.nanoTime();
        RaschService.Estimate estimate = rasch.estimate();
        long spentMs = (System.nanoTime() - startedAt) / 1_000_000;

        List<String> rows = new ArrayList<>();
        List<double[]> pairs = new ArrayList<>();
        for (RaschService.ScenarioDifficulty s : estimate.scenarios()) {
            Double truth = trueDifficulty.get(s.scenarioId());
            if (truth == null) continue;
            pairs.add(new double[]{truth, s.difficulty()});
            rows.add(String.join(";", q(s.scenarioId()), num(truth), num(s.difficulty()),
                    num(s.standardError()), String.valueOf(s.observations()), String.valueOf(s.passed()),
                    String.valueOf(s.suggested())));
        }
        write(Path.of("benchmarks", "cohort-" + name + "-rasch.csv"),
                "scenario_id;true_difficulty;estimated_logit;standard_error;observations;passed;suggested_1_10",
                rows);

        // вторая, независимая проверка: восстанавливается ли способность обучающихся.
        // Истинная берётся усреднённой по занятиям — Раш даёт одно θ на всю историю.
        java.util.Map<String, Double> trueAbility = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> loginById = new java.util.LinkedHashMap<>();
        for (Trainee t : cohort) {
            double mean = 0;
            for (int i = 0; i < 3; i++) mean += t.ability() + t.learningRate() * i;
            trueAbility.put(t.id(), Math.round(mean / 3 * 100) / 100.0);
            loginById.put(t.id(), t.login());
        }
        List<String> abilityRows = new ArrayList<>();
        List<double[]> abilityPairs = new ArrayList<>();
        for (RaschService.TraineeAbility a : estimate.trainees()) {
            Double truth = trueAbility.get(a.traineeId().toString());
            if (truth == null) continue;
            abilityPairs.add(new double[]{truth, a.ability()});
            abilityRows.add(String.join(";", q(loginById.get(a.traineeId().toString())), num(truth),
                    num(a.ability()), num(a.standardError()),
                    String.valueOf(a.observations()), String.valueOf(a.passed())));
        }
        write(Path.of("benchmarks", "cohort-" + name + "-abilities.csv"),
                "trainee;true_ability;estimated_logit;standard_error;observations;passed", abilityRows);
        double rhoAbility = spearman(abilityPairs);

        double rho = spearman(pairs);
        System.out.printf(Locale.ROOT,
                "%n=== Раш: восстановление сложности ===%nСценариев в оценке: %d из %d, итераций %d, сошлось %s, %d мс%n"
                        + "Ранговая корреляция истинной и оценённой сложности: rho = %.3f%n"
                        + "Обучающихся с оценкой способности: %d, rho по способности = %.3f%n",
                estimate.scenarios().size(), trueDifficulty.size(), estimate.iterations(),
                estimate.converged() ? "да" : "нет", spentMs, rho, estimate.trainees().size(), rhoAbility);

        if (pairs.size() >= 5) {
            assertThat(rho).as("ранговая корреляция истинной и оценённой сложности").isGreaterThan(0.5);
        }
        if (abilityPairs.size() >= 5) {
            // Порог ниже, чем по сложности, и это ожидаемо: модель Раша считает способность
            // постоянной, а в когорте она растёт от занятия к занятию. Оценённое θ — среднее
            // по всей истории, поэтому текущую способность оно занижает у тех, кто быстро растёт.
            assertThat(rhoAbility).as("ранговая корреляция истинной и оценённой способности").isGreaterThan(0.5);
        }
    }

    /**
     * Синтетический преподаватель правит часть оценок по известной политике,
     * затем проверяется, восстанавливает ли её калибровка.
     *
     * <p><b>Что именно это проверяет.</b> Только конвейер: доходят ли правки до БД,
     * считаются ли коэффициенты, падает ли отклонение. Сама политика выдумана, поэтому
     * о справедливости оценки отсюда ничего не следует — реальные преподаватели дадут
     * другие коэффициенты, и калибровку придётся переобучить.
     */
    private void checkCalibrationRecovery(String name, String teacherToken, Random random) throws Exception {
        List<String> rows = new ArrayList<>();
        int assessed = 0;
        for (JsonNode lesson : json(get("/api/v1/teacher/lessons", teacherToken))) {
            if (!"COMPLETED".equals(lesson.get("state").asText())) continue;
            JsonNode report = json(get("/api/v1/teacher/lessons/" + lesson.get("id").asText() + "/report", teacherToken));
            for (JsonNode row : report.get("rows")) {
                if (!row.hasNonNull("aiTotal")) continue;
                String sessionId = row.get("sessionId").asText();
                JsonNode detail = json(get("/api/v1/teacher/sessions/" + sessionId, teacherToken));
                JsonNode ai = detail.path("assessment");
                if (ai.isMissingNode() || ai.isNull()) continue;

                List<String> criteria = new ArrayList<>();
                for (JsonNode c : ai.get("aiCriteria")) {
                    if (c.get("score").isNull()) continue;
                    String code = c.get("code").asText();
                    double[] policy = TEACHER_POLICY.get(code);
                    if (policy == null) continue;
                    // преподаватель не линейка: к политике добавляется собственный разброс
                    double score = policy[0] * c.get("score").asDouble() + policy[1] + random.nextGaussian() * 2.5;
                    score = Math.max(0, Math.min(100, Math.round(score * 10) / 10.0));
                    criteria.add("{\"code\":\"" + code + "\",\"score\":" + score + "}");
                    rows.add(String.join(";", q(code), num(c.get("score").asDouble()), num(score)));
                }
                if (criteria.isEmpty()) continue;
                HttpResponse<String> saved = put("/api/v1/teacher/sessions/" + sessionId + "/assessment",
                        "{\"criteria\":[" + String.join(",", criteria) + "]}", teacherToken);
                assertThat(saved.statusCode()).as("оценка преподавателя: " + saved.body()).isEqualTo(200);
                assessed++;
            }
        }
        write(Path.of("benchmarks", "cohort-" + name + "-teacher-pairs.csv"),
                "criterion;ai_score;teacher_score", rows);

        long startedAt = System.nanoTime();
        CalibrationService.Calibration result = calibration.calibrate("CARD_FILL");
        long spentMs = (System.nanoTime() - startedAt) / 1_000_000;

        List<String> calibrationRows = new ArrayList<>();
        System.out.printf(Locale.ROOT, "%n=== Калибровка по преподавателю ===%n"
                + "Оценок с правками: %d, критериев откалибровано: %d, %d мс%n"
                + "%-16s %8s %8s %10s %10s %10s %8s%n",
                assessed, result.criteria().size(), spentMs,
                "критерий", "накл.", "сдвиг", "истин.накл", "истин.сдв", "MAE до→после", "польза");
        for (CalibrationService.CriterionCalibration c : result.criteria()) {
            double[] truth = TEACHER_POLICY.get(c.code());
            System.out.printf(Locale.ROOT, "%-16s %8.2f %8.2f %10.2f %10.2f %5.1f→%-5.1f %7.0f%%%n",
                    c.code(), c.slope(), c.intercept(), truth[0], truth[1],
                    c.maeBefore(), c.maeAfter(), c.improvement());
            calibrationRows.add(String.join(";", q(c.code()), num(c.slope()), num(c.intercept()),
                    num(truth[0]), num(truth[1]), num(c.maeBefore()), num(c.maeAfter()),
                    num(c.improvement()), String.valueOf(c.pairs())));
        }
        result.skipped().forEach(skip -> System.out.println("  пропущено — " + skip));
        write(Path.of("benchmarks", "cohort-" + name + "-calibration.csv"),
                "criterion;slope;intercept;true_slope;true_intercept;mae_before;mae_after;improvement_pct;pairs",
                calibrationRows);

        if (!result.criteria().isEmpty()) {
            // Требование METRICS.md §5.7: калибровка обязана снижать отклонение, иначе она бесполезна
            assertThat(result.criteria()).allSatisfy(c ->
                    assertThat(c.maeAfter()).as("MAE после калибровки, критерий " + c.code())
                            .isLessThan(c.maeBefore()));
        }
    }

    /** Ранговая корреляция Спирмена; связанные ранги усредняются. */
    private static double spearman(List<double[]> pairs) {
        if (pairs.size() < 3) return Double.NaN;
        double[] rx = ranks(pairs.stream().mapToDouble(p -> p[0]).toArray());
        double[] ry = ranks(pairs.stream().mapToDouble(p -> p[1]).toArray());
        double mx = java.util.Arrays.stream(rx).average().orElse(0);
        double my = java.util.Arrays.stream(ry).average().orElse(0);
        double cov = 0, vx = 0, vy = 0;
        for (int i = 0; i < rx.length; i++) {
            cov += (rx[i] - mx) * (ry[i] - my);
            vx += (rx[i] - mx) * (rx[i] - mx);
            vy += (ry[i] - my) * (ry[i] - my);
        }
        return vx == 0 || vy == 0 ? Double.NaN : cov / Math.sqrt(vx * vy);
    }

    private static double[] ranks(double[] values) {
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < values.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(values[a], values[b]));
        double[] result = new double[values.length];
        int i = 0;
        while (i < order.length) {
            int j = i;
            while (j + 1 < order.length && values[order[j + 1]] == values[order[i]]) j++;
            double rank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) result[order[k]] = rank;
            i = j + 1;
        }
        return result;
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

    private HttpResponse<String> put(String path, String body, String token) throws Exception {
        return send("PUT", path, body, token, null);
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
