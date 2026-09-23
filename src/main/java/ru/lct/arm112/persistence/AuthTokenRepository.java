package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthTokenRepository {
    private final JdbcTemplate jdbc;

    public AuthTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String hash, UUID userId, long authVersion, Instant issuedAt, Instant expiresAt) {
        jdbc.update("""
                insert into auth_refresh_token
                    (token_hash, user_id, auth_version, issued_at, expires_at)
                values (?, ?, ?, ?, ?)
                """, hash, userId, authVersion, Timestamp.from(issuedAt), Timestamp.from(expiresAt));
    }

    public Optional<RefreshToken> findActive(String hash, Instant now) {
        return jdbc.query("""
                        select token_hash, user_id, auth_version, issued_at, expires_at
                          from auth_refresh_token
                         where token_hash = ? and revoked_at is null and expires_at > ?
                        """,
                (rs, row) -> new RefreshToken(rs.getString("token_hash"), rs.getObject("user_id", UUID.class),
                        rs.getLong("auth_version"), rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()), hash, Timestamp.from(now))
                .stream().findFirst();
    }

    public int revoke(String hash, Instant now) {
        return jdbc.update("update auth_refresh_token set revoked_at = ? where token_hash = ? and revoked_at is null",
                Timestamp.from(now), hash);
    }

    public void deleteExpired(Instant before) {
        jdbc.update("delete from auth_refresh_token where expires_at < ? or revoked_at < ?",
                Timestamp.from(before), Timestamp.from(before));
    }

    public record RefreshToken(String hash, UUID userId, long authVersion, Instant issuedAt, Instant expiresAt) {}
}
