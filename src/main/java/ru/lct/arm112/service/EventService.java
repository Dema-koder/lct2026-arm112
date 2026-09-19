package ru.lct.arm112.service;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import ru.lct.arm112.api.ApiModels.RealtimeEvent;
import ru.lct.arm112.persistence.RealtimeEventStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class EventService {
    private final ObjectMapper objectMapper;
    private final RealtimeEventStore eventStore;
    private final Map<UUID, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<WebSocketSession> sockets = new CopyOnWriteArrayList<>();

    public EventService(ObjectMapper objectMapper, RealtimeEventStore eventStore) {
        this.objectMapper = objectMapper;
        this.eventStore = eventStore;
    }

    public RealtimeEvent publish(UUID sessionId, String type, String resourceId,
                                 Map<String, Object> payload) {
        long sequence = sequences.computeIfAbsent(sessionId,
                ignored -> new AtomicLong(eventStore.lastSequence(sessionId))).incrementAndGet();
        Instant now = Instant.now();
        RealtimeEvent event = new RealtimeEvent(UUID.randomUUID(), type, now, now,
                sessionId, sequence, resourceId, payload);
        eventStore.append(event);
        broadcast(event);
        return event;
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

    private void broadcast(RealtimeEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            for (WebSocketSession socket : sockets) {
                if (!socket.isOpen()) {
                    sockets.remove(socket);
                    continue;
                }
                try {
                    synchronized (socket) {
                        socket.sendMessage(new TextMessage(json));
                    }
                } catch (IOException ignored) {
                    sockets.remove(socket);
                }
            }
        } catch (JacksonException ignored) {
            // REST replay remains available even if serialization for a socket fails.
        }
    }
}
