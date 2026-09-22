package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.lct.arm112.service.TrainingEngine;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Поток вводных по интенсивности (замечания 3, 5, 7, 8): карточки ДДС приходят по таймеру независимо
 * от текущей, служба обучающегося задаётся в занятии, оператор 112 принимает входящие вызовы из очереди.
 */
class FlowIntensityIntegrationTest extends ApiTestSupport {
    @Autowired
    TrainingEngine trainingEngine;

    private JsonNode startLessonWithFlow(String teacher, String mode, String scenarioIds, String traineeId,
                                         String serviceCode, String intensity) throws Exception {
        HttpResponse<String> created = post("/api/v1/teacher/lessons",
                "{\"title\":\"Поток " + mode + "\",\"kind\":\"CHECK\",\"mode\":\"" + mode
                        + "\",\"cardSource\":\"GENERATED\",\"scenarioIds\":[" + scenarioIds + "],\"traineeIds\":[\"" + traineeId + "\"]"
                        + (serviceCode == null ? "" : ",\"serviceCode\":\"" + serviceCode + "\"")
                        + ",\"intensity\":\"" + intensity + "\"}",
                teacher, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(json(created).get("intensity").asText()).isEqualTo(intensity);
        String lessonId = json(created).get("id").asText();
        HttpResponse<String> started = post("/api/v1/teacher/lessons/" + lessonId + "/start", null, teacher, null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        return json(started);
    }

    @Test
    void ddsCardsArriveByTimerAndOwnServiceTileIsAlwaysPresent() throws Exception {
        String teacher = login("teacher", "teacher");
        String token = login("trainee", "trainee");
        String traineeId = userId(token);

        HttpResponse<String> unknownService = post("/api/v1/teacher/lessons",
                "{\"title\":\"x\",\"kind\":\"CHECK\",\"mode\":\"CARD_ACTIONS\",\"cardSource\":\"GENERATED\",\"scenarioIds\":[\"ticket-01-1\"],"
                        + "\"traineeIds\":[\"" + traineeId + "\"],\"serviceCode\":\"999\"}", teacher, null);
        assertThat(unknownService.statusCode()).isEqualTo(422);

        JsonNode lesson = startLessonWithFlow(teacher, "CARD_ACTIONS", "\"ticket-01-1\",\"ticket-03-1\"", traineeId, "102", "HIGH");
        assertThat(lesson.get("serviceCode").asText()).isEqualTo("102");

        JsonNode session = json(get("/api/v1/trainee/context", token)).get("activeSession");
        String sessionId = session.get("id").asText();
        assertThat(session.get("ownServiceCode").asText()).isEqualTo("102");
        assertThat(session.get("intensity").asText()).isEqualTo("HIGH");
        assertThat(session.get("difficulty").asInt()).isEqualTo(8);

        // Первая карточка сразу; вторая приходит по таймеру, не дожидаясь закрытия первой.
        assertThat(json(get("/api/v1/cards?sessionId=" + sessionId, token)).get("items").size()).isEqualTo(1);
        trainingEngine.forceArrival(UUID.fromString(sessionId));
        JsonNode items = json(get("/api/v1/cards?sessionId=" + sessionId, token)).get("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(json(get("/api/v1/trainee/context", token)).get("activeSession").get("pendingScenarios").asInt()).isZero();

        // Плитка своей службы есть на каждой карточке, даже если по ЕКП служба 102 не оповещается.
        for (JsonNode item : items) {
            JsonNode card = json(get("/api/v1/cards/" + item.get("id").asText(), token));
            assertThat(card.get("ownServiceCode").asText()).isEqualTo("102");
            assertThat(card.get("assignedServices").get(0).get("code").asText()).isEqualTo("102");
        }
    }

    /** Справочники ПОВ-112 (КАРТОЧКА 112.docx, СЛУЖБЫ 112.docx): список «что случилось?», опросная карта, службы. */
    @Test
    void cardTypesSurveyTreeAndTerritorialServices() throws Exception {
        String teacher = login("teacher", "teacher");
        String token = login("trainee", "trainee");
        String traineeId = userId(token);

        JsonNode types = json(get("/api/v1/references/card-types", token));
        assertThat(types.size()).isEqualTo(51);
        assertThat(types.toString()).contains("\"101\"", "ДТП", "Угроза взрыва/террористического акта");
        JsonNode found = json(get("/api/v1/references/card-types?query=взрыв", token));
        assertThat(found.size()).isEqualTo(2);
        assertThat(found.toString()).contains("Взрыв", "Угроза взрыва/террористического акта");

        JsonNode tree = json(get("/api/v1/references/survey-trees/t101", token));
        assertThat(tree.get("label").asText()).isEqualTo("101");
        assertThat(tree.get("questions").toString()).contains("Признак пожара (улица)", "Мачта освещения", "Проведена ли газификация");
        assertThat(get("/api/v1/references/survey-trees/t999", token).statusCode()).isEqualTo(404);

        JsonNode services = json(get("/api/v1/references/services", token));
        assertThat(services.size()).isGreaterThan(200);
        assertThat(services.toString()).contains("Центр организации дорожного движения", "ДДС района Щукино");

        // Ответы опросной карты определяют тип ЕКП, а адрес добавляет ДДС района и префектуры округа.
        startLessonWithFlow(teacher, "CARD_FILL", "\"ticket-01-1\"", traineeId, null, "SEQUENTIAL");
        String sessionId = json(get("/api/v1/trainee/context", token)).get("activeSession").get("id").asText();
        String draftId = json(post("/api/v1/card-drafts", "{\"sessionId\":\"" + sessionId + "\"}", token, null)).get("id").asText();
        HttpResponse<String> patched = patch("/api/v1/card-drafts/" + draftId,
                "{\"topTypeId\":\"t101\",\"surveyAnswers\":[{\"questionId\":\"where\",\"optionId\":\"street\",\"text\":null},"
                        + "{\"questionId\":\"sign_street\",\"optionId\":\"flame\",\"text\":null},"
                        + "{\"questionId\":\"street_object\",\"optionId\":\"garbage\",\"text\":null}],"
                        + "\"address\":{\"country\":\"Россия\",\"locality\":\"Москва\",\"district\":\"Щукино\",\"street\":\"Берзарина\",\"house\":\"21\"}}",
                token);
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(json(patched).get("incidentTypeIds").toString()).isEqualTo("[\"fire.garbage\"]");
        String codes = json(patched).get("services").toString();
        assertThat(codes).contains("R_SCHUKINO", "AO_SZAO");

        // трава вместо мусора — другой тип ЕКП по тому же дереву
        assertThat(json(patch("/api/v1/card-drafts/" + draftId,
                "{\"surveyAnswers\":[{\"questionId\":\"where\",\"optionId\":\"street\",\"text\":null},"
                        + "{\"questionId\":\"sign_street\",\"optionId\":\"flame\",\"text\":null},"
                        + "{\"questionId\":\"street_object\",\"optionId\":\"grass\",\"text\":null}]}", token))
                .get("incidentTypeIds").toString()).isEqualTo("[\"fire.grass\"]");
    }

    @Test
    void operator112AnswersIncomingCallsAndMissedCallIsAssessed() throws Exception {
        String teacher = login("teacher", "teacher");
        String token = login("trainee", "trainee");
        String traineeId = userId(token);

        startLessonWithFlow(teacher, "CARD_FILL", "\"ticket-02-1\",\"ticket-01-1\"", traineeId, null, "MEDIUM");
        JsonNode session = json(get("/api/v1/trainee/context", token)).get("activeSession");
        String sessionId = session.get("id").asText();

        // Первый вызов звонит сразу; в журнале — фоновые карточки смены.
        assertThat(session.get("incomingCall").isNull()).isFalse();
        assertThat(session.get("incomingCall").get("callerName").asText()).isEqualTo("Ким Олег Юрьевич");
        JsonNode journal = json(get("/api/v1/training-sessions/" + sessionId + "/journal", token));
        assertThat(journal.get("rows").size()).isGreaterThanOrEqualTo(1);
        assertThat(journal.get("rows").toString()).contains("BACKGROUND");

        HttpResponse<String> created = post("/api/v1/card-drafts", "{\"sessionId\":\"" + sessionId + "\"}", token, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String draftId = json(created).get("id").asText();
        assertThat(json(created).get("callerAddress").asText()).contains("Берзарина");
        assertThat(json(get("/api/v1/trainee/context", token)).get("activeSession").get("incomingCall").isNull()).isTrue();

        patch("/api/v1/card-drafts/" + draftId,
                "{\"address\":{\"country\":\"Россия\",\"locality\":\"Москва\",\"street\":\"Берзарина\",\"house\":\"21\"},\"incidentTypeIds\":[\"fire.smoke\"]}",
                token);
        assertThat(post("/api/v1/card-drafts/" + draftId + "/save", null, token, key()).statusCode()).isEqualTo(200);

        // Вторая вводная ещё не пришла — принимать нечего.
        HttpResponse<String> noCall = post("/api/v1/card-drafts", "{\"sessionId\":\"" + sessionId + "\"}", token, null);
        assertThat(noCall.statusCode()).isEqualTo(409);
        assertThat(noCall.body()).contains("NO_INCOMING_CALL");

        // Пришла и звонит; оператор не ответил — заявитель перезванивает.
        trainingEngine.forceArrival(UUID.fromString(sessionId));
        JsonNode ringing = json(get("/api/v1/trainee/context", token)).get("activeSession").get("incomingCall");
        assertThat(ringing.isNull()).isFalse();
        trainingEngine.forceRingTimeout(UUID.fromString(sessionId));
        JsonNode afterMiss = json(get("/api/v1/trainee/context", token)).get("activeSession");
        assertThat(afterMiss.get("incomingCall").isNull()).isTrue();
        assertThat(afterMiss.get("queuedCalls").asInt()).isEqualTo(1);
        trainingEngine.forceArrival(UUID.fromString(sessionId));
        JsonNode redial = json(get("/api/v1/trainee/context", token)).get("activeSession").get("incomingCall");
        assertThat(redial.get("missedCount").asInt()).isEqualTo(1);

        String second = json(post("/api/v1/card-drafts", "{\"sessionId\":\"" + sessionId + "\"}", token, null)).get("id").asText();
        patch("/api/v1/card-drafts/" + second,
                "{\"address\":{\"country\":\"Россия\",\"locality\":\"Москва\",\"street\":\"Грина\",\"house\":\"13\"},\"incidentTypeIds\":[\"fire.garbage\"]}",
                token);
        assertThat(post("/api/v1/card-drafts/" + second + "/save", null, token, key()).statusCode()).isEqualTo(200);

        // Все вызовы отработаны — занятие завершилось само, пропуск вызова отмечен в оценке.
        JsonNode mine = null;
        for (JsonNode item : json(get("/api/v1/trainee/results", token))) if (item.get("sessionId").asText().equals(sessionId)) mine = item;
        assertThat(mine).isNotNull();
        JsonNode assessment = json(get("/api/v1/assessments/" + mine.get("assessmentId").asText(), token));
        assertThat(assessment.get("issues").toString()).contains("CALL_MISSED");
        assertThat(json(get("/api/v1/training-sessions/" + sessionId + "/journal", token)).get("rows").toString())
                .contains("\"OWN\"");
    }
}
