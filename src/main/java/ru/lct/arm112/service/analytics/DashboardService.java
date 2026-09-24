package ru.lct.arm112.service.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiModels.*;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.persistence.AssessmentRepository.AssessmentRow;
import ru.lct.arm112.persistence.AssessmentRepository.IssueCount;
import ru.lct.arm112.persistence.ScenarioRepository;
import ru.lct.arm112.persistence.UserRepository;
import ru.lct.arm112.service.RatingService;
import ru.lct.arm112.service.UserService;
import ru.lct.arm112.service.assessment.AssessmentWeights;
import ru.lct.arm112.service.assessment.AssessmentWeights.Criterion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Сборка данных для экранов разбора (DASHBOARDS.md §3.3, §3.4, §3.6).
 *
 * <p>Сервисы оценки, Раша, калибровки и временного анализа написаны по отдельности;
 * здесь они сводятся в то, что показывает один экран. Логики оценивания тут нет —
 * только выборка и приведение к виду, удобному интерфейсу.
 *
 * <p><b>Почему потеря в баллах, а не процент.</b> Экран сортируется по тому, сколько
 * критерий стоил группе: время 61 при весе 15 отнимает 5.9 балла, адрес 72 при весе 40 —
 * 11.2. По проценту разбирать надо было бы время, по потере — адрес. Верно второе.
 */
@Service
public class DashboardService {

    private final JdbcTemplate jdbc;
    private final AssessmentRepository assessments;
    private final ScenarioRepository scenarios;
    private final UserRepository users;
    private final RatingService ratings;
    private final TimingAnalyticsService timings;
    private final RaschService rasch;
    private final CalibrationService calibration;

    public DashboardService(JdbcTemplate jdbc, AssessmentRepository assessments,
                            ScenarioRepository scenarios, UserRepository users, RatingService ratings,
                            TimingAnalyticsService timings, RaschService rasch,
                            CalibrationService calibration) {
        this.jdbc = jdbc;
        this.assessments = assessments;
        this.scenarios = scenarios;
        this.users = users;
        this.ratings = ratings;
        this.timings = timings;
        this.rasch = rasch;
        this.calibration = calibration;
    }

    // ---------------------------------------------------------------- обзор занятия

    public LessonOverview lessonOverview(Lesson lesson) {
        List<AssessmentRow> rows = assessments.findByLesson(lesson.id());
        List<Double> totals = rows.stream().map(AssessmentRow::finalTotal).sorted().toList();

        List<CriterionSummary> criteria = criteriaOf(lesson.mode(), code -> average(rows, code));
        List<IssueSummary> topIssues = assessments.countByLesson(lesson.id()).stream()
                .map(c -> new IssueSummary(c.code(), c.severity(), messageOf(lesson.id(), c.code()),
                        c.total(), c.group()))
                .toList();
        int critical = topIssues.stream()
                .filter(i -> "CRITICAL".equals(i.severity()))
                .mapToInt(IssueSummary::occurrences).sum();

        return new LessonOverview(lesson, rows.size(), median(totals), spread(totals),
                inNormPercent(lesson.id()), critical, criteria, topIssues, byScenario(lesson.id()));
    }

    /** Доля карточек, уложившихся в норматив: балл по времени равен 100. */
    private Double inNormPercent(UUID lessonId) {
        return jdbc.queryForObject("""
                select case when count(*) = 0 then null
                            else round(100.0 * sum(case when timing >= 100 then 1 else 0 end) / count(*), 1)
                       end
                  from assessment_card where lesson_id = ?
                """, Double.class, lessonId);
    }

    /** Ошибки по сценариям: отделяет плохой сценарий от неусвоенной темы. */
    private List<ScenarioIssues> byScenario(UUID lessonId) {
        return jdbc.query("""
                select scenario_id, count(*) as cards,
                       sum(case when issues > 0 then 1 else 0 end) as with_issues
                  from assessment_card
                 where lesson_id = ? and scenario_id is not null
                 group by scenario_id
                 order by sum(case when issues > 0 then 1 else 0 end) desc
                """, (rs, row) -> {
            String id = rs.getString("scenario_id");
            String title = scenarios.findById(id).map(Scenario::title).orElse(id);
            return new ScenarioIssues(id, title, rs.getInt("cards"), rs.getInt("with_issues"));
        }, lessonId);
    }

    // ---------------------------------------------------------------- профиль обучающегося

    public TraineeProfile traineeProfile(UUID traineeId) {
        UserRepository.AppUser user = users.findById(traineeId)
                .orElseThrow(() -> new IllegalArgumentException("Обучающийся не найден"));
        List<AssessmentRow> rows = assessments.findByTrainee(traineeId);
        String mode = rows.isEmpty() ? "CARD_FILL" : rows.get(rows.size() - 1).mode();

        List<TimingMetric> timing = user.groupId() == null ? List.of()
                : timings.forTrainee(traineeId, user.groupId()).stream()
                .map(m -> new TimingMetric(m.code(), m.label(), m.value(), m.groupMedian(),
                        m.percentile(), m.hint()))
                .toList();

        return new TraineeProfile(UserService.toUser(user),
                ratings.traineeRating(traineeId, user.groupId()),
                criteriaOf(mode, code -> average(rows, code)),
                timing, persistentIssues(traineeId, rows.size()), progress(traineeId), coverage(traineeId));
    }

    /**
     * Недочёты, повторяющиеся из занятия в занятие.
     *
     * <p>Главное здесь не частота, а <b>устойчивость</b>: случайная ошибка и закрепившаяся
     * привычка требуют разного. Поэтому считается, в скольких занятиях код встретился,
     * и отдельно — встречается ли он в последних двух.
     */
    private List<PersistentIssue> persistentIssues(UUID traineeId, int totalLessons) {
        List<IssueCount> counts = assessments.countByTrainee(traineeId);
        List<PersistentIssue> result = new ArrayList<>();
        for (IssueCount count : counts) {
            Integer recent = jdbc.queryForObject("""
                    select count(distinct i.lesson_id) from assessment_issue i
                     where i.trainee_id = ? and i.code = ?
                       and i.lesson_id in (
                           select lesson_id from (
                               select distinct s.lesson_id, max(a.created_at) as last_at
                                 from assessment a join training_session s on s.id = a.session_id
                                where s.trainee_id = ? group by s.lesson_id
                                order by max(a.created_at) desc limit 2) recent)
                    """, Integer.class, traineeId, count.code(), traineeId);
            String trend = recent != null && recent > 0
                    ? (count.group() >= Math.max(2, totalLessons - 1) ? "PERSISTENT" : "IMPROVING")
                    : "RESOLVED";
            result.add(new PersistentIssue(count.code(), count.severity(),
                    messageOfTrainee(traineeId, count.code()), count.group(), count.total(), trend));
        }
        return result;
    }

    /** Кривая обучения: итог по номеру занятия. */
    private List<ProgressPoint> progress(UUID traineeId) {
        List<ProgressPoint> points = new ArrayList<>();
        List<Object[]> rows = jdbc.query("""
                select l.id, l.title, l.kind, coalesce(a.teacher_total, a.ai_total) as total, a.created_at
                  from assessment a
                  join training_session s on s.id = a.session_id
                  join lesson l on l.id = s.lesson_id
                 where s.trainee_id = ?
                 order by a.created_at
                """, (rs, row) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getDouble(4), rs.getTimestamp(5).toInstant()}, traineeId);
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            points.add(new ProgressPoint(i + 1, (UUID) r[0], (String) r[1], (String) r[2],
                    (Double) r[3], (java.time.Instant) r[4]));
        }
        return points;
    }

    /** Покрытие категорий: пробелы в подготовке видны сразу. */
    private List<CategoryCoverage> coverage(UUID traineeId) {
        return jdbc.query("""
                select sc.category, count(*) as cards
                  from assessment_card c join scenario sc on sc.id = c.scenario_id
                 where c.trainee_id = ?
                 group by sc.category order by count(*) desc
                """, (rs, row) -> new CategoryCoverage(rs.getString("category"), rs.getInt("cards")),
                traineeId);
    }

    // ---------------------------------------------------------------- качество библиотеки

    public List<ScenarioQuality> scenarioQuality() {
        RaschService.Estimate estimate = rasch.estimate();
        List<ScenarioQuality> result = new ArrayList<>();
        for (RaschService.ScenarioDifficulty d : estimate.scenarios()) {
            Scenario scenario = scenarios.findById(d.scenarioId()).orElse(null);
            if (scenario == null) continue;
            result.add(new ScenarioQuality(d.scenarioId(), scenario.title(), scenario.category(),
                    scenario.difficulty(), d.suggested(), d.difficulty(), d.standardError(),
                    d.observations(), d.passed(), discrimination(d.scenarioId()),
                    scenario.referenceConfirmed()));
        }
        return result;
    }

    /**
     * Дискриминативность: связь балла за сценарий с итогом сессии.
     *
     * <p>Около нуля означает, что сценарий решают одинаково и сильные, и слабые, —
     * такой сценарий ничего не измеряет, сколько бы раз его ни давали.
     */
    private Double discrimination(String scenarioId) {
        return jdbc.queryForObject("""
                select case when count(*) < 5 then null else
                  (count(*) * sum(c.timing * a.ai_total) - sum(c.timing) * sum(a.ai_total)) /
                  nullif(sqrt((count(*) * sum(c.timing * c.timing) - sum(c.timing) * sum(c.timing)) *
                              (count(*) * sum(a.ai_total * a.ai_total) - sum(a.ai_total) * sum(a.ai_total))), 0)
                end
                  from assessment_card c join assessment a on a.id = c.assessment_id
                 where c.scenario_id = ? and c.timing is not null
                """, Double.class, scenarioId);
    }

    // ---------------------------------------------------------------- калибровка

    public CalibrationReport calibrationReport(String mode) {
        CalibrationService.Calibration result = calibration.calibrate(mode);
        return new CalibrationReport(result.mode(), result.assessments(),
                result.criteria().stream()
                        .map(c -> new CriterionCalibration(c.code(), c.label(), c.slope(), c.intercept(),
                                c.maeBefore(), c.maeAfter(), c.improvement(), c.pairs()))
                        .toList(),
                result.skipped());
    }

    // ---------------------------------------------------------------- общее

    private interface Scores {
        Double average(String code);
    }

    /** Критерии режима с потерей в баллах, по убыванию потери. */
    private static List<CriterionSummary> criteriaOf(String mode, Scores scores) {
        List<CriterionSummary> result = new ArrayList<>();
        for (Criterion criterion : AssessmentWeights.forMode(mode)) {
            Double average = scores.average(criterion.code());
            Double lost = average == null ? null
                    : Math.round((100 - average) * criterion.weight() / 100.0 * 10) / 10.0;
            result.add(new CriterionSummary(criterion.code(), criterion.label(),
                    average, criterion.weight(), lost));
        }
        result.sort(Comparator.comparing(
                (CriterionSummary c) -> c.lostPoints() == null ? -1 : c.lostPoints()).reversed());
        return result;
    }

    private static Double average(List<AssessmentRow> rows, String code) {
        List<Double> values = rows.stream()
                .map(r -> AssessmentWeights.scoreOf(r.ai(), code))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (values.isEmpty()) return null;
        return Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
    }

    private String messageOf(UUID lessonId, String code) {
        return jdbc.query("select message from assessment_issue where lesson_id = ? and code = ? limit 1",
                (rs, row) -> rs.getString(1), lessonId, code).stream().findFirst().orElse(code);
    }

    private String messageOfTrainee(UUID traineeId, String code) {
        return jdbc.query("select message from assessment_issue where trainee_id = ? and code = ? limit 1",
                (rs, row) -> rs.getString(1), traineeId, code).stream().findFirst().orElse(code);
    }

    private static Double median(List<Double> sorted) {
        if (sorted.isEmpty()) return null;
        int middle = sorted.size() / 2;
        double value = sorted.size() % 2 == 1 ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        return Math.round(value * 10) / 10.0;
    }

    /** Разброс итогов: по нему видно, провалилась группа или два человека. */
    private static Double spread(List<Double> sorted) {
        if (sorted.size() < 2) return null;
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = sorted.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / sorted.size();
        return Math.round(Math.sqrt(variance) * 10) / 10.0;
    }
}
