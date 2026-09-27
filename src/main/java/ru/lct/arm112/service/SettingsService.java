package ru.lct.arm112.service;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.persistence.SettingsRepository;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Настройки администратора, читаемые в рантайме (решение №9). */
@Service
public class SettingsService {
    public static final String AUDIT_RETENTION_DAYS = "audit.retention_days";
    public static final String RINGING_MS = "telephony.ringing_ms";
    public static final String CONNECT_MS = "telephony.connect_ms";
    public static final String ACKNOWLEDGE_MS = "telephony.acknowledge_ms";
    public static final String ACCEPTANCE_SECONDS = "sla.acceptance_seconds";
    public static final String PROCESSING_SECONDS = "sla.processing_seconds";
    /** Минимальный правдоподобный интервал между статусами реагирования: меньше — прокликивание. */
    public static final String MIN_REACTION_SECONDS = "sla.min_reaction_seconds";
    public static final String CARD_OPEN_MS = "simulation.card_open_ms";
    /** Базовый шаг прихода вводных; интенсивность занятия работает множителем к нему. */
    public static final String CARD_ARRIVAL_MS = "simulation.card_arrival_ms";
    public static final String SERVICE_TIME_SCALE_PERCENT = "simulation.service_time_scale_percent";
    public static final String LOGGING_LEVEL = "logging.level";
    public static final String SERVICE_SIMULATION_ENABLED = "service.simulation.enabled";
    public static final String SERVICE_TELEPHONY_ENABLED = "service.telephony.enabled";
    public static final String SERVICE_REALTIME_ENABLED = "service.realtime.enabled";
    public static final String SERVICE_JOBS_ENABLED = "service.jobs.enabled";

    /** ТЗ: хранение журналов безопасности не менее 6 месяцев — ниже опустить нельзя. */
    public static final int MIN_AUDIT_RETENTION_DAYS = 180;
    private static final Set<String> KNOWN = Set.of(AUDIT_RETENTION_DAYS, RINGING_MS, CONNECT_MS,
            ACKNOWLEDGE_MS, ACCEPTANCE_SECONDS, PROCESSING_SECONDS, MIN_REACTION_SECONDS,
            CARD_OPEN_MS, CARD_ARRIVAL_MS, SERVICE_TIME_SCALE_PERCENT, LOGGING_LEVEL,
            SERVICE_SIMULATION_ENABLED, SERVICE_TELEPHONY_ENABLED,
            SERVICE_REALTIME_ENABLED, SERVICE_JOBS_ENABLED);
    private static final Set<String> BOOLEAN = Set.of(SERVICE_SIMULATION_ENABLED, SERVICE_TELEPHONY_ENABLED,
            SERVICE_REALTIME_ENABLED, SERVICE_JOBS_ENABLED);

    private final SettingsRepository repository;
    private final LoggingSystem loggingSystem;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SettingsService(SettingsRepository repository, LoggingSystem loggingSystem) {
        this.repository = repository;
        this.loggingSystem = loggingSystem;
    }

    @PostConstruct
    void load() {
        cache.putAll(repository.loadAll());
        applyLogging();
    }

    public void reload() {
        cache.clear();
        load();
    }

    public Map<String, String> all() {
        return Map.copyOf(cache);
    }

    public int integer(String key, int fallback) {
        try {
            return Integer.parseInt(cache.getOrDefault(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public String text(String key, String fallback) {
        return cache.getOrDefault(key, fallback);
    }

    public boolean enabled(String key) {
        return Boolean.parseBoolean(cache.getOrDefault(key, "true"));
    }

    public Map<String, String> update(Map<String, String> patch, UUID actor) {
        for (Map.Entry<String, String> entry : patch.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!KNOWN.contains(key)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                        "Неизвестная настройка: " + key);
            }
            if (BOOLEAN.contains(key)) {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                            "Настройка " + key + " должна иметь значение true или false");
                }
                value = value.toLowerCase();
            } else if (key.equals(LOGGING_LEVEL)) {
                if (LogLevel.valueOf(value.toUpperCase()) == null) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Некорректный уровень логирования");
                }
                value = value.toUpperCase();
            } else {
                int number;
                try {
                    number = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                            "Настройка " + key + " должна быть целым числом");
                }
                if (key.equals(AUDIT_RETENTION_DAYS) && number < MIN_AUDIT_RETENTION_DAYS) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                            "Срок хранения аудита не может быть меньше " + MIN_AUDIT_RETENTION_DAYS + " дней (ТЗ: 6 месяцев)");
                }
                if (number < 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                            "Настройка " + key + " не может быть отрицательной");
                }
            }
            repository.upsert(key, value, actor);
            cache.put(key, value);
        }
        applyLogging();
        return all();
    }

    private void applyLogging() {
        try {
            loggingSystem.setLogLevel("ru.lct.arm112", LogLevel.valueOf(text(LOGGING_LEVEL, "INFO")));
        } catch (Exception ignored) {
            // некорректное значение в БД не должно ронять старт
        }
    }
}
