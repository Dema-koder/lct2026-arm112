package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.lct.arm112.persistence.TrainingStateStore;
import ru.lct.arm112.service.TrainingEngine;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Полный сценарий режима действий с карточкой: преподаватель раздал, обучающийся отработал, оба видят результат. */
class TrainingFlowIntegrationTest extends ApiTestSupport {
    @Autowired
    TrainingEngine trainingEngine;

    @Autowired
    TrainingStateStore stateStore;

    @Test
    void completesFullTraineeFlowAndReplaysEvents() throws Exception {
        HttpResponse<String> swagger = rawGet("/swagger-ui.html");
        assertThat(swagger.statusCode()).isEqualTo(200);
        HttpResponse<String> contract = rawGet("/openapi.yaml");
        assertThat(contract.statusCode()).isEqualTo(200);
        assertThat(contract.body()).contains("X-Contract-Version");

        String teacher = login("teacher", "teacher");
        String admin = login("admin", "admin");
        assertThat(put("/api/v1/admin/settings", "{\"simulation.card_open_ms\":\"20\",\"simulation.card_arrival_ms\":\"500\"}", admin).statusCode())
                .isEqualTo(200);
        String token = login("trainee", "trainee");
        String traineeId = userId(token);

        JsonNode lesson = startLesson(teacher, "CHECK", "CARD_ACTIONS", "\"ticket-01-1\",\"ticket-03-1\"", "\"" + traineeId + "\"");
        assertThat(lesson.get("state").asText()).isEqualTo("ACTIVE");

        HttpResponse<String> context = get("/api/v1/trainee/context", token);
        assertThat(context.statusCode()).isEqualTo(200);
        JsonNode session = json(context).get("activeSession");
        assertThat(session.get("mode").asText()).isEqualTo("CARD_ACTIONS");
        assertThat(json(context).get("workstation").get("number").asText()).isEqualTo("12");
        String sessionId = session.get("id").asText();

        // Первая карточка уже в журнале, вторая придёт позже по независимому расписанию.
        HttpResponse<String> cards = get("/api/v1/cards?sessionId=" + sessionId, token);
        assertThat(json(cards).get("items").size()).isEqualTo(1);
        assertThat(session.get("pendingScenarios").asInt()).isEqualTo(1);
        String cardId = json(cards).get("items").get(0).get("id").asText();

        HttpResponse<String> opened = get("/api/v1/cards/" + cardId, token);
        assertThat(json(opened).get("status").asText()).isEqualTo("RECEIVED");
        assertThat(json(opened).get("opening").get("readyAt").isNull()).isFalse();
        assertThat(json(opened).get("allowedActions")).isEmpty();
        Thread.sleep(30);
        opened = get("/api/v1/cards/" + cardId, token);
        assertThat(json(opened).get("status").asText()).isEqualTo("RECEIVED_BY_SERVICE");
        assertThat(json(opened).get("ownServiceCode").asText()).isEqualTo("101");
        // Пожар: мусор — по ЕКП оповещается больше одной службы, панель как в оригинале.
        assertThat(json(opened).get("assignedServices").size()).isGreaterThan(3);
        JsonNode serviceProgress = json(opened).get("serviceProgress");
        assertThat(serviceProgress).isNotNull();
        var foreign = new java.util.ArrayList<JsonNode>();
        serviceProgress.forEach(item -> {
            if (item.get("simulated").asBoolean()) foreign.add(item);
        });
        assertThat(foreign).hasSizeGreaterThanOrEqualTo(2);
        assertThat(foreign.get(0).get("arrivedAt").asText())
                .isEqualTo(foreign.get(1).get("arrivedAt").asText());

        // Открытая, но не принятая карточка остаётся под 30-секундным нормативом.
        trainingEngine.forceAcceptanceDeadline(UUID.fromString(cardId), Instant.now().minusSeconds(1));
        HttpResponse<String> overdue = get("/api/v1/cards/" + cardId, token);
        assertThat(json(overdue).get("sla").get("acceptanceOverdue").asBoolean()).isTrue();

        HttpResponse<String> invalidDecline = post("/api/v1/cards/" + cardId + "/acceptance",
                "{\"action\":\"DECLINE\"}", token, key());
        assertThat(invalidDecline.statusCode()).isEqualTo(422);

        String acceptanceKey = key();
        HttpResponse<String> accepted = post("/api/v1/cards/" + cardId + "/acceptance",
                "{\"action\":\"ACCEPT\"}", token, acceptanceKey);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(json(accepted).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(json(accepted).get("timeline").get(json(accepted).get("timeline").size() - 1)
                .get("actorRole").asText()).isEqualTo("TRAINEE");

        HttpResponse<String> replayed = post("/api/v1/cards/" + cardId + "/acceptance",
                "{\"action\":\"ACCEPT\"}", token, acceptanceKey);
        assertThat(json(replayed).get("timeline").size()).isEqualTo(json(accepted).get("timeline").size());

        HttpResponse<String> started = post("/api/v1/cards/" + cardId + "/reaction-events",
                "{\"action\":\"START_RESPONSE\"}", token, key());
        assertThat(json(started).get("status").asText()).isEqualTo("RESPONSE_STARTED");

        HttpResponse<String> call = post("/api/v1/cards/" + cardId + "/outbound-calls",
                "{\"shortNumber\":\"1102\"}", token, key());
        assertThat(call.statusCode()).isEqualTo(201);
        String callId = json(call).get("id").asText();
        Thread.sleep(1500);
        assertThat(json(get("/api/v1/outbound-calls/" + callId, token)).get("state").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(json(post("/api/v1/outbound-calls/" + callId + "/end", null, token, key())).get("state").asText())
                .isEqualTo("ENDED");

        // Новая карточка пришла, пока первая ещё находится в работе.
        HttpResponse<String> concurrentCards = get("/api/v1/cards?sessionId=" + sessionId, token);
        assertThat(json(concurrentCards).get("items").size()).isEqualTo(2);
        assertThat(json(concurrentCards).get("items").toString()).contains("RESPONSE_STARTED");

        // Статусы только последовательно (памятка ДДС).
        HttpResponse<String> tooEarly = post("/api/v1/cards/" + cardId + "/reaction-events",
                "{\"action\":\"COMPLETE\",\"comment\":\"Информация передана, работы завершены\"}", token, key());
        assertThat(tooEarly.statusCode()).isEqualTo(409);

        post("/api/v1/cards/" + cardId + "/reaction-events", "{\"action\":\"ARRIVE\"}", token, key());
        post("/api/v1/cards/" + cardId + "/reaction-events", "{\"action\":\"START_WORK\"}", token, key());
        HttpResponse<String> completed = post("/api/v1/cards/" + cardId + "/reaction-events",
                "{\"action\":\"COMPLETE\",\"comment\":\"Информация передана, пожар ликвидирован, работы завершены\"}", token, key());
        assertThat(json(completed).get("status").asText()).isEqualTo("COMPLETED");

        // Вторая карточка уже ждёт в журнале, завершение первой её не создаёт.
        HttpResponse<String> cards2 = get("/api/v1/cards?sessionId=" + sessionId, token);
        assertThat(json(cards2).get("items").size()).isEqualTo(2);
        String second = json(cards2).get("items").get(0).get("id").asText();
        if (second.equals(cardId)) second = json(cards2).get("items").get(1).get("id").asText();

        HttpResponse<String> notYet = post("/api/v1/training-sessions/" + sessionId + "/submit", null, token, key());
        assertThat(notYet.statusCode()).isEqualTo(422);

        get("/api/v1/cards/" + second, token);
        Thread.sleep(30);
        get("/api/v1/cards/" + second, token);
        HttpResponse<String> declined = post("/api/v1/cards/" + second + "/acceptance",
                "{\"action\":\"DECLINE\",\"reasonCode\":\"NOT_COMPETENCE\",\"comment\":\"Частный дом вне зоны обслуживания, передано в отдел контроля\"}",
                token, key());
        assertThat(json(declined).get("status").asText()).isEqualTo("NOT_ACCEPTED");

        HttpResponse<String> submitted = post("/api/v1/training-sessions/" + sessionId + "/submit", null, token, key());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        String assessmentId = json(submitted).get("assessmentId").asText();

        // Проверка (CHECK): результат виден сразу, отказ от профильного происшествия отмечен.
        HttpResponse<String> assessment = get("/api/v1/assessments/" + assessmentId, token);
        assertThat(assessment.statusCode()).isEqualTo(200);
        assertThat(json(assessment).get("source").asText()).isEqualTo("AI");
        assertThat(json(assessment).get("issues").toString()).contains("REFUSED_PROFILE_INCIDENT");

        HttpResponse<String> replay = get("/api/v1/training-sessions/" + sessionId + "/events?afterSequence=0", token);
        assertThat(json(replay).get("items").size()).isGreaterThanOrEqualTo(8);

        TrainingStateStore.TrainingSnapshot persisted = stateStore.load(UUID.fromString(sessionId)).orElseThrow();
        assertThat(persisted.sessionState()).isEqualTo("COMPLETED");
        assertThat(persisted.cards()).hasSize(2);
        assertThat(persisted.calls()).singleElement()
                .extracting(TrainingStateStore.CallState::state).isEqualTo("ENDED");

        // Преподаватель видит отчёт по своему занятию и занятие закрылось само.
        String lessonId = lesson.get("id").asText();
        HttpResponse<String> report = get("/api/v1/teacher/lessons/" + lessonId + "/report", teacher);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(json(report).get("rows").size()).isEqualTo(1);
        assertThat(json(report).get("rows").get(0).get("workstationNumber").asText()).isEqualTo("12");
        assertThat(json(report).get("lesson").get("state").asText()).isEqualTo("COMPLETED");

        HttpResponse<String> csv = get("/api/v1/teacher/lessons/" + lessonId + "/report.csv", teacher);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.body()).contains("Обучающийся;АРМ");

        HttpResponse<String> results = get("/api/v1/trainee/results", token);
        assertThat(results.statusCode()).isEqualTo(200);
        assertThat(results.body()).contains(sessionId);

        // При трёх сценариях вторая и третья карточки планируются одной пачкой.
        startLesson(teacher, "TRAINING", "CARD_ACTIONS",
                "\"ticket-01-1\",\"ticket-02-1\",\"ticket-03-1\"", "\"" + traineeId + "\"");
        String burstSessionId = json(get("/api/v1/trainee/context", token))
                .get("activeSession").get("id").asText();
        TrainingStateStore.TrainingSnapshot burst = stateStore.load(UUID.fromString(burstSessionId)).orElseThrow();
        assertThat(burst.pendingScenarioIds()).hasSize(2);
        assertThat(new java.util.HashSet<>(burst.scheduledScenarioAt().values())).hasSize(1);
        trainingEngine.forceComplete(UUID.fromString(burstSessionId));
    }
}
