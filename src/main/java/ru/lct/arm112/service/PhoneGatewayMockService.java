package ru.lct.arm112.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.MockPhoneGatewaySettings;
import ru.lct.arm112.api.ApiModels.MockPhoneGatewayUpdate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Управляет только локальным тестовым шлюзом. Пустой control URL полностью отключает эту возможность. */
@Service
public class PhoneGatewayMockService {
    private static final List<String> SCENARIOS = List.of(
            "ACCEPTED", "ANSWERED", "NOT_ANSWERED", "FAILED", "UNAVAILABLE");

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;
    private final String controlUrl;
    private final String gatewayToken;

    public PhoneGatewayMockService(ObjectMapper mapper,
                                   @Value("${arm112.alerts.phone.mock-control-url:}") String controlUrl,
                                   @Value("${arm112.alerts.phone.gateway-token:}") String gatewayToken) {
        this.mapper = mapper;
        this.controlUrl = controlUrl.trim();
        this.gatewayToken = gatewayToken.trim();
    }

    public MockPhoneGatewaySettings status() {
        if (!configured()) {
            return new MockPhoneGatewaySettings(false, null, null, SCENARIOS,
                    "Mock-шлюз доступен только в локальном Docker Compose");
        }
        try {
            HttpResponse<String> response = client.send(requestBuilder().GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return unavailable("Mock-шлюз вернул HTTP " + response.statusCode());
            }
            JsonNode body = mapper.readTree(response.body());
            return new MockPhoneGatewaySettings(true, body.path("scenario").asText(),
                    body.path("delayMs").asLong(), SCENARIOS, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable("Проверка mock-шлюза прервана");
        } catch (Exception exception) {
            return unavailable("Mock-шлюз недоступен: " + exception.getMessage());
        }
    }

    public MockPhoneGatewaySettings update(MockPhoneGatewayUpdate update) {
        if (!configured()) {
            throw new ApiException(HttpStatus.CONFLICT, "MOCK_GATEWAY_DISABLED",
                    "Mock-шлюз доступен только в локальном Docker Compose");
        }
        String scenario = update.scenario().trim().toUpperCase(Locale.ROOT);
        if (!SCENARIOS.contains(scenario)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Неизвестный сценарий mock-шлюза");
        }
        try {
            String payload = mapper.writeValueAsString(new MockPhoneGatewayUpdate(scenario, update.delayMs()));
            HttpResponse<String> response = client.send(requestBuilder()
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(payload)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "MOCK_GATEWAY_ERROR",
                        "Mock-шлюз не принял настройки");
            }
            JsonNode body = mapper.readTree(response.body());
            return new MockPhoneGatewaySettings(true, body.path("scenario").asText(),
                    body.path("delayMs").asLong(), SCENARIOS, null);
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MOCK_GATEWAY_ERROR",
                    "Настройка mock-шлюза прервана");
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MOCK_GATEWAY_ERROR",
                    "Не удалось настроить mock-шлюз");
        }
    }

    private boolean configured() {
        return !controlUrl.isBlank() && !gatewayToken.isBlank();
    }

    private HttpRequest.Builder requestBuilder() {
        return HttpRequest.newBuilder().uri(URI.create(controlUrl)).timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + gatewayToken);
    }

    private MockPhoneGatewaySettings unavailable(String message) {
        return new MockPhoneGatewaySettings(false, null, null, SCENARIOS, message);
    }
}
