package ru.lct.arm112.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.AlertCallAttempt;
import ru.lct.arm112.api.ApiModels.PhoneCallStatusUpdate;
import ru.lct.arm112.persistence.AlertCallRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Передаёт аварийные сообщения во внутренний телефонный шлюз и сохраняет результат каждой попытки.
 * Секреты поступают только из окружения и никогда не возвращаются через API.
 */
@Service
public class PhoneCallAlertService {
    private static final Logger log = LoggerFactory.getLogger(PhoneCallAlertService.class);
    private static final List<String> CALLBACK_STATES = List.of("ACCEPTED", "ANSWERED", "NOT_ANSWERED", "FAILED");

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final AlertCallRepository attempts;
    private final String gatewayUrl;
    private final String gatewayToken;
    private final List<String> phoneNumbers;

    public PhoneCallAlertService(ObjectMapper mapper, AlertCallRepository attempts,
                                 @Value("${arm112.alerts.phone.gateway-url:}") String gatewayUrl,
                                 @Value("${arm112.alerts.phone.gateway-token:}") String gatewayToken,
                                 @Value("${arm112.alerts.phone.numbers:}") String configuredNumbers,
                                 @Value("${arm112.alerts.phone.number:}") String phoneNumber) {
        this.mapper = mapper;
        this.attempts = attempts;
        this.gatewayUrl = gatewayUrl.trim();
        this.gatewayToken = gatewayToken.trim();
        String source = configuredNumbers == null || configuredNumbers.isBlank() ? phoneNumber : configuredNumbers;
        this.phoneNumbers = Arrays.stream(source.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    public boolean configured() {
        return !gatewayUrl.isBlank() && !gatewayToken.isBlank() && !phoneNumbers.isEmpty();
    }

    public int recipientCount() {
        return phoneNumbers.size();
    }

    public boolean send(String message) {
        return send(message, "SYSTEM", null);
    }

    public boolean send(String message, String triggerType, String serviceId) {
        AlertCallAttempt attempt = deliverChain(message, triggerType, serviceId, null, 1, 1);
        return attempt.status().equals("ACCEPTED") || attempt.status().equals("ANSWERED");
    }

    /** Отдельный метод для API, которому нужен созданный объект истории. */
    public AlertCallAttempt sendDetailed(String message, String triggerType, String serviceId) {
        return deliverChain(message, triggerType, serviceId, null, 1, 1);
    }

    public List<AlertCallAttempt> history(int limit) {
        return attempts.list(limit);
    }

    public AlertCallAttempt retry(UUID id) {
        AlertCallAttempt source = attempts.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CALL_ATTEMPT_NOT_FOUND", "Попытка звонка не найдена"));
        if (!configured()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALERTS_NOT_CONFIGURED",
                    "Телефонные оповещения не настроены на сервере");
        }
        return deliver(source.message(), "RETRY", source.serviceId(), source.id(),
                source.attemptNumber() + 1, Math.min(source.recipientOrder(), phoneNumbers.size()));
    }

    public void updateStatus(String gatewayCallId, String suppliedToken, PhoneCallStatusUpdate update) {
        if (!tokenMatches(suppliedToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GATEWAY_TOKEN", "Неверный токен телефонного шлюза");
        }
        String status = update.status().trim().toUpperCase(Locale.ROOT);
        if (!CALLBACK_STATES.contains(status)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Допустимые статусы: ACCEPTED, ANSWERED, NOT_ANSWERED, FAILED");
        }
        Boolean answered = status.equals("ANSWERED") ? true
                : status.equals("NOT_ANSWERED") ? false : update.answered();
        AlertCallAttempt source = attempts.findByGatewayCallId(gatewayCallId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "GATEWAY_CALL_NOT_FOUND", "Звонок шлюза не найден"));
        if (List.of("ANSWERED", "NOT_ANSWERED", "FAILED").contains(source.status())) return;
        if (attempts.updateByGatewayCallId(gatewayCallId, status, answered,
                cleanError(update.errorMessage()), Instant.now()) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GATEWAY_CALL_NOT_FOUND", "Звонок шлюза не найден");
        }
        if ((status.equals("NOT_ANSWERED") || status.equals("FAILED"))
                && source.recipientOrder() < phoneNumbers.size()) {
            deliverChain(source.message(), "ESCALATION", source.serviceId(), source.id(),
                    source.attemptNumber() + 1, source.recipientOrder() + 1);
        }
    }

    private AlertCallAttempt deliverChain(String message, String triggerType, String serviceId,
                                          UUID retryOfId, int attemptNumber, int recipientOrder) {
        AlertCallAttempt result = deliver(message, triggerType, serviceId, retryOfId, attemptNumber, recipientOrder);
        while (configured() && result.status().equals("FAILED") && result.recipientOrder() < phoneNumbers.size()) {
            result = deliver(message, "ESCALATION", serviceId, result.id(), result.attemptNumber() + 1,
                    result.recipientOrder() + 1);
        }
        return result;
    }

    private AlertCallAttempt deliver(String message, String triggerType, String serviceId,
                                     UUID retryOfId, int attemptNumber, int recipientOrder) {
        Instant requestedAt = Instant.now();
        UUID id = UUID.randomUUID();
        String status = "FAILED";
        Boolean answered = null;
        String gatewayCallId = null;
        String error = null;
        if (!configured()) {
            error = "Телефонный шлюз не настроен";
        } else {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("phoneNumber", phoneNumbers.get(recipientOrder - 1));
                payload.put("message", message);
                payload.put("source", "ARM-112");
                payload.put("attemptId", id.toString());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(gatewayUrl))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    status = "ACCEPTED";
                    if (response.body() != null && !response.body().isBlank()) {
                        JsonNode body = mapper.readTree(response.body());
                        gatewayCallId = text(body, "callId");
                        String reported = text(body, "status");
                        if (reported != null && CALLBACK_STATES.contains(reported.toUpperCase(Locale.ROOT))) {
                            status = reported.toUpperCase(Locale.ROOT);
                        }
                        if (body.path("answered").isBoolean()) answered = body.path("answered").asBoolean();
                        if (status.equals("FAILED")) error = text(body, "errorMessage");
                    }
                } else {
                    error = "Телефонный шлюз вернул HTTP " + response.statusCode();
                    log.warn(error);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                error = "Отправка звонка прервана";
                log.warn(error);
            } catch (Exception exception) {
                error = "Не удалось передать звонок: " + exception.getMessage();
                log.warn("Не удалось передать оповещение в телефонный шлюз: {}", exception.getMessage());
            }
        }
        if (status.equals("ANSWERED")) answered = true;
        if (status.equals("NOT_ANSWERED")) answered = false;
        Instant updatedAt = Instant.now();
        AlertCallAttempt attempt = new AlertCallAttempt(id, retryOfId, serviceId, triggerType,
                maskedPhone(recipientOrder), message, status, answered, attemptNumber, recipientOrder, gatewayCallId,
                cleanError(error), requestedAt, updatedAt);
        attempts.insert(attempt);
        return attempt;
    }

    private boolean tokenMatches(String suppliedToken) {
        if (gatewayToken.isBlank() || suppliedToken == null) return false;
        return MessageDigest.isEqual(gatewayToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8));
    }

    private String maskedPhone(int recipientOrder) {
        if (phoneNumbers.isEmpty()) return "не настроен";
        String compact = phoneNumbers.get(Math.max(0, recipientOrder - 1)).replaceAll("\\D", "");
        if (compact.length() <= 4) return "••••";
        return "•••• " + compact.substring(compact.length() - 4);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String cleanError(String error) {
        if (error == null || error.isBlank()) return null;
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
