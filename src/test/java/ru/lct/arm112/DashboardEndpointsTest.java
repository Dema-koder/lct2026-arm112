package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозная проверка экранных эндпоинтов: занятие проводится целиком, затем
 * дёргаются все четыре ручки аналитики.
 *
 * <p>Проверяется не только код ответа, но и <b>осмысленность</b>: экран, получивший
 * пустой список там, где должны быть данные, выглядит рабочим и вводит в заблуждение
 * сильнее, чем ошибка.
 */
class DashboardEndpointsTest extends ApiTestSupport {

    @Test
    void analyticsEndpointsReturnUsableData() throws Exception {
        String admin = login("admin", "admin");
        String teacher = login("teacher", "teacher");

        // --- проводим занятие на трёх обучающихся, чтобы агрегатам было что считать
        List<String[]> trainees = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String login = createTrainee(admin, String.valueOf(70 + i));
            String token = login(login, "secret1");
            trainees.add(new String[]{token, userId(token)});
        }
        String ids = String.join(",", trainees.stream().map(t -> "\"" + t[1] + "\"").toList());
        JsonNode lesson = startLesson(teacher, "TRAINING", "CARD_FILL",
                "\"ticket-02-1\",\"ticket-04-1\"", ids);
        String lessonId = lesson.get("id").asText();

        for (String[] trainee : trainees) {
            fillTwoCards(trainee[0]);
        }

        // --- 1. обзор занятия
        JsonNode overview = json(get("/api/v1/teacher/lessons/" + lessonId + "/overview", teacher));
        assertThat(overview.get("trainees").asInt()).isEqualTo(3);
        assertThat(overview.get("medianTotal").isNull()).as("нет медианы итогов").isFalse();
        assertThat(overview.get("criteria").size()).as("критерии режима").isEqualTo(5);
        // сортировка по потере в баллах — на ней держится порядок разбора на занятии
        double first = overview.get("criteria").get(0).get("lostPoints").asDouble();
        double last = overview.get("criteria").get(4).get("lostPoints").asDouble();
        assertThat(first).as("критерии не отсортированы по потере").isGreaterThanOrEqualTo(last);
        assertThat(overview.get("topIssues").size()).as("типовые ошибки занятия").isGreaterThan(0);
        assertThat(overview.get("byScenario").size()).as("разрез по сценариям").isGreaterThan(0);

        // --- 2. профиль обучающегося
        String traineeId = trainees.get(0)[1];
        JsonNode profile = json(get("/api/v1/teacher/trainees/" + traineeId + "/profile", teacher));
        assertThat(profile.get("trainee").get("id").asText()).isEqualTo(traineeId);
        assertThat(profile.get("criteria").size()).isEqualTo(5);
        assertThat(profile.get("progress").size()).as("кривая обучения").isGreaterThan(0);
        assertThat(profile.get("coverage").size()).as("покрытие категорий").isGreaterThan(0);

        // --- 3. качество библиотеки: без накопленных наблюдений список честно пуст
        HttpResponse<String> quality = get("/api/v1/teacher/scenarios/quality", teacher);
        assertThat(quality.statusCode()).isEqualTo(200);

        // --- 4. калибровка: преподаватель ещё не правил, значит калибровать нечего
        JsonNode calibration = json(get("/api/v1/teacher/calibration?mode=CARD_FILL", teacher));
        assertThat(calibration.get("mode").asText()).isEqualTo("CARD_FILL");
        assertThat(calibration.get("skipped").size())
                .as("без правок преподавателя каждый критерий должен быть пропущен с объяснением")
                .isGreaterThan(0);

        // --- 5. разбор для обучающегося
        String assessmentId = assessmentOf(trainees.get(0)[0]);
        JsonNode debrief = json(get("/api/v1/assessments/" + assessmentId + "/debrief", trainees.get(0)[0]));
        // без модели разбор не подменяется текстом по правилам: UNAVAILABLE с пояснением,
        // а рекомендации по правилам уже лежат в самой оценке
        assertThat(debrief.get("state").asText()).isIn("READY", "PENDING", "UNAVAILABLE");
        if (debrief.get("state").asText().equals("UNAVAILABLE")) {
            assertThat(debrief.get("text").asText()).as("пояснение, почему разбора нет").isNotBlank();
        }

        System.out.printf(Locale.ROOT,
                "%n=== Экранные эндпоинты ===%nобзор занятия: %d обучающихся, медиана %.1f, "
                        + "критериев %d, типовых ошибок %d%nпрофиль: точек прогресса %d, категорий %d%n"
                        + "качество библиотеки: %d сценариев%nкалибровка: пропущено %d критериев%n"
                        + "разбор: %s%n",
                overview.get("trainees").asInt(), overview.get("medianTotal").asDouble(),
                overview.get("criteria").size(), overview.get("topIssues").size(),
                profile.get("progress").size(), profile.get("coverage").size(),
                json(quality).size(), calibration.get("skipped").size(),
                debrief.get("state").asText());
    }

    /** Чужого обучающегося преподаватель видеть не должен. */
    @Test
    void foreignTraineeProfileIsForbidden() throws Exception {
        String admin = login("admin", "admin");
        String teacher = login("teacher", "teacher");
        String outsider = login(createTrainee(admin, "99"), "secret1");

        HttpResponse<String> response = get(
                "/api/v1/teacher/trainees/" + userId(outsider) + "/profile", teacher);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
    }

    private void fillTwoCards(String token) throws Exception {
        String sessionId = json(get("/api/v1/trainee/context", token))
                .get("activeSession").get("id").asText();
        for (int card = 0; card < 2; card++) {
            HttpResponse<String> created = post("/api/v1/card-drafts",
                    "{\"sessionId\":\"" + sessionId + "\"}", token, null);
            if (created.statusCode() != 201) return;
            String draftId = json(created).get("id").asText();
            // адрес намеренно с ошибкой: без замечаний агрегатам нечего показывать
            patch("/api/v1/card-drafts/" + draftId,
                    "{\"address\":{\"country\":\"Россия\",\"locality\":\"Москва\",\"street\":\"Тверская\","
                            + "\"house\":\"21\"},\"incidentTypeIds\":[\"fire.smoke\"],"
                            + "\"description\":\"Задымление в жилом доме, открытого пламени нет.\"}", token);
            post("/api/v1/card-drafts/" + draftId + "/save", null, token, key());
        }
    }

    private String assessmentOf(String token) throws Exception {
        for (JsonNode item : json(get("/api/v1/trainee/results", token))) {
            if (item.hasNonNull("assessmentId")) return item.get("assessmentId").asText();
        }
        throw new IllegalStateException("оценка не появилась");
    }
}
