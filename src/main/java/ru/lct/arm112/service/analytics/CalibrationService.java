package ru.lct.arm112.service.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.AdminCalibrationState;
import ru.lct.arm112.api.ApiModels.Assessment;
import ru.lct.arm112.api.ApiModels.CalibrationModel;
import ru.lct.arm112.api.ApiModels.CalibrationReport;
import ru.lct.arm112.api.ApiModels.CriterionScore;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.persistence.AssessmentRepository.AssessmentRow;
import ru.lct.arm112.persistence.CalibrationRepository;
import ru.lct.arm112.persistence.CalibrationRepository.ModelRow;
import ru.lct.arm112.persistence.CalibrationRepository.Parameter;
import ru.lct.arm112.service.assessment.AssessmentResult;
import ru.lct.arm112.service.assessment.AssessmentWeights;
import ru.lct.arm112.service.assessment.AssessmentWeights.Criterion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Калибровка оценки ИИ по правкам преподавателя (задача B5 плана).
 *
 * <p>Заказчик потребовал, чтобы приоритет всегда был за преподавателем и чтобы его правки
 * дообучали систему ([q-and-a.md](../../../../../../docs/materials/q-and-a.md), §8). Пары
 * «балл ИИ — балл преподавателя» по критериям уже накапливаются в {@code teacher_payload},
 * но до сих пор никак не использовались.
 *
 * <p>Метод — линейная калибровка на критерий: {@code балл_преп ≈ a × балл_ИИ + b}.
 * Взята намеренно простая форма: заказчик просил не строить «сложную модель с кучей
 * параметров, которую трудно поддерживать». Два коэффициента на критерий читаются
 * преподавателем напрямую: {@code a < 1} — он мягче ИИ на краях, {@code b < 0} — строже в целом.
 *
 * <h2>Что сервис отказывается делать</h2>
 * <ul>
 *   <li>калибровать критерий, по которому меньше {@link #MIN_PAIRS} пар — на трёх правках
 *       коэффициенты означают шум, а не мнение преподавателя;</li>
 *   <li>калибровать, когда исходный балл ИИ не менялся: нулевой разброс по оси X
 *       не даёт наклона;</li>
 *   <li>применять калибровку молча — она возвращается как предложение, а решение
 *       о включении версии остаётся за администратором.</li>
 * </ul>
 */
@Service
public class CalibrationService {
    private static final Logger log = LoggerFactory.getLogger(CalibrationService.class);

    /** Меньше этого числа пар по критерию — калибровка не предлагается (METRICS.md §5.7). */
    static final int MIN_PAIRS = 30;
    /**
     * Если ИИ и преподаватель расходятся меньше чем на столько баллов, калибровать нечего:
     * остаток — разброс самого преподавателя, и подгонка под него только добавит шума.
     */
    static final double NOISE_FLOOR = 3.0;
    /** Ниже этого улучшения калибровка не предлагается: возня не окупается. */
    static final double MIN_IMPROVEMENT = 10.0;
    /** Каждая пятая пара уходит в проверочную часть: улучшение считается вне обучающей выборки. */
    private static final int HOLDOUT_EVERY = 5;
    /** Наклон ограничивается: на краях выборки МНК легко даёт неправдоподобный угол. */
    private static final double MIN_SLOPE = 0.2, MAX_SLOPE = 3.0;

    private final AssessmentRepository assessments;
    private final CalibrationRepository models;

    public CalibrationService(AssessmentRepository assessments, CalibrationRepository models) {
        this.assessments = assessments;
        this.models = models;
    }

    /**
     * Калибровка одного критерия.
     *
     * @param slope      множитель к баллу ИИ
     * @param intercept  сдвиг в баллах
     * @param maeBefore  среднее отклонение балла ИИ от преподавательского до калибровки
     * @param maeAfter   оно же после — если не упало, применять калибровку незачем
     */
    public record CriterionCalibration(String code, String label, double slope, double intercept,
                                       double maeBefore, double maeAfter, int pairs) {
        /** Улучшение в процентах; отрицательное — калибровка делает хуже. */
        public double improvement() {
            return maeBefore == 0 ? 0 : Math.round((1 - maeAfter / maeBefore) * 1000) / 10.0;
        }
    }

    public record Calibration(String mode, List<CriterionCalibration> criteria, int assessments,
                              List<String> skipped) {}

    public Calibration calibrate(String mode) {
        requireMode(mode);
        List<AssessmentRow> rows = assessments.findTeacherAssessed(mode);
        Map<String, List<double[]>> pairs = new LinkedHashMap<>();
        int usableAssessments = 0;
        for (AssessmentRow row : rows) {
            if (row.teacherCriteria() == null) continue;
            boolean usable = false;
            for (CriterionScore teacher : row.teacherCriteria()) {
                if (teacher.score() == null) continue;
                Double ai = AssessmentWeights.scoreOf(row.ai(), teacher.code());
                if (ai == null) continue;
                pairs.computeIfAbsent(teacher.code(), k -> new ArrayList<>())
                        .add(new double[]{ai, teacher.score()});
                usable = true;
            }
            if (usable) usableAssessments++;
        }

        List<CriterionCalibration> result = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Criterion criterion : AssessmentWeights.forMode(mode)) {
            List<double[]> points = pairs.getOrDefault(criterion.code(), List.of());
            if (points.size() < MIN_PAIRS) {
                skipped.add(criterion.code() + ": пар " + points.size() + " из " + MIN_PAIRS);
                continue;
            }
            CriterionCalibration calibration = fit(criterion, points);
            if (calibration == null) {
                skipped.add(criterion.code() + ": балл ИИ не менялся — наклон не определён");
                continue;
            }
            if (calibration.maeBefore() < NOISE_FLOOR) {
                skipped.add(criterion.code() + ": ИИ и преподаватель расходятся на "
                        + calibration.maeBefore() + " балла — калибровать нечего");
                continue;
            }
            if (calibration.improvement() < MIN_IMPROVEMENT) {
                skipped.add(criterion.code() + ": улучшение " + calibration.improvement()
                        + " % не окупает подгонку");
                continue;
            }
            result.add(calibration);
        }

        log.info("Калибровка {}: оценок {}, критериев откалибровано {}, пропущено {}",
                mode, usableAssessments, result.size(), skipped.size());
        return new Calibration(mode, result, usableAssessments, skipped);
    }

    /** Состояние для администратора: активная версия, новый расчёт и история решений. */
    public AdminCalibrationState state(String mode) {
        requireMode(mode);
        Calibration candidate = calibrate(mode);
        return new AdminCalibrationState(mode, models.active(mode).map(this::apiModel).orElse(null),
                report(candidate), models.history(mode).stream().map(this::apiModel).toList());
    }

    /** Создаёт новую версию только при доказанном улучшении на отложенной выборке. */
    @Transactional
    public synchronized AdminCalibrationState activate(String mode, java.util.UUID actorId) {
        requireMode(mode);
        Calibration candidate = calibrate(mode);
        if (candidate.criteria().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "CALIBRATION_NOT_READY",
                    "Недостаточно подтверждённых преподавателями оценок для безопасной коррекции");
        }
        List<Parameter> parameters = candidate.criteria().stream().map(c -> new Parameter(
                c.code(), c.label(), c.slope(), c.intercept(), c.maeBefore(), c.maeAfter(),
                c.improvement(), c.pairs())).toList();
        double before = weighted(candidate.criteria(), true);
        double after = weighted(candidate.criteria(), false);
        models.activate(mode, parameters, candidate.assessments(), before, after, actorId);
        log.info("Администратор {} включил коррекцию {} по {} оценкам", actorId, mode, candidate.assessments());
        return state(mode);
    }

    @Transactional
    public AdminCalibrationState deactivate(String mode, java.util.UUID actorId) {
        requireMode(mode);
        models.deactivate(mode);
        log.info("Администратор {} отключил коррекцию {}", actorId, mode);
        return state(mode);
    }

    /** Результат применения версии; raw остаётся неизменным для будущего обучения и аудита. */
    public record AppliedAssessment(AssessmentResult result, Assessment raw, java.util.UUID modelId,
                                    Integer modelVersion) {}

    public AppliedAssessment applyActive(AssessmentResult rawResult) {
        Assessment raw = rawResult.assessment();
        ModelRow active = models.active(raw.mode()).orElse(null);
        if (active == null) return new AppliedAssessment(rawResult, raw, null, null);

        Map<String, Parameter> parameters = new LinkedHashMap<>();
        active.parameters().forEach(p -> parameters.put(p.code(), p));
        Double timing = corrected(raw, "timing", parameters);
        Double actions = corrected(raw, "actions", parameters);
        Double communication = corrected(raw, "communication", parameters);
        Double language = corrected(raw, "language", parameters);
        Double address = corrected(raw, "address", parameters);
        Double classification = corrected(raw, "classification", parameters);
        Double services = corrected(raw, "services", parameters);
        Assessment provisional = new Assessment(raw.id(), raw.sessionId(), raw.state(), raw.mode(), raw.totalScore(),
                timing, actions, communication, language, address, classification, services,
                raw.syntaxErrors(), raw.issues(), raw.recommendations(), raw.source(), raw.aiTotalScore(),
                null, null, null, List.of(), List.of());
        double total = AssessmentWeights.total(provisional, List.of());
        Assessment calibrated = new Assessment(raw.id(), raw.sessionId(), raw.state(), raw.mode(), total,
                timing, actions, communication, language, address, classification, services,
                raw.syntaxErrors(), raw.issues(), raw.recommendations(), raw.source(), total,
                null, null, null, List.of(), List.of());
        return new AppliedAssessment(new AssessmentResult(calibrated, rawResult.cards()), raw,
                active.id(), active.version());
    }

    private static Double corrected(Assessment assessment, String code, Map<String, Parameter> parameters) {
        Double score = AssessmentWeights.scoreOf(assessment, code);
        Parameter parameter = parameters.get(code);
        if (score == null || parameter == null) return score;
        return round(apply(score, parameter.slope(), parameter.intercept()));
    }

    private static double weighted(List<CriterionCalibration> criteria, boolean before) {
        int pairs = criteria.stream().mapToInt(CriterionCalibration::pairs).sum();
        if (pairs == 0) return 0;
        double sum = criteria.stream().mapToDouble(c -> (before ? c.maeBefore() : c.maeAfter()) * c.pairs()).sum();
        return round(sum / pairs);
    }

    private CalibrationReport report(Calibration value) {
        return new CalibrationReport(value.mode(), value.assessments(), value.criteria().stream()
                .map(c -> new ru.lct.arm112.api.ApiModels.CriterionCalibration(c.code(), c.label(), c.slope(),
                        c.intercept(), c.maeBefore(), c.maeAfter(), c.improvement(), c.pairs()))
                .toList(), value.skipped());
    }

    private CalibrationModel apiModel(ModelRow row) {
        return new CalibrationModel(row.id(), row.mode(), row.version(), row.active(), row.assessments(),
                row.maeBefore(), row.maeAfter(), row.parameters().stream()
                .map(p -> new ru.lct.arm112.api.ApiModels.CriterionCalibration(p.code(), p.label(), p.slope(),
                        p.intercept(), p.maeBefore(), p.maeAfter(), p.improvementPercent(), p.pairs()))
                .toList(), row.createdBy(), row.createdAt(), row.activatedAt(), row.deactivatedAt());
    }

    private static void requireMode(String mode) {
        if (!"CARD_FILL".equals(mode) && !"CARD_ACTIONS".equals(mode)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Режим должен быть CARD_FILL или CARD_ACTIONS");
        }
    }


    /**
     * МНК по паре «балл ИИ — балл преподавателя» с отложенной проверкой.
     *
     * <p>Коэффициенты считаются по обучающей части, а отклонение до и после —
     * по отложенной. Иначе улучшение измерялось бы на тех же точках, по которым подбиралось,
     * и любая подгонка выглядела бы полезной.
     */
    private static CriterionCalibration fit(Criterion criterion, List<double[]> points) {
        List<double[]> train = new ArrayList<>();
        List<double[]> holdout = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            (i % HOLDOUT_EVERY == 0 ? holdout : train).add(points.get(i));
        }
        if (holdout.isEmpty() || train.size() < 2) return null;

        double meanX = train.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double meanY = train.stream().mapToDouble(p -> p[1]).average().orElse(0);
        double covariance = 0, variance = 0;
        for (double[] p : train) {
            covariance += (p[0] - meanX) * (p[1] - meanY);
            variance += (p[0] - meanX) * (p[0] - meanX);
        }
        if (variance < 1e-6) return null; // балл ИИ не менялся — наклон не определён

        double slope = Math.max(MIN_SLOPE, Math.min(MAX_SLOPE, covariance / variance));
        double intercept = meanY - slope * meanX;

        double maeBefore = 0, maeAfter = 0;
        for (double[] p : holdout) {
            maeBefore += Math.abs(p[0] - p[1]);
            maeAfter += Math.abs(apply(p[0], slope, intercept) - p[1]);
        }
        maeBefore /= holdout.size();
        maeAfter /= holdout.size();

        return new CriterionCalibration(criterion.code(), criterion.label(),
                round(slope), round(intercept), round(maeBefore), round(maeAfter), points.size());
    }

    /** Калиброванный балл; шкала остаётся 0–100. */
    public static double apply(double aiScore, double slope, double intercept) {
        return Math.max(0, Math.min(100, aiScore * slope + intercept));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
