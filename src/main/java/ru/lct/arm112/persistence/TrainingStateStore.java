package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.arm112.api.ApiModels.*;
import ru.lct.arm112.api.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Снимок состояния одной персональной сессии: карточки, звонки, черновики и очередь сценариев.
 * Ключ строки — идентификатор сессии; сессии из таблицы training_session.
 */
@Repository
public class TrainingStateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Map<UUID, Long> revisions = new ConcurrentHashMap<>();

    public TrainingStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<TrainingSnapshot> load(UUID sessionId) {
        return jdbc.query(
                        "select payload, revision from training_state where state_key = ?",
                        (resultSet, rowNumber) -> {
                            revisions.put(sessionId, resultSet.getLong("revision"));
                            return decode(resultSet.getString("payload"));
                        },
                        sessionId.toString())
                .stream()
                .findFirst();
    }

    @Transactional
    public synchronized void save(UUID sessionId, TrainingSnapshot snapshot) {
        String payload = encode(snapshot);
        Long expected = revisions.get(sessionId);
        if (expected == null) {
            try {
                jdbc.update("insert into training_state (state_key, payload, revision) values (?, ?, 1)",
                        sessionId.toString(), payload);
                revisions.put(sessionId, 1L);
                return;
            } catch (DuplicateKeyException exception) {
                throw conflict(sessionId, null);
            }
        }
        int updated = jdbc.update("""
                update training_state
                   set payload = ?, revision = revision + 1, updated_at = current_timestamp
                 where state_key = ? and revision = ?
                """, payload, sessionId.toString(), expected);
        if (updated == 0) {
            throw conflict(sessionId, expected);
        }
        revisions.put(sessionId, expected + 1);
    }

    public void delete(UUID sessionId) {
        jdbc.update("delete from training_state where state_key = ?", sessionId.toString());
        revisions.remove(sessionId);
    }

    public void resetRevisionTracking() {
        revisions.clear();
    }

    private ApiException conflict(UUID sessionId, Long expected) {
        Long actual = jdbc.query("select revision from training_state where state_key = ?",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null, sessionId.toString());
        return new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT",
                "Состояние занятия было изменено другим экземпляром приложения",
                List.of(), Map.of("sessionId", sessionId.toString(), "expectedRevision",
                        expected == null ? 0 : expected, "actualRevision", actual == null ? 0 : actual));
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
            List<CardState> cards,
            List<CallState> calls,
            List<CardDraft> drafts,
            // поля потока (V8): отсутствуют в старых снимках — restore() подставляет значения по умолчанию
            String ownServiceCode,
            String intensity,
            Instant nextArrivalAt,
            List<IncomingCallState> callQueue,
            IncomingCallState ringing,
            List<String> lost,
            Integer missedCalls,
            List<JournalRow> background,
            // траектория заполнения (V11): в старых снимках отсутствует — restore() подставляет пустой список
            List<DraftTrajectory> trajectories
    ) {}

    /** Входящий вызов оператору 112: в очереди (ringingSince == null) или звонит. */
    public record IncomingCallState(
            UUID id,
            String scenarioId,
            String phone,
            String callerName,
            Instant arrivedAt,
            Instant ringingSince,
            int missedCount
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

    /**
     * Как заполнялся черновик, а не только сколько это заняло всего.
     *
     * <p>Сессионного времени «сохранено минус начато» мало: оно говорит, что обучающийся
     * не уложился, но не говорит где именно. Эти отметки отвечают на вопрос «на чём встал» —
     * на поиске адреса, на выборе типа или на колебаниях между типами.
     *
     * @param firstAddressAt когда впервые введена улица
     * @param firstTypeAt    когда впервые выбран тип происшествия
     * @param typeChanges    сколько раз набор типов менялся после первого выбора
     * @param updates        сколько всего правок пришло по черновику
     * @param idleSeconds    суммарные паузы без правок дольше порога
     * @param lastUpdateAt   отметка предыдущей правки — по ней считается пауза
     */
    public record DraftTrajectory(
            UUID draftId,
            Instant firstAddressAt,
            Instant firstTypeAt,
            int typeChanges,
            int updates,
            long idleSeconds,
            Instant lastUpdateAt
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
