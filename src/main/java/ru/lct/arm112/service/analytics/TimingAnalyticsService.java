package ru.lct.arm112.service.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Как обучающийся распределяет время внутри карточки и как это смотрится на фоне группы
 * (задача B3 плана).
 *
 * <p>Абсолютный норматив в три минуты отвечает «уложился или нет», но не отвечает «почему».
 * Здесь считается, на чём уходит время — на поиске адреса, на выборе типа, на колебаниях
 * между типами, на паузах — и как это соотносится с одногруппниками.
 *
 * <p><b>Перцентиль не выдаётся на малых группах.</b> При пяти участниках ранг — это шум,
 * выданный за оценку: один отсутствующий меняет картину целиком. Порог в
 * {@link #MIN_GROUP} человек взят из [METRICS.md §5.5](../../../../../../docs/assessment/METRICS.md).
 */
@Service
public class TimingAnalyticsService {

    /** Меньше этого числа обучающихся в сравнении — перцентиль не показывается. */
    static final int MIN_GROUP = 8;

    private final JdbcTemplate jdbc;

    public TimingAnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Показатель времени с привязкой к норме группы.
     *
     * @param percentile доля группы, работающая быстрее; null — участников меньше порога
     */
    public record TimingMetric(String code, String label, Double value, Double groupMedian,
                               Integer percentile, String hint) {}

    /** Разбор времени одного обучающегося на фоне группы. */
    public List<TimingMetric> forTrainee(UUID traineeId, UUID groupId) {
        List<TimingMetric> result = new ArrayList<>();
        result.add(metric(traineeId, groupId, "seconds_to_address", "Время до ввода адреса",
                "Адрес вводится заметно дольше, чем у группы: уточняйте улицу сразу, детали — потом"));
        result.add(metric(traineeId, groupId, "seconds_to_type", "Время до выбора типа",
                "Тип выбирается долго: пользуйтесь поиском и синонимами, а не прокруткой списка"));
        result.add(metric(traineeId, groupId, "type_changes", "Смен типа за карточку",
                "Тип меняется по нескольку раз: уточните у заявителя характер происшествия до выбора"));
        result.add(metric(traineeId, groupId, "idle_seconds", "Паузы без работы",
                "Длинные паузы в работе с карточкой: заявитель ждёт ответа"));
        result.add(metric(traineeId, groupId, "spent_seconds", "Время на карточку",
                "Карточка заполняется дольше, чем у группы"));
        return result;
    }

    private TimingMetric metric(UUID traineeId, UUID groupId, String column, String label, String hint) {
        Double own = jdbc.queryForObject(
                "select avg(" + column + " * 1.0) from assessment_card where trainee_id = ?",
                Double.class, traineeId);
        if (own == null) return new TimingMetric(column, label, null, null, null, null);

        // средние по каждому участнику группы — сравниваются люди, а не отдельные карточки
        List<Double> peers = jdbc.queryForList("""
                select avg(c.%s * 1.0)
                  from assessment_card c join app_user u on u.id = c.trainee_id
                 where u.group_id = ? and c.%s is not null
                 group by c.trainee_id
                """.formatted(column, column), Double.class, groupId);

        Double median = median(peers);
        Integer percentile = peers.size() < MIN_GROUP ? null
                : (int) Math.round(100.0 * peers.stream().filter(v -> v < own).count() / peers.size());
        // подсказка только тем, кто медленнее большинства: остальным она бессмысленна
        String advice = percentile != null && percentile >= 70 ? hint : null;
        return new TimingMetric(column, label, round(own), round(median), percentile, advice);
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return round(sorted.size() % 2 == 1 ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2);
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 10.0) / 10.0;
    }
}
