package ru.lct.arm112.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiModels.Rating;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Рейтинг обучающегося — среднее итоговых оценок завершённых сессий, взвешенное сложностью сценариев занятия;
 * рейтинг группы — среднее по участникам. Итог = оценка преподавателя, если есть, иначе ИИ (решение №5).
 *
 * <h2>Почему выборка сужается в SQL, а не в памяти</h2>
 * Раньше любой расчёт начинался с чтения <b>всех оценок в базе</b>, после чего лишнее
 * отбрасывалось в памяти. Отчёт по занятию вызывал рейтинг в цикле по обучающимся,
 * то есть один отчёт на группу из тридцати человек означал тридцать полных проходов
 * по таблице оценок. Стоимость росла как «размер группы × вся история центра»:
 * на стенде p95 отчёта поднялся со 149 до 407 мс, когда база выросла со 130 до 281
 * обучающегося (см. ANALYTICS_REPORT.md §4.5).
 *
 * <p>Индексом это не лечится — запрос не фильтровал ничего намеренно. Поэтому условие
 * отбора перенесено в SQL, а для отчёта добавлен {@link #ratingsOf} — он делает
 * один запрос на группу вместо одного на обучающегося.
 */
@Service
public class RatingService {
    private final JdbcTemplate jdbc;

    public RatingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String TRAINEE_SQL = """
            select s.trainee_id, coalesce(a.teacher_total, a.ai_total) as final_total, l.id as lesson_id
              from assessment a
              join training_session s on s.id = a.session_id
              join lesson l on l.id = s.lesson_id
             where s.state = 'COMPLETED'
            """;

    /** Отбор по группе: рейтингу нужны только её участники — и для среднего, и для места. */
    private static final String BY_GROUP =
            " and s.trainee_id in (select id from app_user where group_id = ? and role = 'TRAINEE')";
    private static final String BY_TRAINEE = " and s.trainee_id = ?";
    private static final String BY_LESSON = " and l.id = ?";

    public Rating traineeRating(UUID traineeId, UUID groupId) {
        if (groupId == null) {
            List<Row> own = load(BY_TRAINEE, traineeId);
            return new Rating(average(own), null, null, own.size());
        }
        return ratingsOf(List.of(traineeId), groupId).get(traineeId);
    }

    /**
     * Рейтинги сразу нескольких обучающихся одной группы — один запрос на всех.
     *
     * <p>Для отчёта по занятию это и есть основная правка: раньше здесь был цикл
     * с полным чтением таблицы на каждом шаге.
     */
    public Map<UUID, Rating> ratingsOf(List<UUID> traineeIds, UUID groupId) {
        Map<UUID, Rating> result = new LinkedHashMap<>();
        if (groupId == null) {
            for (UUID id : traineeIds) result.put(id, traineeRating(id, null));
            return result;
        }
        List<UUID> members = jdbc.query("select id from app_user where group_id = ? and role = 'TRAINEE'",
                (rs, i) -> rs.getObject("id", UUID.class), groupId);
        List<Row> rows = load(BY_GROUP, groupId);

        Map<UUID, List<Row>> byTrainee = new LinkedHashMap<>();
        for (Row row : rows) byTrainee.computeIfAbsent(row.traineeId(), k -> new ArrayList<>()).add(row);

        List<Double> memberRatings = new ArrayList<>();
        for (UUID member : members) {
            Double value = average(byTrainee.getOrDefault(member, List.of()));
            memberRatings.add(value == null ? -1 : value);
        }
        for (UUID traineeId : traineeIds) {
            List<Row> own = byTrainee.getOrDefault(traineeId, List.of());
            Double value = average(own);
            Integer rank = null;
            if (value != null) {
                final double mine = value;
                rank = 1 + (int) memberRatings.stream().filter(v -> v > mine).count();
            }
            result.put(traineeId, new Rating(value, rank, members.size(), own.size()));
        }
        return result;
    }

    public Double groupRating(UUID groupId) {
        if (groupId == null) return null;
        List<UUID> members = jdbc.query("select id from app_user where group_id = ? and role = 'TRAINEE'",
                (rs, i) -> rs.getObject("id", UUID.class), groupId);
        Map<UUID, List<Row>> byTrainee = new LinkedHashMap<>();
        for (Row row : load(BY_GROUP, groupId)) {
            byTrainee.computeIfAbsent(row.traineeId(), k -> new ArrayList<>()).add(row);
        }
        List<Double> values = new ArrayList<>();
        for (UUID member : members) {
            Double value = average(byTrainee.getOrDefault(member, List.of()));
            if (value != null) values.add(value);
        }
        return values.isEmpty() ? null : Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
    }

    public Double lessonRating(UUID lessonId) {
        return average(load(BY_LESSON, lessonId));
    }

    private List<Row> load(String condition, Object... args) {
        return jdbc.query(TRAINEE_SQL + condition, (rs, i) -> new Row(rs.getObject("trainee_id", UUID.class),
                rs.getBigDecimal("final_total").doubleValue(), rs.getObject("lesson_id", UUID.class)), args);
    }

    private static Double average(List<Row> rows) {
        if (rows.isEmpty()) return null;
        return Math.round(rows.stream().mapToDouble(Row::finalTotal).average().orElse(0) * 10) / 10.0;
    }

    private record Row(UUID traineeId, double finalTotal, UUID lessonId) {}
}
