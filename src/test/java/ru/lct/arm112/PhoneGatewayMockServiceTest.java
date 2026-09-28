package ru.lct.arm112;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.lct.arm112.api.ApiModels.MockPhoneGatewaySettings;
import ru.lct.arm112.api.ApiModels.MockPhoneGatewayUpdate;
import ru.lct.arm112.service.PhoneGatewayMockService;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneGatewayMockServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void reportsDisabledWhenControlUrlIsEmpty() {
        PhoneGatewayMockService service = new PhoneGatewayMockService(new ObjectMapper(), "", "token");

        MockPhoneGatewaySettings status = service.status();

        assertThat(status.available()).isFalse();
        assertThat(status.scenario()).isNull();
        assertThat(status.allowedScenarios()).contains("ANSWERED", "NOT_ANSWERED", "UNAVAILABLE");
    }

    @Test
    void readsAndUpdatesScenarioThroughAuthenticatedControlApi() throws Exception {
        AtomicReference<String> state = new AtomicReference<>("{\"scenario\":\"ANSWERED\",\"delayMs\":1000}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scenario", exchange -> handle(exchange, state));
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/scenario";
        PhoneGatewayMockService service = new PhoneGatewayMockService(new ObjectMapper(), url, "test-token");

        assertThat(service.status().scenario()).isEqualTo("ANSWERED");
        MockPhoneGatewaySettings updated = service.update(new MockPhoneGatewayUpdate("not_answered", 750));

        assertThat(updated.available()).isTrue();
        assertThat(updated.scenario()).isEqualTo("NOT_ANSWERED");
        assertThat(updated.delayMs()).isEqualTo(750);
    }

    private static void handle(HttpExchange exchange, AtomicReference<String> state) throws IOException {
        if (!"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        if ("PUT".equals(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("NOT_ANSWERED") && body.contains("750")) {
                state.set("{\"scenario\":\"NOT_ANSWERED\",\"delayMs\":750}");
            }
        }
        byte[] response = state.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
