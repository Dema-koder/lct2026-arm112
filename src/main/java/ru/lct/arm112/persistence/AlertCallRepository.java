package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.lct.arm112.api.ApiModels.AlertCallAttempt;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AlertCallRepository {
    private static final RowMapper<AlertCallAttempt> MAPPER = (rs, row) -> new AlertCallAttempt(
            rs.getObject("id", UUID.class), rs.getObject("retry_of_id", UUID.class), rs.getString("service_id"),
            rs.getString("trigger_type"), rs.getString("recipient"), rs.getString("message"),
            rs.getString("status"), rs.getObject("answered", Boolean.class), rs.getInt("attempt_number"),
            rs.getInt("recipient_order"), rs.getString("gateway_call_id"), rs.getString("error_message"),
            rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public AlertCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AlertCallAttempt attempt) {
        jdbc.update("""
                insert into alert_call_attempt (id, retry_of_id, service_id, trigger_type, recipient,
                    message, status, answered, attempt_number, recipient_order, gateway_call_id, error_message,
                    requested_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, attempt.id(), attempt.retryOfId(), attempt.serviceId(), attempt.triggerType(),
                attempt.recipient(), attempt.message(), attempt.status(), attempt.answered(),
                attempt.attemptNumber(), attempt.recipientOrder(), attempt.gatewayCallId(), attempt.errorMessage(),
                Timestamp.from(attempt.requestedAt()), Timestamp.from(attempt.updatedAt()));
    }

    public List<AlertCallAttempt> list(int limit) {
        return jdbc.query("select * from alert_call_attempt order by requested_at desc limit ?", MAPPER, limit);
    }

    public Optional<AlertCallAttempt> findById(UUID id) {
        return jdbc.query("select * from alert_call_attempt where id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<AlertCallAttempt> findByGatewayCallId(String gatewayCallId) {
        return jdbc.query("select * from alert_call_attempt where gateway_call_id = ?", MAPPER, gatewayCallId)
                .stream().findFirst();
    }

    public int updateByGatewayCallId(String gatewayCallId, String status, Boolean answered,
                                     String errorMessage, Instant updatedAt) {
        return jdbc.update("""
                update alert_call_attempt
                   set status = ?, answered = ?, error_message = ?, updated_at = ?
                 where gateway_call_id = ?
                """, status, answered, errorMessage, Timestamp.from(updatedAt), gatewayCallId);
    }
}
