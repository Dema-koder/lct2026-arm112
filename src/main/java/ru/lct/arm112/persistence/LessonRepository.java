package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.lct.arm112.api.ApiModels.Group;
import ru.lct.arm112.api.ApiModels.Lesson;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Группы и занятия преподавателя. */
@Repository
public class LessonRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<Lesson> lessonMapper = this::mapLesson;
    private static final RowMapper<Group> GROUP_MAPPER = (rs, row) -> new Group(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("teacher_id", UUID.class),
            rs.getString("teacher_name"), List.of());

    public LessonRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- groups

    public List<Group> findGroups(UUID teacherId) {
        String sql = """
                select g.id, g.name, g.teacher_id, u.display_name as teacher_name
                  from training_group g join app_user u on u.id = g.teacher_id
                """ + (teacherId == null ? "" : " where g.teacher_id = ?") + " order by g.name";
        return teacherId == null ? jdbc.query(sql, GROUP_MAPPER) : jdbc.query(sql, GROUP_MAPPER, teacherId);
    }

    public Optional<Group> findGroup(UUID id) {
        return jdbc.query("""
                select g.id, g.name, g.teacher_id, u.display_name as teacher_name
                  from training_group g join app_user u on u.id = g.teacher_id
                 where g.id = ?
                """, GROUP_MAPPER, id).stream().findFirst();
    }

    public void insertGroup(UUID id, String name, UUID teacherId) {
        jdbc.update("insert into training_group (id, name, teacher_id) values (?, ?, ?)", id, name, teacherId);
    }

    public void updateGroup(UUID id, String name, UUID teacherId) {
        jdbc.update("update training_group set name = ?, teacher_id = ? where id = ?", name, teacherId, id);
    }

    public long countGroups() {
        Long value = jdbc.queryForObject("select count(*) from training_group", Long.class);
        return value == null ? 0 : value;
    }

    public long countGroupsByTeacher(UUID teacherId) {
        Long value = jdbc.queryForObject("select count(*) from training_group where teacher_id = ?",
                Long.class, teacherId);
        return value == null ? 0 : value;
    }

    // ---------------------------------------------------------------- lessons

    public List<Lesson> findLessons(UUID teacherId, String state) {
        StringBuilder sql = new StringBuilder("""
                select l.*, g.name as group_name,
                       (select count(*) from training_session s where s.lesson_id = l.id) as session_count
                  from lesson l left join training_group g on g.id = l.group_id
                 where 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (teacherId != null) { sql.append(" and l.teacher_id = ?"); args.add(teacherId); }
        if (state != null && !state.isBlank()) { sql.append(" and l.state = ?"); args.add(state); }
        sql.append(" order by l.created_at desc");
        return jdbc.query(sql.toString(), lessonMapper, args.toArray());
    }

    public Optional<Lesson> findLesson(UUID id) {
        return jdbc.query("""
                select l.*, g.name as group_name,
                       (select count(*) from training_session s where s.lesson_id = l.id) as session_count
                  from lesson l left join training_group g on g.id = l.group_id
                 where l.id = ?
                """, lessonMapper, id).stream().findFirst();
    }

    public void insertLesson(Lesson lesson) {
        jdbc.update("""
                insert into lesson (id, teacher_id, group_id, title, kind, mode, card_source, state, scenario_ids,
                                    service_code, intensity, norm_score)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, lesson.id(), lesson.teacherId(), lesson.groupId(), lesson.title(), lesson.kind(),
                lesson.mode(), lesson.cardSource(), lesson.state(), encode(lesson.scenarioIds()),
                lesson.serviceCode(), lesson.intensity(), lesson.normScore());
    }

    public void setState(UUID id, String state, Instant startedAt, Instant completedAt) {
        jdbc.update("update lesson set state = ?, started_at = coalesce(?, started_at), completed_at = coalesce(?, completed_at) where id = ?",
                state, startedAt == null ? null : Timestamp.from(startedAt),
                completedAt == null ? null : Timestamp.from(completedAt), id);
    }

    public void publish(UUID id, Instant at) {
        jdbc.update("update lesson set results_published_at = ? where id = ?", Timestamp.from(at), id);
    }

    private Lesson mapLesson(ResultSet rs, int row) throws SQLException {
        return new Lesson(rs.getObject("id", UUID.class), rs.getObject("teacher_id", UUID.class),
                rs.getObject("group_id", UUID.class), rs.getString("group_name"), rs.getString("title"),
                rs.getString("kind"), rs.getString("mode"), rs.getString("card_source"), rs.getString("state"),
                decode(rs.getString("scenario_ids")), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("results_published_at")), rs.getInt("session_count"),
                rs.getString("service_code"), rs.getString("intensity"), rs.getInt("norm_score"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String encode(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось сохранить список сценариев", exception);
        }
    }

    private List<String> decode(String payload) {
        try {
            return objectMapper.readValue(payload, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось прочитать список сценариев", exception);
        }
    }
}
