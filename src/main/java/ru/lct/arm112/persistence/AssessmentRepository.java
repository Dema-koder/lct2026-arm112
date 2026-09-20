package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.lct.arm112.api.ApiModels.Assessment;
import ru.lct.arm112.api.ApiModels.CriterionScore;
import ru.lct.arm112.service.assessment.AssessmentWeights;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Оценка ИИ (JSON) и оценка преподавателя рядом; итог выбирается при чтении (решение №5). */
@Repository
public class AssessmentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<AssessmentRow> mapper = this::map;

    public AssessmentRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<AssessmentRow> findById(UUID id) {
        return jdbc.query("select * from assessment where id = ?", mapper, id).stream().findFirst();
    }

    public Optional<AssessmentRow> findBySession(UUID sessionId) {
        return jdbc.query("select * from assessment where session_id = ? order by created_at desc", mapper, sessionId)
                .stream().findFirst();
    }

    public List<AssessmentRow> findByLesson(UUID lessonId) {
        return jdbc.query("""
                select a.* from assessment a join training_session s on s.id = a.session_id
                 where s.lesson_id = ? order by a.created_at
                """, mapper, lessonId);
    }

    public void insert(Assessment ai, UUID sessionId) {
        jdbc.update("""
                insert into assessment (id, session_id, mode, ai_payload, ai_total, timing_score, language_score, syntax_errors)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, ai.id(), sessionId, ai.mode(), encode(ai), ai.totalScore(), ai.timingScore(),
                ai.languageScore(), ai.syntaxErrors() == null ? 0 : ai.syntaxErrors());
    }

    public void setTeacher(UUID id, UUID teacherId, double total, String comment, List<CriterionScore> criteria) {
        jdbc.update("""
                update assessment set teacher_id = ?, teacher_total = ?, teacher_comment = ?,
                                      teacher_payload = ?, teacher_assessed_at = current_timestamp
                 where id = ?
                """, teacherId, total, comment, encodeCriteria(criteria), id);
    }

    /**
     * Итоговая оценка по решению №5: преподавателя, если она есть, иначе ИИ.
     * Баллы по критериям тоже подменяются оценкой преподавателя там, где она задана;
     * исходные баллы ИИ остаются в aiCriteria.
     */
    public static Assessment finalOf(AssessmentRow row) {
        Assessment ai = row.ai();
        boolean teacher = row.teacherTotal() != null;
        List<CriterionScore> teacherCriteria = row.teacherCriteria() == null ? List.of() : row.teacherCriteria();
        Map<String, Double> overrides = new HashMap<>();
        for (CriterionScore c : teacherCriteria) if (c.score() != null) overrides.put(c.code(), c.score());
        return new Assessment(ai.id(), ai.sessionId(), ai.state(), ai.mode(),
                teacher ? row.teacherTotal() : ai.totalScore(),
                overrides.getOrDefault("timing", ai.timingScore()),
                overrides.getOrDefault("actions", ai.actionsScore()),
                overrides.getOrDefault("communication", ai.communicationScore()),
                overrides.getOrDefault("language", ai.languageScore()),
                overrides.getOrDefault("address", ai.addressScore()),
                overrides.getOrDefault("classification", ai.classificationScore()),
                overrides.getOrDefault("services", ai.servicesScore()),
                ai.syntaxErrors(), ai.issues(), ai.recommendations(), teacher ? "TEACHER" : "AI",
                ai.totalScore(), row.teacherTotal(), row.teacherComment(), row.teacherAssessedAt(),
                AssessmentWeights.aiCriteria(ai), teacherCriteria);
    }

    private AssessmentRow map(ResultSet rs, int row) throws SQLException {
        Timestamp assessedAt = rs.getTimestamp("teacher_assessed_at");
        BigDecimal teacherTotal = rs.getBigDecimal("teacher_total");
        return new AssessmentRow(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getString("mode"), decode(rs.getString("ai_payload")),
                rs.getBigDecimal("ai_total").doubleValue(),
                rs.getObject("timing_score") == null ? null : rs.getBigDecimal("timing_score").doubleValue(),
                rs.getObject("language_score") == null ? null : rs.getBigDecimal("language_score").doubleValue(),
                rs.getInt("syntax_errors"), rs.getObject("teacher_id", UUID.class),
                teacherTotal == null ? null : teacherTotal.doubleValue(), rs.getString("teacher_comment"),
                decodeCriteria(rs.getString("teacher_payload")),
                assessedAt == null ? null : assessedAt.toInstant(), rs.getTimestamp("created_at").toInstant());
    }

    private String encodeCriteria(List<CriterionScore> criteria) {
        try {
            return objectMapper.writeValueAsString(criteria == null ? List.of() : criteria);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось сохранить оценку по критериям", exception);
        }
    }

    private List<CriterionScore> decodeCriteria(String payload) {
        if (payload == null || payload.isBlank()) return List.of();
        try {
            return objectMapper.readValue(payload,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CriterionScore.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось прочитать оценку по критериям", exception);
        }
    }

    private String encode(Assessment value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось сохранить оценку", exception);
        }
    }

    private Assessment decode(String payload) {
        try {
            return objectMapper.readValue(payload, Assessment.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось прочитать оценку", exception);
        }
    }

    public record AssessmentRow(UUID id, UUID sessionId, String mode, Assessment ai, double aiTotal,
                                Double timingScore, Double languageScore, int syntaxErrors,
                                UUID teacherId, Double teacherTotal, String teacherComment,
                                List<CriterionScore> teacherCriteria,
                                Instant teacherAssessedAt, Instant createdAt) {
        public double finalTotal() {
            return teacherTotal != null ? teacherTotal : aiTotal;
        }
    }
}
