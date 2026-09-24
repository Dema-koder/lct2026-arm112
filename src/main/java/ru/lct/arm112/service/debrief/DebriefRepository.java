package ru.lct.arm112.service.debrief;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Готовый разбор занятия; отсутствие строки означает «ещё не готов». */
@Repository
public class DebriefRepository {

    private final JdbcTemplate jdbc;

    public DebriefRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Debrief(UUID assessmentId, String text, String source, Instant createdAt) {}

    /**
     * Сохранить разбор. Перезапись допустима: очередь даёт гарантию «хотя бы один раз»,
     * и задача может выполниться повторно после падения исполнителя.
     */
    public void save(UUID assessmentId, String text, String source) {
        int updated = jdbc.update("""
                update assessment_debrief set text = ?, source = ?, created_at = current_timestamp
                 where assessment_id = ?
                """, text, source, assessmentId);
        if (updated == 0) {
            jdbc.update("insert into assessment_debrief (assessment_id, text, source) values (?, ?, ?)",
                    assessmentId, text, source);
        }
    }

    public Optional<Debrief> find(UUID assessmentId) {
        return jdbc.query("select * from assessment_debrief where assessment_id = ?",
                (rs, row) -> new Debrief(rs.getObject("assessment_id", UUID.class),
                        rs.getString("text"), rs.getString("source"),
                        rs.getTimestamp("created_at").toInstant()),
                assessmentId).stream().findFirst();
    }
}
