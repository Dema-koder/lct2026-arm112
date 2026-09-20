package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.lct.arm112.api.ApiModels.Scenario;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Сценарии: описание и эталон лежат в JSON, фильтруемые поля — в колонках. */
@Repository
public class ScenarioRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<Scenario> mapper = this::map;

    public ScenarioRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long count() {
        Long value = jdbc.queryForObject("select count(*) from scenario", Long.class);
        return value == null ? 0 : value;
    }

    public Optional<Scenario> findById(String id) {
        return jdbc.query("select * from scenario where id = ?", mapper, id).stream().findFirst();
    }

    public List<Scenario> findByIds(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Scenario> found = jdbc.query("select * from scenario where id in (" + placeholders + ")",
                mapper, ids.toArray());
        // сохраняем порядок, в котором преподаватель выбрал сценарии
        List<Scenario> ordered = new ArrayList<>();
        for (String id : ids) {
            found.stream().filter(s -> s.id().equals(id)).findFirst().ifPresent(ordered::add);
        }
        return ordered;
    }

    public List<Scenario> find(String category, String source, Boolean confirmed, int limit) {
        StringBuilder sql = new StringBuilder("select * from scenario where 1=1");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank()) { sql.append(" and category = ?"); args.add(category); }
        if (source != null && !source.isBlank()) { sql.append(" and source = ?"); args.add(source); }
        if (confirmed != null) {
            sql.append(confirmed ? " and reference_confirmed_at is not null" : " and reference_confirmed_at is null");
        }
        sql.append(" order by reference_confirmed_at desc nulls last, title, id limit ").append(limit);
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    public void insert(Scenario scenario) {
        jdbc.update("""
                insert into scenario (id, title, source, category, difficulty, payload, reference_confirmed_by,
                                      reference_confirmed_at, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, scenario.id(), scenario.title(), scenario.source(), scenario.category(), scenario.difficulty(),
                encode(scenario), scenario.referenceConfirmed() ? scenario.createdBy() : null,
                scenario.referenceConfirmedAt() == null ? null : Timestamp.from(scenario.referenceConfirmedAt()),
                scenario.createdBy());
    }

    public void update(Scenario scenario) {
        jdbc.update("""
                update scenario set title = ?, category = ?, difficulty = ?, payload = ?, updated_at = current_timestamp
                 where id = ?
                """, scenario.title(), scenario.category(), scenario.difficulty(), encode(scenario), scenario.id());
    }

    public void confirmReference(String id, UUID by) {
        jdbc.update("""
                update scenario set reference_confirmed_by = ?, reference_confirmed_at = current_timestamp,
                                    updated_at = current_timestamp
                 where id = ?
                """, by, id);
    }

    private Scenario map(ResultSet rs, int row) throws SQLException {
        Scenario payload = decode(rs.getString("payload"));
        Timestamp confirmedAt = rs.getTimestamp("reference_confirmed_at");
        Timestamp createdAt = rs.getTimestamp("created_at");
        String title = rs.getString("title");
        return new Scenario(rs.getString("id"), title == null ? payload.title() : title, rs.getString("source"), rs.getString("category"),
                rs.getInt("difficulty"), payload.callerText(), payload.caller(), payload.rawAddress(),
                payload.expectedAddress(), nz(payload.expectedIncidentTypes()), nz(payload.expectedServices()),
                payload.addressClarified(), payload.expectedDecision(), payload.expectedDecisionReason(),
                payload.outboundCallRequired(), confirmedAt != null,
                confirmedAt == null ? null : confirmedAt.toInstant(),
                rs.getObject("created_by", UUID.class),
                createdAt == null ? Instant.now() : createdAt.toInstant());
    }

    private static List<String> nz(List<String> value) {
        return value == null ? List.of() : value;
    }

    private String encode(Scenario scenario) {
        try {
            return objectMapper.writeValueAsString(scenario);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось сериализовать сценарий", exception);
        }
    }

    private Scenario decode(String payload) {
        try {
            return objectMapper.readValue(payload, Scenario.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось прочитать сценарий", exception);
        }
    }
}
