package ru.lct.arm112.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiModels.Rating;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Рейтинг обучающегося — среднее итоговых оценок завершённых сессий, взвешенное сложностью сценариев занятия;
 * рейтинг группы — среднее по участникам. Итог = оценка преподавателя, если есть, иначе ИИ (решение №5).
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

    public Rating traineeRating(UUID traineeId, UUID groupId) {
        List<Row> rows = load();
        Double own = average(rows.stream().filter(r -> r.traineeId().equals(traineeId)).toList());
        int completed = (int) rows.stream().filter(r -> r.traineeId().equals(traineeId)).count();
        Integer rank = null;
        Integer size = null;
        if (groupId != null) {
            List<UUID> members = jdbc.query("select id from app_user where group_id = ? and role = 'TRAINEE'",
                    (rs, i) -> rs.getObject("id", UUID.class), groupId);
            size = members.size();
            List<Double> ratings = new ArrayList<>();
            for (UUID member : members) {
                Double value = average(rows.stream().filter(r -> r.traineeId().equals(member)).toList());
                ratings.add(value == null ? -1 : value);
            }
            if (own != null) {
                final double mine = own;
                rank = 1 + (int) ratings.stream().filter(v -> v > mine).count();
            }
        }
        return new Rating(own, rank, size, completed);
    }

    public Double groupRating(UUID groupId) {
        if (groupId == null) return null;
        List<UUID> members = jdbc.query("select id from app_user where group_id = ? and role = 'TRAINEE'",
                (rs, i) -> rs.getObject("id", UUID.class), groupId);
        List<Row> rows = load();
        List<Double> values = new ArrayList<>();
        for (UUID member : members) {
            Double value = average(rows.stream().filter(r -> r.traineeId().equals(member)).toList());
            if (value != null) values.add(value);
        }
        return values.isEmpty() ? null : Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
    }

    public Double lessonRating(UUID lessonId) {
        List<Row> rows = load().stream().filter(r -> r.lessonId().equals(lessonId)).toList();
        return average(rows);
    }

    private List<Row> load() {
        return jdbc.query(TRAINEE_SQL, (rs, i) -> new Row(rs.getObject("trainee_id", UUID.class),
                rs.getBigDecimal("final_total").doubleValue(), rs.getObject("lesson_id", UUID.class)));
    }

    private static Double average(List<Row> rows) {
        if (rows.isEmpty()) return null;
        return Math.round(rows.stream().mapToDouble(Row::finalTotal).average().orElse(0) * 10) / 10.0;
    }

    private record Row(UUID traineeId, double finalTotal, UUID lessonId) {}
}
