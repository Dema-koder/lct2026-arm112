package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.arm112.api.ApiModels.Assessment;
import ru.lct.arm112.api.ApiModels.AssessmentIssue;
import ru.lct.arm112.api.ApiModels.CriterionScore;
import ru.lct.arm112.service.assessment.AssessmentResult;
import ru.lct.arm112.service.assessment.AssessmentResult.CardBreakdown;
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
    private static final Logger log = LoggerFactory.getLogger(AssessmentRepository.class);

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

    /**
     * Оценка целиком (JSON) плюс её замечания отдельными строками для агрегатов дашбордов.
     *
     * @param cardScenarios карточка или черновик → сценарий: у замечания есть только cardId,
     *                      а разрез «ошибки по сценариям» нужен в отчёте занятия
     */
    public void insert(AssessmentResult result, UUID sessionId, UUID lessonId, UUID traineeId,
                       Map<UUID, String> cardScenarios) {
        Assessment ai = result.assessment();
        long startedAt = System.nanoTime();
        jdbc.update("""
                insert into assessment (id, session_id, mode, ai_payload, ai_total, timing_score, language_score, syntax_errors)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, ai.id(), sessionId, ai.mode(), encode(ai), ai.totalScore(), ai.timingScore(),
                ai.languageScore(), ai.syntaxErrors() == null ? 0 : ai.syntaxErrors());

        List<AssessmentIssue> issues = ai.issues() == null ? List.of() : ai.issues();
        if (!issues.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into assessment_issue (id, assessment_id, session_id, lesson_id, trainee_id, mode,
                                                  card_id, scenario_id, code, severity, message, expected, actual)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, issues.stream().map(issue -> new Object[]{
                    UUID.randomUUID(), ai.id(), sessionId, lessonId, traineeId, ai.mode(),
                    issue.cardId(), issue.cardId() == null ? null : cardScenarios.get(issue.cardId()),
                    issue.code(), issue.severity(), issue.message(),
                    text(issue.expected()), text(issue.actual())}).toList());
        }

        List<CardBreakdown> cards = result.cards();
        if (!cards.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into assessment_card (id, assessment_id, session_id, lesson_id, trainee_id, mode,
                                                 card_id, scenario_id, address, classification, services, timing,
                                                 language, actions, communication, spent_seconds, issues, critical_issues)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, cards.stream().map(card -> new Object[]{
                    UUID.randomUUID(), ai.id(), sessionId, lessonId, traineeId, ai.mode(),
                    card.cardId(), card.scenarioId() != null ? card.scenarioId() : cardScenarios.get(card.cardId()),
                    card.address(), card.classification(), card.services(), card.timing(), card.language(),
                    card.actions(), card.communication(), card.spentSeconds(),
                    card.issues(), card.criticalIssues()}).toList());
        }

        long spentMs = (System.nanoTime() - startedAt) / 1_000_000;
        // Одна строка на завершённую сессию: по ней собирается статистика оценки без разбора JSON.
        log.info("assessment saved: session={} mode={} total={} cards={} issues={} critical={} syntaxErrors={} spentMs={}",
                sessionId, ai.mode(), ai.totalScore(), cards.size(), issues.size(),
                issues.stream().filter(i -> "CRITICAL".equals(i.severity())).count(),
                ai.syntaxErrors() == null ? 0 : ai.syntaxErrors(), spentMs);
    }

    /** Замечания одной оценки — для экрана разбора и агрегатов. */
    public List<IssueRow> findIssues(UUID assessmentId) {
        return jdbc.query("select * from assessment_issue where assessment_id = ? order by created_at",
                (rs, row) -> new IssueRow(
                        rs.getObject("id", UUID.class), rs.getObject("assessment_id", UUID.class),
                        rs.getObject("lesson_id", UUID.class), rs.getObject("trainee_id", UUID.class),
                        rs.getString("mode"), rs.getObject("card_id", UUID.class), rs.getString("scenario_id"),
                        rs.getString("code"), rs.getString("severity"), rs.getString("message"),
                        rs.getString("expected"), rs.getString("actual"),
                        rs.getTimestamp("created_at").toInstant()),
                assessmentId);
    }

    /** Частота кодов замечаний по занятию — «типовые ошибки» в отчёте (DASHBOARDS.md §3.3). */
    public List<IssueCount> countByLesson(UUID lessonId) {
        return jdbc.query("""
                select code, severity, count(*) as total, count(distinct trainee_id) as trainees
                  from assessment_issue where lesson_id = ?
                 group by code, severity order by count(distinct trainee_id) desc, count(*) desc
                """, (rs, row) -> new IssueCount(rs.getString("code"), rs.getString("severity"),
                rs.getInt("total"), rs.getInt("trainees")), lessonId);
    }

    /** Замечания обучающегося по занятиям — «устойчивые недочёты» в профиле (DASHBOARDS.md §3.4). */
    public List<IssueCount> countByTrainee(UUID traineeId) {
        return jdbc.query("""
                select code, severity, count(*) as total, count(distinct lesson_id) as lessons
                  from assessment_issue where trainee_id = ?
                 group by code, severity order by count(distinct lesson_id) desc, count(*) desc
                """, (rs, row) -> new IssueCount(rs.getString("code"), rs.getString("severity"),
                rs.getInt("total"), rs.getInt("lessons")), traineeId);
    }

    /** Исходы по карточкам для матрицы «обучающийся × сценарий» (оценка сложности по Рашу). */
    public List<CardOutcome> findCardOutcomes() {
        return jdbc.query("""
                select trainee_id, scenario_id, mode, address, classification, services,
                       timing, language, actions, communication
                  from assessment_card where scenario_id is not null
                """, (rs, row) -> new CardOutcome(
                rs.getObject("trainee_id", UUID.class), rs.getString("scenario_id"), rs.getString("mode"),
                value(rs.getBigDecimal("address")), value(rs.getBigDecimal("classification")),
                value(rs.getBigDecimal("services")), value(rs.getBigDecimal("timing")),
                value(rs.getBigDecimal("language")), value(rs.getBigDecimal("actions")),
                value(rs.getBigDecimal("communication"))));
    }

    public record CardOutcome(UUID traineeId, String scenarioId, String mode,
                              Double address, Double classification, Double services,
                              Double timing, Double language, Double actions, Double communication) {}

    private static Double value(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    public record IssueRow(UUID id, UUID assessmentId, UUID lessonId, UUID traineeId, String mode,
                           UUID cardId, String scenarioId, String code, String severity,
                           String message, String expected, String actual, Instant createdAt) {}

    /** Код замечания и его частота; {@code group} — число обучающихся или занятий в зависимости от запроса. */
    public record IssueCount(String code, String severity, int total, int group) {}

    private static String text(Object value) {
        if (value == null) return null;
        String result = value instanceof List<?> list ? String.join(", ", list.stream().map(String::valueOf).toList())
                : String.valueOf(value);
        return result.length() > 2000 ? result.substring(0, 2000) : result;
    }

    /** Оценки, которые преподаватель правил по критериям — вход для калибровки. */
    public List<AssessmentRow> findTeacherAssessed(String mode) {
        return jdbc.query("""
                select * from assessment
                 where mode = ? and teacher_assessed_at is not null and teacher_payload is not null
                 order by teacher_assessed_at
                """, mapper, mode);
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
