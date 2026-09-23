package ru.lct.arm112.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.persistence.LoginThrottleRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LoginThrottleService {
    private final LoginThrottleRepository repository;
    private final int maxFailures;
    private final Duration window;
    private final Duration blockDuration;

    public LoginThrottleService(LoginThrottleRepository repository,
                                @Value("${arm112.login-throttle.max-failures:5}") int maxFailures,
                                @Value("${arm112.login-throttle.window:PT10M}") Duration window,
                                @Value("${arm112.login-throttle.block-duration:PT15M}") Duration blockDuration) {
        this.repository = repository;
        this.maxFailures = maxFailures;
        this.window = window;
        this.blockDuration = blockDuration;
    }

    @Transactional
    public void check(String login, String ip) {
        for (String key : keys(login, ip)) {
            Instant now = Instant.now();
            repository.ensure(key, now);
            LoginThrottleRepository.State state = repository.lock(key).orElseThrow();
            if (state.blockedUntil() != null && state.blockedUntil().isAfter(now)) {
                long retry = Math.max(1, Duration.between(now, state.blockedUntil()).toSeconds());
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_TEMPORARILY_BLOCKED",
                        "Слишком много неудачных попыток входа", List.of(), Map.of("retryAfterSeconds", retry));
            }
        }
    }

    @Transactional
    public void failure(String login, String ip) {
        Instant now = Instant.now();
        for (String key : keys(login, ip)) {
            repository.ensure(key, now);
            LoginThrottleRepository.State state = repository.lock(key).orElseThrow();
            Instant windowStart = state.windowStarted();
            int failures = state.failures();
            if (windowStart.plus(window).isBefore(now)) {
                windowStart = now;
                failures = 0;
            }
            failures++;
            int limit = key.startsWith("ip:") ? maxFailures * 5 : maxFailures;
            Instant blockedUntil = failures >= limit ? now.plus(blockDuration) : null;
            repository.update(key, failures, windowStart, blockedUntil, now);
        }
    }

    public void success(String login, String ip) {
        keys(login, ip).forEach(repository::delete);
    }

    @Scheduled(cron = "${arm112.login-throttle.cleanup-cron:0 30 3 * * *}")
    void cleanup() {
        repository.deleteOlderThan(Instant.now().minus(Duration.ofDays(2)));
    }

    private static List<String> keys(String login, String ip) {
        String normalized = login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
        return List.of("login:" + normalized, "ip:" + (ip == null ? "unknown" : ip));
    }
}
