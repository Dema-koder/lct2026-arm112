package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Персональные занятия обучающихся (решение №1). */
@Repository
public class SessionRepository {
    private static final RowMapper<SessionRow> MAPPER = (rs, row) -> new SessionRow(
            rs.getObject("id", UUID.class), rs.getObject("lesson_id", UUID.class),
            rs.getObject("trainee_id", UUID.class), rs.getString("workstation_number"), rs.getString("state"),
            instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")));

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SessionRow> findById(UUID id) {
        return jdbc.query("select * from training_session where id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<SessionRow> findActiveByTrainee(UUID traineeId) {
        return jdbc.query("select * from training_session where trainee_id = ? and state = 'ACTIVE' order by started_at desc",
                MAPPER, traineeId).stream().findFirst();
    }

    public List<SessionRow> findByTrainee(UUID traineeId) {
        return jdbc.query("select * from training_session where trainee_id = ? order by created_at desc", MAPPER, traineeId);
    }

    public List<SessionRow> findByLesson(UUID lessonId) {
        return jdbc.query("select * from training_session where lesson_id = ? order by workstation_number, created_at",
                MAPPER, lessonId);
    }

    public List<SessionRow> findByState(String state) {
        return jdbc.query("select * from training_session where state = ?", MAPPER, state);
    }

    public void insert(SessionRow row) {
        jdbc.update("""
                insert into training_session (id, lesson_id, trainee_id, workstation_number, state, started_at)
                values (?, ?, ?, ?, ?, ?)
                """, row.id(), row.lessonId(), row.traineeId(), row.workstationNumber(), row.state(),
                row.startedAt() == null ? null : Timestamp.from(row.startedAt()));
    }

    public void setState(UUID id, String state, Instant completedAt) {
        jdbc.update("update training_session set state = ?, completed_at = coalesce(?, completed_at) where id = ?",
                state, completedAt == null ? null : Timestamp.from(completedAt), id);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record SessionRow(UUID id, UUID lessonId, UUID traineeId, String workstationNumber, String state,
                             Instant startedAt, Instant completedAt) {}
}
