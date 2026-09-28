package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.lct.arm112.api.ApiModels.ServiceEvent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ServiceEventRepository {
    private static final RowMapper<ServiceEvent> MAPPER = (rs, row) -> new ServiceEvent(
            rs.getObject("id", UUID.class), rs.getString("service_id"), rs.getString("event_type"),
            rs.getString("previous_state"), rs.getString("current_state"), rs.getString("action"),
            rs.getString("outcome"), rs.getString("message"), rs.getObject("actor_user_id", UUID.class),
            rs.getString("actor_login"), rs.getBoolean("notified"), rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbc;

    public ServiceEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ServiceEvent event) {
        jdbc.update("""
                insert into service_event (id, service_id, event_type, previous_state, current_state,
                                           action, outcome, message, actor_user_id, actor_login, notified, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.serviceId(), event.eventType(), event.previousState(),
                event.currentState(), event.action(), event.outcome(), event.message(), event.actorUserId(),
                event.actorLogin(), event.notified(), Timestamp.from(event.occurredAt()));
    }

    public List<ServiceEvent> list(String serviceId, int limit) {
        StringBuilder sql = new StringBuilder("select * from service_event where 1=1");
        List<Object> args = new ArrayList<>();
        if (serviceId != null && !serviceId.isBlank()) {
            sql.append(" and service_id = ?");
            args.add(serviceId);
        }
        sql.append(" order by occurred_at desc limit ").append(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}
