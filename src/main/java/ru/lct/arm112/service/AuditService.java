package ru.lct.arm112.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiModels.AuditEntry;
import ru.lct.arm112.api.ApiModels.AuditPage;
import ru.lct.arm112.persistence.AuditRepository;
import ru.lct.arm112.security.CurrentUser;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Отдельный журнал действий всех ролей с хранением не менее 6 месяцев (решение №4). */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int PAYLOAD_LIMIT = 4096;
    private static final Pattern SECRETS = Pattern.compile(
            "\"(password|current|next|passwordHash)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    private final AuditRepository repository;
    private final SettingsService settings;

    public AuditService(AuditRepository repository, SettingsService settings) {
        this.repository = repository;
        this.settings = settings;
    }

    public void record(CurrentUser actor, String action, String resourceType, String resourceId,
                       Integer status, UUID requestId, String clientIp, String payload) {
        try {
            repository.insert(new AuditEntry(UUID.randomUUID(),
                    actor == null ? null : actor.id(), actor == null ? null : actor.login(),
                    actor == null || actor.role() == null ? null : actor.role().name(),
                    action, resourceType, resourceId, status, requestId, clientIp, sanitize(payload), Instant.now()));
        } catch (RuntimeException ex) {
            // аудит не должен ломать бизнес-операцию, но и молчать нельзя
            log.error("Не удалось записать аудит {}: {}", action, ex.getMessage());
        }
    }

    public AuditPage query(UUID actorId, String role, String action, Instant from, Instant to,
                           String cursor, int limit) {
        Instant before = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                before = Instant.ofEpochMilli(Long.parseLong(cursor));
            } catch (NumberFormatException ignored) {
                before = null;
            }
        }
        List<AuditEntry> items = repository.query(actorId, role, action, from, to, before, limit + 1);
        String next = null;
        if (items.size() > limit) {
            items = items.subList(0, limit);
            next = String.valueOf(items.get(items.size() - 1).occurredAt().toEpochMilli());
        }
        return new AuditPage(items, next);
    }

    /** Ежедневная чистка старше настроенного срока, но никогда меньше 180 дней. */
    @Scheduled(cron = "0 0 3 * * *")
    public void purge() {
        int days = Math.max(SettingsService.MIN_AUDIT_RETENTION_DAYS,
                settings.integer(SettingsService.AUDIT_RETENTION_DAYS, SettingsService.MIN_AUDIT_RETENTION_DAYS));
        int removed = repository.deleteOlderThan(Instant.now().minus(Duration.ofDays(days)));
        if (removed > 0) {
            log.info("Аудит: удалено {} записей старше {} дней", removed, days);
        }
    }

    static String sanitize(String payload) {
        if (payload == null) return null;
        String cleaned = SECRETS.matcher(payload).replaceAll("\"$1\":\"***\"");
        return cleaned.length() > PAYLOAD_LIMIT ? cleaned.substring(0, PAYLOAD_LIMIT) : cleaned;
    }
}
