package ru.lct.arm112;

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
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Общие HTTP-помощники интеграционных тестов: контракт 0.3, токены трёх ролей. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ApiTestSupport {
    static final String CONTRACT = "0.3";

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    String login(String username, String password) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null, null);
        assertThat(response.statusCode()).as("login " + username + ": " + response.body()).isEqualTo(200);
        return json(response).get("accessToken").asText();
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("X-Contract-Version", CONTRACT).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> rawGet(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body, String token, String idempotencyKey) throws Exception {
        return send("POST", path, body, token, idempotencyKey);
    }

    HttpResponse<String> put(String path, String body, String token) throws Exception {
        return send("PUT", path, body, token, null);
    }

    HttpResponse<String> patch(String path, String body, String token) throws Exception {
        return send("PATCH", path, body, token, null);
    }

    HttpResponse<String> delete(String path, String token) throws Exception {
        return send("DELETE", path, null, token, null);
    }

    HttpResponse<String> uploadMaterial(String token, String title, String groupId) throws Exception {
        String boundary = "arm112-" + UUID.randomUUID();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n" + title + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"groupIds\"\r\n\r\n" + groupId + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"guide.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nМатериал для проверки\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/teacher/materials"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Contract-Version", CONTRACT)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> send(String method, String path, String body, String token, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Contract-Version", CONTRACT);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    String key() {
        return UUID.randomUUID().toString();
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    /** Создаёт обучающегося через администратора; логин уникальный, чтобы тесты не мешали друг другу. */
    String createTrainee(String adminToken, String workstation) throws Exception {
        String login = "t-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = post("/api/v1/admin/users",
                "{\"login\":\"" + login + "\",\"password\":\"secret1\",\"displayName\":\"Тест " + login
                        + "\",\"role\":\"TRAINEE\",\"workstationNumber\":\"" + workstation + "\"}", adminToken, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return login;
    }

    /** Занятие преподавателя для списка обучающихся; возвращает JSON занятия после старта. */
    JsonNode startLesson(String teacherToken, String kind, String mode, String scenarioIds, String traineeIds) throws Exception {
        HttpResponse<String> created = post("/api/v1/teacher/lessons",
                "{\"title\":\"Тест " + kind + " " + mode + "\",\"kind\":\"" + kind + "\",\"mode\":\"" + mode
                        + "\",\"cardSource\":\"GENERATED\",\"scenarioIds\":[" + scenarioIds + "],\"traineeIds\":[" + traineeIds + "]}",
                teacherToken, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String lessonId = json(created).get("id").asText();
        HttpResponse<String> started = post("/api/v1/teacher/lessons/" + lessonId + "/start", null, teacherToken, null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        return json(started);
    }

    String userId(String token) throws Exception {
        return json(get("/api/v1/auth/me", token)).get("id").asText();
    }
}
