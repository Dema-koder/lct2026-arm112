package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.lct.arm112.api.ApiModels.AuditEntry;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class AuditRepository {
    private static final RowMapper<AuditEntry> MAPPER = (rs, row) -> new AuditEntry(
            rs.getObject("id", UUID.class), rs.getObject("actor_user_id", UUID.class),
            rs.getString("actor_login"), rs.getString("actor_role"), rs.getString("action"),
            rs.getString("resource_type"), rs.getString("resource_id"),
            rs.getObject("http_status") == null ? null : rs.getInt("http_status"),
            rs.getObject("request_id", UUID.class), rs.getString("client_ip"), rs.getString("payload"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditEntry entry) {
        jdbc.update("""
                insert into audit_event (id, actor_user_id, actor_login, actor_role, action, resource_type,
                                         resource_id, http_status, request_id, client_ip, payload, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entry.id(), entry.actorUserId(), entry.actorLogin(), entry.actorRole(), entry.action(),
                entry.resourceType(), entry.resourceId(), entry.httpStatus(), entry.requestId(),
                entry.clientIp(), entry.payload(), Timestamp.from(entry.occurredAt()));
    }

    /** Страница идёт от новых к старым; курсор — момент последней записи предыдущей страницы. */
    public List<AuditEntry> query(UUID actorId, String role, String action, Instant from, Instant to,
                                  Instant before, int limit) {
        StringBuilder sql = new StringBuilder("select * from audit_event where 1=1");
        List<Object> args = new ArrayList<>();
        if (actorId != null) { sql.append(" and actor_user_id = ?"); args.add(actorId); }
        if (role != null && !role.isBlank()) { sql.append(" and actor_role = ?"); args.add(role); }
        if (action != null && !action.isBlank()) {
            sql.append(" and lower(action) like ?");
            args.add("%" + action.toLowerCase() + "%");
        }
        if (from != null) { sql.append(" and occurred_at >= ?"); args.add(Timestamp.from(from)); }
        if (to != null) { sql.append(" and occurred_at <= ?"); args.add(Timestamp.from(to)); }
        if (before != null) { sql.append(" and occurred_at < ?"); args.add(Timestamp.from(before)); }
        sql.append(" order by occurred_at desc limit ").append(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count() {
        Long value = jdbc.queryForObject("select count(*) from audit_event", Long.class);
        return value == null ? 0 : value;
    }

    public int deleteOlderThan(Instant threshold) {
        return jdbc.update("delete from audit_event where occurred_at < ?", Timestamp.from(threshold));
    }
}
