package ru.lct.arm112.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.lct.arm112.api.ApiModels.RealtimeEvent;
import ru.lct.arm112.persistence.RealtimeEventStore;
import ru.lct.arm112.security.Role;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Realtime-события: сохраняются для replay и доставляются адресно — обучающемуся сессии
 * и преподавателю занятия. Администратор событий занятий не получает.
 */
@Service
public class EventService {
    private final ObjectMapper objectMapper;
    private final RealtimeEventStore eventStore;
    private final SessionAccess access;
    private final OperationalMetrics metrics;
    private final CopyOnWriteArrayList<WebSocketSession> sockets = new CopyOnWriteArrayList<>();

    public EventService(ObjectMapper objectMapper, RealtimeEventStore eventStore, SessionAccess access,
                        OperationalMetrics metrics, MeterRegistry registry) {
        this.objectMapper = objectMapper;
        this.eventStore = eventStore;
        this.access = access;
        this.metrics = metrics;
        Gauge.builder("arm112.websocket.connections", this, EventService::openSockets).register(registry);
    }

    public RealtimeEvent publish(UUID sessionId, String type, String resourceId,
                                 Map<String, Object> payload) {
        long sequence = eventStore.nextSequence(sessionId);
        Instant now = Instant.now();
        RealtimeEvent event = new RealtimeEvent(UUID.randomUUID(), type, now, now,
                sessionId, sequence, resourceId, payload);
        eventStore.append(event);
        metrics.realtimePublished();
        Set<UUID> recipients = access.recipientsOf(sessionId)
                .map(SessionAccess.Recipients::userIds).orElse(Set.of());
        Runnable delivery = () -> deliverPersisted(event, recipients);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { delivery.run(); }
            });
        } else delivery.run();
        return event;
    }

    /** Служебное событие одному пользователю без записи в журнал сессии (например, «занятие началось»). */
    public void notifyUser(UUID userId, String type, Map<String, Object> payload) {
        Instant now = Instant.now();
        RealtimeEvent event = new RealtimeEvent(UUID.randomUUID(), type, now, now, null, 0, null, payload);
        deliver(event, Set.of(userId));
    }

    public List<RealtimeEvent> after(UUID sessionId, long afterSequence) {
        return eventStore.after(sessionId, afterSequence);
    }

    public void register(WebSocketSession session) {
        sockets.add(session);
    }

    public void unregister(WebSocketSession session) {
        sockets.remove(session);
    }

    public int openSockets() {
        return (int) sockets.stream().filter(WebSocketSession::isOpen).count();
    }

    @Scheduled(fixedDelayString = "${arm112.realtime.retry-ms:5000}")
    void retryPending() {
        for (RealtimeEvent event : eventStore.pending(100)) {
            Set<UUID> recipients = access.recipientsOf(event.sessionId())
                    .map(SessionAccess.Recipients::userIds).orElse(Set.of());
            if (deliver(event, recipients)) eventStore.markDelivered(event.eventId());
            else {
                eventStore.markAttempt(event.eventId());
                metrics.realtimeDeliveryFailure();
            }
        }
    }

    private void deliverPersisted(RealtimeEvent event, Set<UUID> recipients) {
        if (deliver(event, recipients)) eventStore.markDelivered(event.eventId());
        else {
            eventStore.markAttempt(event.eventId());
            metrics.realtimeDeliveryFailure();
        }
    }

    private boolean deliver(RealtimeEvent event, Set<UUID> recipients) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JacksonException ignored) {
            return false; // REST replay остаётся доступным
        }
        boolean success = true;
        for (WebSocketSession socket : sockets) {
            if (!socket.isOpen()) {
                sockets.remove(socket);
                continue;
            }
            Object subject = socket.getAttributes().get("subject");
            Object role = socket.getAttributes().get("role");
            UUID userId;
            try {
                userId = UUID.fromString(String.valueOf(subject));
            } catch (Exception ex) {
                continue;
            }
            boolean addressed = recipients.contains(userId)
                    || (Role.ADMIN.name().equals(role) && event.sessionId() == null);
            if (!addressed) continue;
            try {
                synchronized (socket) {
                    socket.sendMessage(new TextMessage(json));
                }
            } catch (IOException ignored) {
                sockets.remove(socket);
                success = false;
            }
        }
        return success;
    }
}
