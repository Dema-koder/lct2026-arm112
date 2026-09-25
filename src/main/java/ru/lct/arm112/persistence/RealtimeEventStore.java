package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import ru.lct.arm112.api.ApiModels.RealtimeEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class RealtimeEventStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final boolean postgres;

    public RealtimeEventStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.postgres = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql")));
    }

    public long lastSequence(UUID sessionId) {
        Long value = jdbc.queryForObject(
                "select coalesce(max(sequence_number), 0) from realtime_event where session_id = ?",
                Long.class, sessionId);
        return value == null ? 0 : value;
    }

    public long nextSequence(UUID sessionId) {
        if (postgres) {
            Long value = jdbc.queryForObject("""
                    insert into realtime_event_sequence (session_id, last_sequence)
                    values (?, 1)
                    on conflict (session_id) do update
                       set last_sequence = realtime_event_sequence.last_sequence + 1
                    returning last_sequence
                    """, Long.class, sessionId);
            return value == null ? 1 : value;
        }
        int updated = jdbc.update("""
                update realtime_event_sequence
                   set last_sequence = last_sequence + 1
                 where session_id = ?
                """, sessionId);
        if (updated == 0) {
            try {
                jdbc.update("""
                        insert into realtime_event_sequence (session_id, last_sequence)
                        select ?, coalesce(max(sequence_number), 0) + 1
                          from realtime_event
                         where session_id = ?
                        """, sessionId, sessionId);
            } catch (DuplicateKeyException race) {
                jdbc.update("""
                        update realtime_event_sequence
                           set last_sequence = last_sequence + 1
                         where session_id = ?
                        """, sessionId);
            }
        }
        Long value = jdbc.queryForObject(
                "select last_sequence from realtime_event_sequence where session_id = ?",
                Long.class, sessionId);
        return value == null ? 1 : value;
    }

    public void append(RealtimeEvent event) {
        jdbc.update("""
                insert into realtime_event
                    (event_id, session_id, sequence_number, event_type, occurred_at,
                     server_time, resource_id, payload)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.eventId(), event.sessionId(), event.sequence(), event.type(),
                Timestamp.from(event.occurredAt()), Timestamp.from(event.serverTime()),
                event.resourceId(), encode(event.payload()));
    }

    public List<RealtimeEvent> after(UUID sessionId, long afterSequence) {
        return jdbc.query("""
                        select event_id, event_type, occurred_at, server_time, session_id,
                               sequence_number, resource_id, payload
                          from realtime_event
                         where session_id = ? and sequence_number > ?
                         order by sequence_number
                        """,
                (resultSet, rowNumber) -> new RealtimeEvent(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getTimestamp("occurred_at").toInstant(),
                        resultSet.getTimestamp("server_time").toInstant(),
                        resultSet.getObject("session_id", UUID.class),
                        resultSet.getLong("sequence_number"),
                        resultSet.getString("resource_id"),
                        decode(resultSet.getString("payload"))),
                sessionId, afterSequence);
    }

    public List<RealtimeEvent> pending(int limit) {
        return jdbc.query("""
                        select event_id, event_type, occurred_at, server_time, session_id,
                               sequence_number, resource_id, payload
                          from realtime_event
                         where delivered_at is null and delivery_attempts < 10
                         order by occurred_at
                         limit ?
                        """,
                (resultSet, rowNumber) -> new RealtimeEvent(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getTimestamp("occurred_at").toInstant(),
                        resultSet.getTimestamp("server_time").toInstant(),
                        resultSet.getObject("session_id", UUID.class),
                        resultSet.getLong("sequence_number"),
                        resultSet.getString("resource_id"),
                        decode(resultSet.getString("payload"))),
                limit);
    }

    public void markDelivered(UUID eventId) {
        jdbc.update("update realtime_event set delivered_at = ?, delivery_attempts = delivery_attempts + 1 where event_id = ?",
                Timestamp.from(Instant.now()), eventId);
    }

    public void markAttempt(UUID eventId) {
        jdbc.update("update realtime_event set delivery_attempts = delivery_attempts + 1 where event_id = ?", eventId);
    }

    private String encode(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось сохранить realtime-событие", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decode(String payload) {
        try {
            return objectMapper.readValue(payload, Map.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось прочитать realtime-событие", exception);
        }
    }
}
