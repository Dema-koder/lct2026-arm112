package ru.lct.arm112.service.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.persistence.AssessmentRepository.CardOutcome;
import ru.lct.arm112.service.assessment.AssessmentWeights;
import ru.lct.arm112.service.assessment.AssessmentWeights.Criterion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Эмпирическая сложность сценариев по однопараметрической модели Раша (задача B4 плана).
 *
 * <p>Зачем: поле {@code Scenario.difficulty} проставляется преподавателем вручную и ни на что
 * не влияет, а заказчик просил, чтобы система сама предлагала вес задания
 * ([q-and-a.md](../../../../../../docs/materials/q-and-a.md), §19). Модель Раша даёт сложность
 * сценария и способность обучающегося на одной шкале, поэтому их можно сравнивать между собой.
 *
 * <p>Модель: вероятность справиться со сценарием равна {@code 1 / (1 + exp(-(θ - b)))},
 * где θ — способность обучающегося, b — сложность сценария. Оценка параметров —
 * совместным методом максимального правдоподобия (JMLE): по очереди уточняются θ при
 * фиксированных b и наоборот, пока шаг не станет меньше порога.
 *
 * <p><b>Что такое «справился».</b> Карточка сводится к бинарному исходу: взвешенный по весам
 * режима балл карточки не ниже {@link #PASS_SCORE}. Непрерывный балл в классическую модель
 * Раша не подаётся сознательно — она определена для дихотомии, а частичные модели требуют
 * на порядок больше данных.
 *
 * <p><b>Ограничения, которые сервис соблюдает сам.</b> Сценарии и обучающиеся с числом
 * наблюдений меньше порога исключаются; крайние случаи (справился со всем или ни с чем)
 * не дают конечной оценки и тоже исключаются — иначе θ уходит в бесконечность.
 */
@Service
public class RaschService {
    private static final Logger log = LoggerFactory.getLogger(RaschService.class);

    /** Балл карточки, начиная с которого считается, что обучающийся со сценарием справился. */
    static final double PASS_SCORE = 70.0;
    /** Меньше этого числа наблюдений — оценка сложности не выдаётся (METRICS.md §5.6). */
    static final int MIN_OBSERVATIONS = 10;
    private static final int MAX_ITERATIONS = 200;
    private static final double CONVERGENCE = 1e-4;
    /** Ограничение шага: без него первые итерации на разреженной матрице «выстреливают» в бесконечность. */
    private static final double MAX_STEP = 1.0;

    private final AssessmentRepository assessments;

    public RaschService(AssessmentRepository assessments) {
        this.assessments = assessments;
    }

    /**
     * Сложность сценария на шкале логитов и её погрешность.
     *
     * @param difficulty больше нуля — сценарий труднее среднего
     * @param standardError чем шире, тем меньше данных; при широком интервале преподавателю
     *                      показывается «данных мало», а не уверенная цифра
     * @param suggested     та же сложность, приведённая к шкале 1–10 для поля {@code Scenario.difficulty}
     */
    public record ScenarioDifficulty(String scenarioId, double difficulty, double standardError,
                                     int observations, int passed, int suggested) {}

    /** Способность обучающегося на той же шкале, что и сложность сценариев. */
    public record TraineeAbility(UUID traineeId, double ability, double standardError,
                                 int observations, int passed) {}

    public record Estimate(List<ScenarioDifficulty> scenarios, List<TraineeAbility> trainees,
                           int iterations, boolean converged) {}

    /** Оценка по всем накопленным карточкам. */
    public Estimate estimate() {
        return estimate(assessments.findCardOutcomes());
    }

    Estimate estimate(List<CardOutcome> outcomes) {
        // --- дихотомизация и отсев редких сценариев и обучающихся
        List<boolean[]> ignored = new ArrayList<>();
        Map<String, List<Boolean>> byScenario = new LinkedHashMap<>();
        Map<UUID, List<Boolean>> byTrainee = new LinkedHashMap<>();
        List<Observation> observations = new ArrayList<>();
        for (CardOutcome outcome : outcomes) {
            if (outcome.scenarioId() == null) continue;
            Double composite = composite(outcome);
            if (composite == null) continue;
            boolean passed = composite >= PASS_SCORE;
            observations.add(new Observation(outcome.traineeId(), outcome.scenarioId(), passed));
            byScenario.computeIfAbsent(outcome.scenarioId(), k -> new ArrayList<>()).add(passed);
            byTrainee.computeIfAbsent(outcome.traineeId(), k -> new ArrayList<>()).add(passed);
        }
        ignored.clear();

        Set<String> scenarios = new LinkedHashSet<>();
        byScenario.forEach((id, values) -> {
            if (values.size() >= MIN_OBSERVATIONS && mixed(values)) scenarios.add(id);
        });
        Set<UUID> trainees = new LinkedHashSet<>();
        byTrainee.forEach((id, values) -> {
            if (values.size() >= 2 && mixed(values)) trainees.add(id);
        });
        observations = observations.stream()
                .filter(o -> scenarios.contains(o.scenarioId()) && trainees.contains(o.traineeId()))
                .toList();

        if (scenarios.isEmpty() || trainees.isEmpty()) {
            log.info("Рash: данных недостаточно — сценариев {}, обучающихся {}", scenarios.size(), trainees.size());
            return new Estimate(List.of(), List.of(), 0, false);
        }

        // --- JMLE
        Map<String, Double> b = new LinkedHashMap<>();
        scenarios.forEach(id -> b.put(id, 0.0));
        Map<UUID, Double> theta = new LinkedHashMap<>();
        trainees.forEach(id -> theta.put(id, 0.0));

        int iteration = 0;
        boolean converged = false;
        while (iteration++ < MAX_ITERATIONS) {
            double delta = 0;
            delta = Math.max(delta, updatePersons(observations, theta, b));
            delta = Math.max(delta, updateItems(observations, theta, b));
            centre(b);
            if (delta < CONVERGENCE) { converged = true; break; }
        }

        List<ScenarioDifficulty> scenarioResult = new ArrayList<>();
        for (String id : scenarios) {
            List<Boolean> values = byScenario.get(id);
            double information = itemInformation(observations, theta, b, id);
            scenarioResult.add(new ScenarioDifficulty(id, round(b.get(id)),
                    round(standardError(information)), values.size(),
                    (int) values.stream().filter(Boolean::booleanValue).count(), toTenPointScale(b.get(id))));
        }
        scenarioResult.sort((x, y) -> Double.compare(y.difficulty(), x.difficulty()));

        List<TraineeAbility> traineeResult = new ArrayList<>();
        for (UUID id : trainees) {
            List<Boolean> values = byTrainee.get(id);
            double information = personInformation(observations, theta, b, id);
            traineeResult.add(new TraineeAbility(id, round(theta.get(id)), round(standardError(information)),
                    values.size(), (int) values.stream().filter(Boolean::booleanValue).count()));
        }

        log.info("Раш: сценариев {}, обучающихся {}, итераций {}, сошлось {}",
                scenarioResult.size(), traineeResult.size(), iteration, converged);
        return new Estimate(scenarioResult, traineeResult, iteration, converged);
    }

    private record Observation(UUID traineeId, String scenarioId, boolean passed) {}

    /** Взвешенный балл карточки по весам режима; критерии без балла в расчёт не входят. */
    static Double composite(CardOutcome outcome) {
        double sum = 0, weights = 0;
        for (Criterion criterion : AssessmentWeights.forMode(outcome.mode())) {
            Double score = switch (criterion.code()) {
                case "address" -> outcome.address();
                case "classification" -> outcome.classification();
                case "services" -> outcome.services();
                case "timing" -> outcome.timing();
                case "language" -> outcome.language();
                case "actions" -> outcome.actions();
                case "communication" -> outcome.communication();
                default -> null;
            };
            if (score == null) continue;
            sum += score * criterion.weight();
            weights += criterion.weight();
        }
        return weights == 0 ? null : sum / weights;
    }

    private static boolean mixed(List<Boolean> values) {
        // в модели Раша крайние случаи не дают конечной оценки: нужен хотя бы один успех и один провал
        return values.contains(true) && values.contains(false);
    }

    private static double updatePersons(List<Observation> observations, Map<UUID, Double> theta,
                                        Map<String, Double> b) {
        Map<UUID, double[]> stats = new LinkedHashMap<>();
        for (Observation o : observations) {
            double p = probability(theta.get(o.traineeId()), b.get(o.scenarioId()));
            double[] s = stats.computeIfAbsent(o.traineeId(), k -> new double[3]);
            s[0] += o.passed() ? 1 : 0;
            s[1] += p;
            s[2] += p * (1 - p);
        }
        return applyStep(stats, theta, 1);
    }

    private static double updateItems(List<Observation> observations, Map<UUID, Double> theta,
                                      Map<String, Double> b) {
        Map<String, double[]> stats = new LinkedHashMap<>();
        for (Observation o : observations) {
            double p = probability(theta.get(o.traineeId()), b.get(o.scenarioId()));
            double[] s = stats.computeIfAbsent(o.scenarioId(), k -> new double[3]);
            s[0] += o.passed() ? 1 : 0;
            s[1] += p;
            s[2] += p * (1 - p);
        }
        // у сценария знак обратный: чем больше справившихся, тем он легче
        return applyStep(stats, b, -1);
    }

    private static <K> double applyStep(Map<K, double[]> stats, Map<K, Double> values, int sign) {
        double maxDelta = 0;
        for (Map.Entry<K, double[]> entry : stats.entrySet()) {
            double[] s = entry.getValue();
            if (s[2] < 1e-9) continue;
            double step = sign * (s[0] - s[1]) / s[2];
            step = Math.max(-MAX_STEP, Math.min(MAX_STEP, step));
            values.put(entry.getKey(), values.get(entry.getKey()) + step);
            maxDelta = Math.max(maxDelta, Math.abs(step));
        }
        return maxDelta;
    }

    /** Шкала логитов определена с точностью до сдвига — закрепляем нулём средней сложности. */
    private static void centre(Map<String, Double> b) {
        double mean = b.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        b.replaceAll((k, v) -> v - mean);
    }

    private static double itemInformation(List<Observation> observations, Map<UUID, Double> theta,
                                          Map<String, Double> b, String scenarioId) {
        double information = 0;
        for (Observation o : observations) {
            if (!o.scenarioId().equals(scenarioId)) continue;
            double p = probability(theta.get(o.traineeId()), b.get(scenarioId));
            information += p * (1 - p);
        }
        return information;
    }

    private static double personInformation(List<Observation> observations, Map<UUID, Double> theta,
                                            Map<String, Double> b, UUID traineeId) {
        double information = 0;
        for (Observation o : observations) {
            if (!o.traineeId().equals(traineeId)) continue;
            double p = probability(theta.get(traineeId), b.get(o.scenarioId()));
            information += p * (1 - p);
        }
        return information;
    }

    private static double standardError(double information) {
        return information < 1e-9 ? Double.NaN : 1.0 / Math.sqrt(information);
    }

    private static double probability(double ability, double difficulty) {
        return 1.0 / (1.0 + Math.exp(-(ability - difficulty)));
    }

    /**
     * Логиты в привычную преподавателю шкалу 1–10. Диапазон ±3 логита покрывает практически
     * всё наблюдаемое; за его пределами значение прижимается к границе.
     */
    static int toTenPointScale(double logit) {
        double scaled = 5.5 + logit * 1.5;
        return (int) Math.round(Math.max(1, Math.min(10, scaled)));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
