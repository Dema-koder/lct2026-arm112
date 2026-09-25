package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.lct.arm112.persistence.LoginThrottleRepository;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSecurityIntegrationTest extends ApiTestSupport {
    @Autowired
    LoginThrottleRepository throttle;

    @Test
    void refreshTokenIsRotatedAndCanBeRevoked() throws Exception {
        HttpResponse<String> login = post("/api/v1/auth/login",
                "{\"username\":\"trainee\",\"password\":\"trainee\"}", null, null);
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode first = json(login);

        HttpResponse<String> refresh = post("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + first.get("refreshToken").asText() + "\"}", null, null);
        assertThat(refresh.statusCode()).as(refresh.body()).isEqualTo(200);
        JsonNode second = json(refresh);
        assertThat(second.get("refreshToken").asText()).isNotEqualTo(first.get("refreshToken").asText());

        assertThat(post("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + first.get("refreshToken").asText() + "\"}", null, null).statusCode())
                .isEqualTo(401);
        assertThat(post("/api/v1/auth/logout",
                "{\"refreshToken\":\"" + second.get("refreshToken").asText() + "\"}", null, null).statusCode())
                .isEqualTo(204);
        assertThat(post("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + second.get("refreshToken").asText() + "\"}", null, null).statusCode())
                .isEqualTo(401);
    }

    @Test
    void repeatedLoginFailuresAreTemporarilyBlocked() throws Exception {
        String login = "missing-" + UUID.randomUUID();
        String body = "{\"username\":\"" + login + "\",\"password\":\"wrong\"}";
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                assertThat(post("/api/v1/auth/login", body, null, null).statusCode()).isEqualTo(401);
            }
            HttpResponse<String> blocked = post("/api/v1/auth/login", body, null, null);
            assertThat(blocked.statusCode()).isEqualTo(429);
            assertThat(json(blocked).get("error").get("code").asText()).isEqualTo("LOGIN_TEMPORARILY_BLOCKED");
        } finally {
            throttle.delete("login:" + login);
        }
    }
}
