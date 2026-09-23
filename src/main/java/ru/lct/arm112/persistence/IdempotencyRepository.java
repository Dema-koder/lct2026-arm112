package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.postgres = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql")));
    }

    public boolean reserve(String operation, UUID key, String signature, Instant expiresAt) {
        if (postgres) {
            return jdbc.update("""
                    insert into idempotency_record
                        (operation, idempotency_key, request_signature, expires_at)
                    values (?, ?, ?, ?)
                    on conflict (operation, idempotency_key) do nothing
                    """, operation, key, signature, Timestamp.from(expiresAt)) == 1;
        }
        return jdbc.update("""
                insert into idempotency_record
                    (operation, idempotency_key, request_signature, expires_at)
                select ?, ?, ?, ? where not exists
                    (select 1 from idempotency_record where operation = ? and idempotency_key = ?)
                """, operation, key, signature, Timestamp.from(expiresAt), operation, key) == 1;
    }

    public Optional<Record> find(String operation, UUID key) {
        return jdbc.query("""
                        select request_signature, response_type, response_payload, expires_at
                          from idempotency_record
                         where operation = ? and idempotency_key = ? and expires_at > current_timestamp
                        """,
                (rs, row) -> new Record(rs.getString("request_signature"), rs.getString("response_type"),
                        rs.getString("response_payload"), rs.getTimestamp("expires_at").toInstant()),
                operation, key).stream().findFirst();
    }

    public void complete(String operation, UUID key, String type, String payload) {
        jdbc.update("""
                update idempotency_record
                   set response_type = ?, response_payload = ?
                 where operation = ? and idempotency_key = ?
                """, type, payload, operation, key);
    }

    public void release(String operation, UUID key) {
        jdbc.update("delete from idempotency_record where operation = ? and idempotency_key = ? and response_payload is null",
                operation, key);
    }

    public int deleteExpired() {
        return jdbc.update("delete from idempotency_record where expires_at <= current_timestamp");
    }

    public record Record(String signature, String responseType, String responsePayload, Instant expiresAt) {}
}
