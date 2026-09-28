package ru.lct.arm112.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Передаёт текст аварии во внутренний телефонный шлюз, который инициирует голосовой звонок.
 * URL, токен и номер поступают только из окружения; приложение не зависит от внешнего поставщика.
 */
@Service
public class PhoneCallAlertService {
    private static final Logger log = LoggerFactory.getLogger(PhoneCallAlertService.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final String gatewayUrl;
    private final String gatewayToken;
    private final String phoneNumber;

    public PhoneCallAlertService(ObjectMapper mapper,
                                 @Value("${arm112.alerts.phone.gateway-url:}") String gatewayUrl,
                                 @Value("${arm112.alerts.phone.gateway-token:}") String gatewayToken,
                                 @Value("${arm112.alerts.phone.number:}") String phoneNumber) {
        this.mapper = mapper;
        this.gatewayUrl = gatewayUrl.trim();
        this.gatewayToken = gatewayToken.trim();
        this.phoneNumber = phoneNumber.trim();
    }

    public boolean configured() {
        return !gatewayUrl.isBlank() && !gatewayToken.isBlank() && !phoneNumber.isBlank();
    }

    public boolean send(String message) {
        if (!configured()) return false;
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "phoneNumber", phoneNumber,
                    "message", message,
                    "source", "ARM-112"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + gatewayToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return true;
            log.warn("Телефонный шлюз вернул HTTP {}", response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Отправка звонка прервана");
        } catch (Exception exception) {
            log.warn("Не удалось передать оповещение в телефонный шлюз: {}", exception.getMessage());
        }
        return false;
    }
}
