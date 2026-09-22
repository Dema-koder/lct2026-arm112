package ru.lct.arm112.service.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiModels.CriterionScore;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.persistence.AssessmentRepository.AssessmentRow;
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
 *   <li>калибровать, когда преподаватель не менял балл ни разу: нулевой разброс по оси X
 *       не даёт наклона;</li>
 *   <li>применять калибровку молча — она возвращается как предложение, а решение
 *       остаётся за преподавателем.</li>
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

    public CalibrationService(AssessmentRepository assessments) {
        this.assessments = assessments;
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
        List<AssessmentRow> rows = assessments.findTeacherAssessed(mode);
        Map<String, List<double[]>> pairs = new LinkedHashMap<>();
        for (AssessmentRow row : rows) {
            if (row.teacherCriteria() == null) continue;
            for (CriterionScore teacher : row.teacherCriteria()) {
                if (teacher.score() == null) continue;
                Double ai = AssessmentWeights.scoreOf(row.ai(), teacher.code());
                if (ai == null) continue;
                pairs.computeIfAbsent(teacher.code(), k -> new ArrayList<>())
                        .add(new double[]{ai, teacher.score()});
            }
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
                mode, rows.size(), result.size(), skipped.size());
        return new Calibration(mode, result, rows.size(), skipped);
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
