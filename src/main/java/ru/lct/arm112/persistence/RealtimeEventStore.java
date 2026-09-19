package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.lct.arm112.api.ApiModels.RealtimeEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class RealtimeEventStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RealtimeEventStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long lastSequence(UUID sessionId) {
        Long value = jdbc.queryForObject(
                "select coalesce(max(sequence_number), 0) from realtime_event where session_id = ?",
                Long.class, sessionId);
        return value == null ? 0 : value;
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
