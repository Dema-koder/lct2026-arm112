package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.arm112.service.TrainingEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.lct.arm112.service.TrainingEngine.CARD_ID;
import static ru.lct.arm112.service.TrainingEngine.SESSION_ID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrainingFlowIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TrainingEngine trainingEngine;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Test
    void completesFullTraineeFlowAndReplaysEvents() throws Exception {
        HttpResponse<String> swagger = rawGet("/swagger-ui.html");
        assertThat(swagger.statusCode()).isEqualTo(200);
        assertThat(swagger.headers().firstValue("content-type").orElse(""))
                .contains("text/html");

        HttpResponse<String> contract = rawGet("/openapi.yaml");
        assertThat(contract.statusCode()).isEqualTo(200);
        assertThat(contract.body()).contains("title: ARM-112 DDS Trainee API", "X-Contract-Version");

        HttpResponse<String> swaggerConfig = rawGet("/v3/api-docs/swagger-config");
        assertThat(swaggerConfig.statusCode()).isEqualTo(200);
        assertThat(swaggerConfig.body()).contains("/openapi.yaml");

        HttpResponse<String> login = post("/api/v1/auth/login",
                "{\"username\":\"trainee\",\"password\":\"trainee\"}", null, null);
        assertThat(login.statusCode()).isEqualTo(200);
        String token = json(login).get("accessToken").asText();

        HttpResponse<String> context = get("/api/v1/trainee/context", token);
        assertThat(context.statusCode()).isEqualTo(200);
        assertThat(json(context).get("activeSession").get("mode").asText()).isEqualTo("CARD_ACTIONS");

        HttpResponse<String> opened = get("/api/v1/cards/" + CARD_ID, token);
        assertThat(json(opened).get("status").asText()).isEqualTo("RECEIVED_BY_SERVICE");

        // Открытая, но ещё не принятая карточка не должна выпадать из контроля
        // 30-секундного норматива после автоматического RECEIVE.
        @SuppressWarnings("unchecked")
        Map<UUID, Object> cards = (Map<UUID, Object>) ReflectionTestUtils.getField(trainingEngine, "cards");
        Object mutableCard = cards.get(CARD_ID);
        ReflectionTestUtils.setField(mutableCard, "acceptanceDeadlineAt", Instant.now().minusSeconds(1));
        ReflectionTestUtils.invokeMethod(trainingEngine, "detectOverdue");

        HttpResponse<String> overdue = get("/api/v1/cards/" + CARD_ID, token);
        assertThat(json(overdue).get("sla").get("acceptanceOverdue").asBoolean()).isTrue();

        HttpResponse<String> invalidDecline = post("/api/v1/cards/" + CARD_ID + "/acceptance",
                "{\"action\":\"DECLINE\"}", token, UUID.randomUUID().toString());
        assertThat(invalidDecline.statusCode()).isEqualTo(422);
        assertThat(json(invalidDecline).get("error").get("code").asText()).isEqualTo("VALIDATION_ERROR");

        String acceptanceKey = UUID.randomUUID().toString();
        HttpResponse<String> accepted = post("/api/v1/cards/" + CARD_ID + "/acceptance",
                "{\"action\":\"ACCEPT\"}", token, acceptanceKey);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(json(accepted).get("status").asText()).isEqualTo("ACCEPTED");

        HttpResponse<String> acceptedReplay = post("/api/v1/cards/" + CARD_ID + "/acceptance",
                "{\"action\":\"ACCEPT\"}", token, acceptanceKey);
        assertThat(acceptedReplay.statusCode()).isEqualTo(200);
        assertThat(json(acceptedReplay).get("timeline").size())
                .isEqualTo(json(accepted).get("timeline").size());

        HttpResponse<String> started = post("/api/v1/cards/" + CARD_ID + "/reaction-events",
                "{\"action\":\"START_RESPONSE\"}", token, UUID.randomUUID().toString());
        assertThat(json(started).get("status").asText()).isEqualTo("RESPONSE_STARTED");

        HttpResponse<String> call = post("/api/v1/cards/" + CARD_ID + "/outbound-calls",
                "{\"shortNumber\":\"1102\"}", token, UUID.randomUUID().toString());
        assertThat(call.statusCode()).isEqualTo(201);
        String callId = json(call).get("id").asText();

        Thread.sleep(1400);
        HttpResponse<String> acknowledged = get("/api/v1/outbound-calls/" + callId, token);
        assertThat(json(acknowledged).get("state").asText()).isEqualTo("ACKNOWLEDGED");

        HttpResponse<String> ended = post("/api/v1/outbound-calls/" + callId + "/end",
                null, token, UUID.randomUUID().toString());
        assertThat(json(ended).get("state").asText()).isEqualTo("ENDED");

        // Статусы реагирования проставляются только последовательно: перепрыгнуть
        // через «Прибытие» и «Проведение работ» нельзя (памятка ДДС).
        HttpResponse<String> tooEarly = post("/api/v1/cards/" + CARD_ID + "/reaction-events",
                "{\"action\":\"COMPLETE\",\"comment\":\"Информация передана, работы завершены\"}",
                token, UUID.randomUUID().toString());
        assertThat(tooEarly.statusCode()).isEqualTo(409);
        assertThat(json(tooEarly).get("error").get("code").asText()).isEqualTo("INVALID_STATE_TRANSITION");

        HttpResponse<String> arrived = post("/api/v1/cards/" + CARD_ID + "/reaction-events",
                "{\"action\":\"ARRIVE\"}", token, UUID.randomUUID().toString());
        assertThat(json(arrived).get("status").asText()).isEqualTo("ARRIVED");

        HttpResponse<String> working = post("/api/v1/cards/" + CARD_ID + "/reaction-events",
                "{\"action\":\"START_WORK\"}", token, UUID.randomUUID().toString());
        assertThat(json(working).get("status").asText()).isEqualTo("WORK_IN_PROGRESS");

        HttpResponse<String> completed = post("/api/v1/cards/" + CARD_ID + "/reaction-events",
                "{\"action\":\"COMPLETE\",\"comment\":\"Информация передана, работы завершены\"}",
                token, UUID.randomUUID().toString());
        assertThat(json(completed).get("status").asText()).isEqualTo("COMPLETED");

        HttpResponse<String> submitted = post("/api/v1/training-sessions/" + SESSION_ID + "/submit",
                null, token, UUID.randomUUID().toString());
        assertThat(submitted.statusCode()).isEqualTo(202);
        String assessmentId = json(submitted).get("assessmentId").asText();

        HttpResponse<String> assessment = get("/api/v1/assessments/" + assessmentId, token);
        assertThat(json(assessment).get("state").asText()).isEqualTo("COMPLETED");

        HttpResponse<String> replay = get("/api/v1/training-sessions/" + SESSION_ID
                + "/events?afterSequence=0", token);
        assertThat(json(replay).get("items").size()).isGreaterThanOrEqualTo(8);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("X-Contract-Version", "0.2")
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> rawGet(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token,
                                      String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Contract-Version", "0.2");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        builder.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }
}
