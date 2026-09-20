package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Зачёт на двух обучающихся: монитор, отчёт, оценка преподавателя поверх ИИ, публикация, изоляция преподавателей. */
class TeacherFlowIntegrationTest extends ApiTestSupport {

    @Test
    void examLifecycleWithTeacherOverrideAndPublication() throws Exception {
        String admin = login("admin", "admin");
        String teacher = login("teacher", "teacher");
        String loginA = createTrainee(admin, "7");
        String loginB = createTrainee(admin, "8");
        String tokenA = login(loginA, "secret1");
        String tokenB = login(loginB, "secret1");
        String idA = userId(tokenA);
        String idB = userId(tokenB);

        JsonNode lesson = startLesson(teacher, "EXAM", "CARD_FILL", "\"ticket-01-1\"",
                "\"" + idA + "\",\"" + idB + "\"");
        String lessonId = lesson.get("id").asText();
        assertThat(lesson.get("sessionCount").asInt()).isEqualTo(2);

        // монитор: у каждого своя сессия со своим АРМ (решения №1, №2)
        JsonNode monitor = json(get("/api/v1/teacher/lessons/" + lessonId + "/monitor", teacher));
        assertThat(monitor.get("sessions").size()).isEqualTo(2);
        assertThat(monitor.get("sessions").toString()).contains("\"workstationNumber\":\"7\"", "\"workstationNumber\":\"8\"");

        // обучающийся A заполняет и сохраняет; B — ничего
        String sessionA = json(get("/api/v1/trainee/context", tokenA)).get("activeSession").get("id").asText();
        String draftA = json(post("/api/v1/card-drafts", "{\"sessionId\":\"" + sessionA + "\"}", tokenA, null)).get("id").asText();
        patch("/api/v1/card-drafts/" + draftA,
                "{\"address\":{\"locality\":\"Москва\",\"street\":\"МЖД Киевская 1 км. 2\",\"structure\":\"2\"},"
                        + "\"incidentTypeIds\":[\"fire.garbage\"],\"description\":\"Горит мусорный контейнер у депо, пострадавших нет.\"}", tokenA);
        HttpResponse<String> saved = post("/api/v1/card-drafts/" + draftA + "/save", null, tokenA, key());
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);

        // зачёт: результат обучающемуся не виден до публикации (решение №10)
        JsonNode resultsA = json(get("/api/v1/trainee/results", tokenA));
        JsonNode mine = null;
        for (JsonNode r : resultsA) if (r.get("sessionId").asText().equals(sessionA)) mine = r;
        assertThat(mine).isNotNull();
        assertThat(mine.get("visible").asBoolean()).isFalse();

        // преподаватель завершает занятие принудительно — B получает оценку по факту
        HttpResponse<String> completed = post("/api/v1/teacher/lessons/" + lessonId + "/complete", null, teacher, null);
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        assertThat(json(completed).get("state").asText()).isEqualTo("COMPLETED");

        JsonNode report = json(get("/api/v1/teacher/lessons/" + lessonId + "/report", teacher));
        assertThat(report.get("rows").size()).isEqualTo(2);
        JsonNode rowA = report.get("rows").get(0).get("traineeId").asText().equals(idA) ? report.get("rows").get(0) : report.get("rows").get(1);
        assertThat(rowA.get("aiTotal").asDouble()).isGreaterThan(0);
        assertThat(rowA.get("teacherTotal").isNull()).isTrue();

        // детали сессии и оценка преподавателя рядом с ИИ (решение №5)
        JsonNode detail = json(get("/api/v1/teacher/sessions/" + sessionA, teacher));
        assertThat(detail.get("drafts").size()).isEqualTo(1);
        HttpResponse<String> assessed = put("/api/v1/teacher/sessions/" + sessionA + "/assessment",
                "{\"total\":88.5,\"comment\":\"Адрес верный, описание можно короче\"}", teacher);
        assertThat(assessed.statusCode()).as(assessed.body()).isEqualTo(200);
        assertThat(json(assessed).get("source").asText()).isEqualTo("TEACHER");
        assertThat(json(assessed).get("totalScore").asDouble()).isEqualTo(88.5);
        assertThat(json(assessed).get("aiTotalScore").asDouble()).isGreaterThan(0);

        String assessmentId = json(assessed).get("id").asText();
        assertThat(get("/api/v1/assessments/" + assessmentId, tokenA).statusCode()).isEqualTo(403);

        HttpResponse<String> published = post("/api/v1/teacher/lessons/" + lessonId + "/publish", null, teacher, null);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
        JsonNode visible = json(get("/api/v1/assessments/" + assessmentId, tokenA));
        assertThat(visible.get("source").asText()).isEqualTo("TEACHER");
        assertThat(visible.get("teacherComment").asText()).contains("Адрес верный");
        // чужой результат недоступен
        assertThat(get("/api/v1/assessments/" + assessmentId, tokenB).statusCode()).isEqualTo(403);

        JsonNode reportAfter = json(get("/api/v1/teacher/lessons/" + lessonId + "/report", teacher));
        assertThat(reportAfter.get("rows").toString()).contains("\"finalTotal\":88.5");

        // другой преподаватель не видит это занятие (решение №8)
        post("/api/v1/admin/users", "{\"login\":\"teacher2\",\"password\":\"teacher2\",\"displayName\":\"Второй преподаватель\",\"role\":\"TEACHER\"}",
                admin, null);
        String teacher2 = login("teacher2", "teacher2");
        assertThat(get("/api/v1/teacher/lessons/" + lessonId, teacher2).statusCode()).isEqualTo(403);
        assertThat(json(get("/api/v1/teacher/lessons", teacher2)).size()).isEqualTo(0);

        // рейтинг обучающегося и группы считается по итоговым оценкам
        HttpResponse<String> rating = get("/api/v1/trainee/rating", tokenA);
        assertThat(rating.statusCode()).isEqualTo(200);
        assertThat(json(rating).get("value").asDouble()).isEqualTo(88.5);
    }
}
