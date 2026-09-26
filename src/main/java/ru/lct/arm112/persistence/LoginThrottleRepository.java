package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class LoginThrottleRepository {
    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public LoginThrottleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.postgres = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql")));
    }

    public void ensure(String key, Instant now) {
        if (postgres) {
            jdbc.update("""
                    insert into login_throttle (throttle_key, failures, window_started, updated_at)
                    values (?, 0, ?, ?)
                    on conflict (throttle_key) do nothing
                    """, key, Timestamp.from(now), Timestamp.from(now));
        } else {
            jdbc.update("""
                    insert into login_throttle (throttle_key, failures, window_started, updated_at)
                    select ?, 0, ?, ? where not exists
                        (select 1 from login_throttle where throttle_key = ?)
                    """, key, Timestamp.from(now), Timestamp.from(now), key);
        }
    }

    public Optional<State> lock(String key) {
        return jdbc.query("""
                        select failures, window_started, blocked_until
                          from login_throttle where throttle_key = ? for update
                        """, (rs, row) -> new State(rs.getInt("failures"),
                        rs.getTimestamp("window_started").toInstant(),
                        rs.getTimestamp("blocked_until") == null ? null : rs.getTimestamp("blocked_until").toInstant()), key)
                .stream().findFirst();
    }

    public void update(String key, int failures, Instant windowStarted, Instant blockedUntil, Instant now) {
        jdbc.update("""
                update login_throttle
                   set failures = ?, window_started = ?, blocked_until = ?, updated_at = ?
                 where throttle_key = ?
                """, failures, Timestamp.from(windowStarted),
                blockedUntil == null ? null : Timestamp.from(blockedUntil), Timestamp.from(now), key);
    }

    public void delete(String key) {
        jdbc.update("delete from login_throttle where throttle_key = ?", key);
    }

    public void deleteOlderThan(Instant cutoff) {
        jdbc.update("delete from login_throttle where updated_at < ?", Timestamp.from(cutoff));
    }

    public record State(int failures, Instant windowStarted, Instant blockedUntil) {}
}
