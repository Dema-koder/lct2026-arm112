package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Режим заполнения карточки: вводная → адрес с опечаткой → тип → службы → сохранить → оценка. */
class CardFillFlowIntegrationTest extends ApiTestSupport {

    @Test
    void fillsCardWithTypoAndGetsAddressIssue() throws Exception {
        String teacher = login("teacher", "teacher");
        String token = login("trainee", "trainee");
        String traineeId = userId(token);

        JsonNode lesson = startLesson(teacher, "TRAINING", "CARD_FILL", "\"ticket-02-1\"", "\"" + traineeId + "\"");
        String sessionId = json(get("/api/v1/trainee/context", token)).get("activeSession").get("id").asText();
        assertThat(json(get("/api/v1/trainee/context", token)).get("activeSession").get("mode").asText()).isEqualTo("CARD_FILL");

        HttpResponse<String> created = post("/api/v1/card-drafts", "{\"sessionId\":\"" + sessionId + "\"}", token, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode draft = json(created);
        String draftId = draft.get("id").asText();
        assertThat(draft.get("callerText").asText()).contains("Задымление мусоропровода");
        assertThat(draft.get("caller").get("fullName").asText()).isEqualTo("Ким Олег Юрьевич");

        // Повторный запрос отдаёт тот же незакрытый черновик, а не новую вводную.
        assertThat(json(post("/api/v1/card-drafts", "{\"sessionId\":\"" + sessionId + "\"}", token, null))
                .get("id").asText()).isEqualTo(draftId);

        HttpResponse<String> types = get("/api/v1/references/incident-types?query=дым", token);
        assertThat(types.body()).contains("fire.smoke");
        HttpResponse<String> survey = get("/api/v1/references/survey-cards/fire.smoke", token);
        assertThat(json(survey).get("questions").size()).isGreaterThan(0);

        // Опечатка в улице: Берзорина вместо Берзарина.
        HttpResponse<String> patched = patch("/api/v1/card-drafts/" + draftId,
                "{\"address\":{\"country\":\"Россия\",\"locality\":\"Москва\",\"street\":\"Берзорина\",\"house\":\"21\",\"building\":\"1\",\"entrance\":\"3\"},"
                        + "\"incidentTypeIds\":[\"fire.smoke\"],"
                        + "\"description\":\"Задымление мусоропровода в жилом доме, 17 этажей, заявитель на 7 этаже, открытого пламени нет.\"}",
                token);
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        JsonNode afterPatch = json(patched);
        // службы подобрались автоматически по ЕКП
        assertThat(afterPatch.get("services").size()).isGreaterThan(3);
        assertThat(afterPatch.get("services").toString()).contains("\"auto\":true");
        // тренировка: подсказка про улицу приходит сразу (решение №10)
        assertThat(afterPatch.get("hints").toString()).contains("address.street");

        HttpResponse<String> saved = post("/api/v1/card-drafts/" + draftId + "/save", null, token, key());
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(json(saved).get("state").asText()).isEqualTo("SAVED");

        // Единственная вводная сохранена — занятие завершилось само.
        HttpResponse<String> results = get("/api/v1/trainee/results", token);
        JsonNode mine = null;
        for (JsonNode item : json(results)) if (item.get("sessionId").asText().equals(sessionId)) mine = item;
        assertThat(mine).isNotNull();
        assertThat(mine.get("visible").asBoolean()).isTrue();
        String assessmentId = mine.get("assessmentId").asText();

        JsonNode assessment = json(get("/api/v1/assessments/" + assessmentId, token));
        assertThat(assessment.get("mode").asText()).isEqualTo("CARD_FILL");
        assertThat(assessment.get("addressScore").asDouble()).isLessThan(100);
        assertThat(assessment.get("issues").toString()).contains("ADDRESS_STREET_MISMATCH");
        assertThat(assessment.get("classificationScore").asDouble()).isEqualTo(100);

        // Сохранённая карточка стала сценарием «сформировано обучающимися».
        HttpResponse<String> traineeMade = get("/api/v1/teacher/scenarios?source=TRAINEE_MADE", teacher);
        assertThat(json(traineeMade).size()).isGreaterThan(0);

        assertThat(json(get("/api/v1/teacher/lessons/" + lesson.get("id").asText(), teacher)).get("state").asText())
                .isEqualTo("COMPLETED");
    }
}
