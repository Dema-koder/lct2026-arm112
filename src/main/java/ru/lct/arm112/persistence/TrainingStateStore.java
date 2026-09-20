package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.arm112.api.ApiModels.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Снимок состояния одной персональной сессии: карточки, звонки, черновики и очередь сценариев.
 * Ключ строки — идентификатор сессии; сессии из таблицы training_session.
 */
@Repository
public class TrainingStateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TrainingStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<TrainingSnapshot> load(UUID sessionId) {
        return jdbc.query(
                        "select payload from training_state where state_key = ?",
                        (resultSet, rowNumber) -> decode(resultSet.getString("payload")),
                        sessionId.toString())
                .stream()
                .findFirst();
    }

    @Transactional
    public synchronized void save(UUID sessionId, TrainingSnapshot snapshot) {
        String payload = encode(snapshot);
        int updated = jdbc.update("""
                update training_state
                   set payload = ?, revision = revision + 1, updated_at = current_timestamp
                 where state_key = ?
                """, payload, sessionId.toString());
        if (updated == 0) {
            jdbc.update("insert into training_state (state_key, payload) values (?, ?)",
                    sessionId.toString(), payload);
        }
    }

    public void delete(UUID sessionId) {
        jdbc.update("delete from training_state where state_key = ?", sessionId.toString());
    }

    private String encode(TrainingSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось сериализовать состояние занятия", exception);
        }
    }

    private TrainingSnapshot decode(String payload) {
        try {
            return objectMapper.readValue(payload, TrainingSnapshot.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось прочитать сохранённое состояние занятия", exception);
        }
    }

    public record TrainingSnapshot(
            UUID sessionId,
            UUID lessonId,
            UUID traineeId,
            String mode,
            String lessonKind,
            String workstationNumber,
            String sessionState,
            Instant sessionStartedAt,
            Instant sessionCompletedAt,
            List<String> pendingScenarioIds,
            Map<String, Instant> scheduledScenarioAt,
            List<CardState> cards,
            List<CallState> calls,
            List<CardDraft> drafts
    ) {}

    public record CardState(
            UUID id,
            UUID sessionId,
            String scenarioId,
            String number,
            Instant receivedAt,
            String source,
            String senderLabel,
            String status,
            Instant openingStartedAt,
            Instant openingReadyAt,
            Instant openedAt,
            Instant acceptanceDeadlineAt,
            Instant processingDeadlineAt,
            Instant acceptedAt,
            boolean acceptanceOverdue,
            boolean processingOverdue,
            Caller caller,
            IncidentAddress address,
            String description,
            DictionaryItem incidentType,
            List<String> features,
            List<DictionaryItem> assignedServices,
            ScenarioRequirements requirements,
            List<CallTarget> callTargets,
            List<CardTimelineEntry> timeline,
            List<UUID> callIds,
            List<String> expectedServices,
            String expectedDecision,
            String scenarioTitle
    ) {}

    public record CallState(
            UUID id,
            UUID cardId,
            CallTarget target,
            String state,
            Instant startedAt,
            Instant connectedAt,
            Instant endedAt
    ) {}
}
