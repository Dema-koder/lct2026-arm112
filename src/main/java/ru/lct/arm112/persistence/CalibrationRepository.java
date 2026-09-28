package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Хранилище версий коррекции: одновременно активна не более одной версии на режим. */
@Repository
public class CalibrationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CalibrationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record Parameter(String code, String label, double slope, double intercept,
                            double maeBefore, double maeAfter, double improvementPercent, int pairs) {}

    public record ModelRow(UUID id, String mode, int version, List<Parameter> parameters,
                           int assessments, Double maeBefore, Double maeAfter, boolean active,
                           UUID createdBy, Instant createdAt, Instant activatedAt, Instant deactivatedAt) {}

    public Optional<ModelRow> active(String mode) {
        return jdbc.query("""
                select * from assessment_calibration_model
                 where mode = ? and active = true order by version desc
                """, this::map, mode).stream().findFirst();
    }

    public List<ModelRow> history(String mode) {
        return jdbc.query("""
                select * from assessment_calibration_model where mode = ? order by version desc
                """, this::map, mode);
    }

    public int nextVersion(String mode) {
        Integer value = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1 from assessment_calibration_model where mode = ?
                """, Integer.class, mode);
        return value == null ? 1 : value;
    }

    public ModelRow activate(String mode, List<Parameter> parameters, int assessments,
                             double maeBefore, double maeAfter, UUID actorId) {
        jdbc.update("""
                update assessment_calibration_model
                   set active = false, deactivated_at = current_timestamp
                 where mode = ? and active = true
                """, mode);
        UUID id = UUID.randomUUID();
        int version = nextVersion(mode);
        jdbc.update("""
                insert into assessment_calibration_model
                    (id, mode, version, parameters, assessments, mae_before, mae_after,
                     active, created_by, activated_at)
                values (?, ?, ?, ?, ?, ?, ?, true, ?, current_timestamp)
                """, id, mode, version, encode(parameters), assessments, maeBefore, maeAfter, actorId);
        return active(mode).orElseThrow();
    }

    public void deactivate(String mode) {
        jdbc.update("""
                update assessment_calibration_model
                   set active = false, deactivated_at = current_timestamp
                 where mode = ? and active = true
                """, mode);
    }

    private ModelRow map(ResultSet rs, int row) throws SQLException {
        return new ModelRow(rs.getObject("id", UUID.class), rs.getString("mode"), rs.getInt("version"),
                decode(rs.getString("parameters")), rs.getInt("assessments"), number(rs, "mae_before"),
                number(rs, "mae_after"), rs.getBoolean("active"), rs.getObject("created_by", UUID.class),
                instant(rs, "created_at"), instant(rs, "activated_at"), instant(rs, "deactivated_at"));
    }

    private static Double number(ResultSet rs, String column) throws SQLException {
        var value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String encode(List<Parameter> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Не удалось сохранить параметры коррекции", ex);
        }
    }

    private List<Parameter> decode(String value) {
        try {
            return objectMapper.readValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Parameter.class));
        } catch (JacksonException ex) {
            throw new IllegalStateException("Не удалось прочитать параметры коррекции", ex);
        }
    }
}
