package ru.lct.arm112.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.persistence.AuthTokenRepository;
import ru.lct.arm112.persistence.UserRepository;
import ru.lct.arm112.persistence.UserRepository.AppUser;
import ru.lct.arm112.security.JwtService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthSessionService {
    private final AuthTokenRepository tokens;
    private final UserRepository users;
    private final JwtService jwt;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public AuthSessionService(AuthTokenRepository tokens, UserRepository users, JwtService jwt,
                              @Value("${arm112.refresh-token-ttl:P7D}") Duration refreshTtl) {
        this.tokens = tokens;
        this.users = users;
        this.jwt = jwt;
        this.refreshTtl = refreshTtl;
    }

    @Transactional
    public SessionTokens issue(AppUser user) {
        return create(user);
    }

    @Transactional
    public SessionTokens refresh(String rawToken) {
        Instant now = Instant.now();
        String hash = hash(rawToken);
        AuthTokenRepository.RefreshToken stored = tokens.findActive(hash, now)
                .orElseThrow(AuthSessionService::invalidRefresh);
        if (tokens.revoke(hash, now) != 1) throw invalidRefresh();
        AppUser user = users.findById(stored.userId())
                .filter(AppUser::active)
                .filter(value -> value.authVersion() == stored.authVersion())
                .orElseThrow(AuthSessionService::invalidRefresh);
        return create(user);
    }

    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) tokens.revoke(hash(rawToken), Instant.now());
    }

    @Scheduled(cron = "${arm112.auth-token-cleanup-cron:0 15 3 * * *}")
    void cleanup() {
        tokens.deleteExpired(Instant.now().minus(Duration.ofDays(1)));
    }

    private SessionTokens create(AppUser user) {
        Instant now = Instant.now();
        Instant refreshExpiresAt = now.plus(refreshTtl);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.insert(hash(refreshToken), user.id(), user.authVersion(), now, refreshExpiresAt);
        return new SessionTokens(jwt.issue(user), refreshToken, refreshExpiresAt, user);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "Refresh-токен недействителен или истёк");
    }

    public record SessionTokens(JwtService.IssuedToken access, String refreshToken,
                                Instant refreshExpiresAt, AppUser user) {}
}
