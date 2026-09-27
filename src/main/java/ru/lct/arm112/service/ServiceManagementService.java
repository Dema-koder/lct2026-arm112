package ru.lct.arm112.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.ManagedService;
import ru.lct.arm112.api.ApiModels.ServiceMetric;
import ru.lct.arm112.persistence.JobRepository;
import ru.lct.arm112.persistence.SessionRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Безопасное управление компонентами в рантайме.
 *
 * <p>Процесс приложения и база намеренно доступны только для просмотра: остановленный backend
 * не сможет принять команду запуска, а Docker socket внутри приложения был бы лишним системным
 * доступом. Их жизненным циклом управляет Docker/CI. Учебные подсистемы можно переключать из UI,
 * состояние хранится в {@code app_setting} и переживает перезапуск.
 */
@Service
public class ServiceManagementService {
    private static final Map<String, String> KEYS = Map.of(
            "simulation", SettingsService.SERVICE_SIMULATION_ENABLED,
            "telephony", SettingsService.SERVICE_TELEPHONY_ENABLED,
            "realtime", SettingsService.SERVICE_REALTIME_ENABLED,
            "jobs", SettingsService.SERVICE_JOBS_ENABLED);

    private final SettingsService settings;
    private final JdbcTemplate jdbc;
    private final EventService events;
    private final SessionRepository sessions;
    private final JobRepository jobs;
    private final TrainingEngine engine;
    private final String version;

    public ServiceManagementService(SettingsService settings, JdbcTemplate jdbc, EventService events,
                                    SessionRepository sessions, JobRepository jobs, TrainingEngine engine,
                                    @Value("${arm112.version:0.3.0}") String version) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.events = events;
        this.sessions = sessions;
        this.jobs = jobs;
        this.engine = engine;
        this.version = version;
    }

    public List<ManagedService> list() {
        String databaseState = "RUNNING";
        String databaseVersion = "—";
        try {
            databaseVersion = jdbc.queryForObject("select current_setting('server_version')", String.class);
        } catch (RuntimeException exception) {
            databaseState = "FAILED";
        }
        return List.of(
                fixed("frontend", "Веб-интерфейс", "Доступен через браузер; перезапускается средствами Docker/CI.",
                        "RUNNING", false, true, List.of(new ServiceMetric("Состояние", "страница загружена"))),
                fixed("backend", "Backend API", "Основное приложение; жизненным циклом управляет Docker/CI.",
                        "RUNNING", false, true, List.of(new ServiceMetric("Версия", version))),
                fixed("database", "PostgreSQL", "Основная база; остановка из приложения запрещена.",
                        databaseState, false, true, List.of(new ServiceMetric("Версия", databaseVersion))),
                runtime("simulation", "Движок симуляции", "Доставка вводных и контроль нормативов времени.",
                        List.of(new ServiceMetric("Активных сессий", String.valueOf(sessions.findByState("ACTIVE").size()))), true),
                runtime("telephony", "Симулятор телефонии", "Исходящие учебные звонки и смена их состояний.",
                        List.of(new ServiceMetric("Активных звонков", String.valueOf(engine.activeCallCount()))), false),
                runtime("realtime", "Доставка событий", "WebSocket-уведомления и повтор недоставленных событий.",
                        List.of(new ServiceMetric("Подключений", String.valueOf(events.openSockets()))), false),
                runtime("jobs", "Фоновые задания", "Генерация сценариев и персональные разборы занятий.",
                        List.of(new ServiceMetric("В очереди", String.valueOf(jobs.countByState(JobRepository.READY))),
                                new ServiceMetric("В работе", String.valueOf(jobs.countByState(JobRepository.RUNNING))),
                                new ServiceMetric("С ошибкой", String.valueOf(jobs.countByState(JobRepository.FAILED)))), false)
        );
    }

    public ManagedService execute(String id, String requestedAction, UUID actorId) {
        String key = KEYS.get(id);
        if (key == null) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_NOT_CONTROLLABLE",
                    "Этот компонент управляется через Docker/CI и недоступен для переключения из приложения");
        }
        boolean wasEnabled = settings.enabled(key);
        String action = requestedAction == null ? "" : requestedAction.trim().toUpperCase(Locale.ROOT);
        switch (action) {
            case "START" -> settings.update(Map.of(key, "true"), actorId);
            case "STOP" -> settings.update(Map.of(key, "false"), actorId);
            case "RESTART" -> {
                settings.update(Map.of(key, "false"), actorId);
                settings.update(Map.of(key, "true"), actorId);
            }
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Допустимые действия: START, STOP, RESTART");
        }
        if (id.equals("telephony") && !wasEnabled && settings.enabled(key)) engine.resumeTelephony();
        return list().stream().filter(service -> service.id().equals(id)).findFirst().orElseThrow();
    }

    private ManagedService runtime(String id, String label, String description,
                                   List<ServiceMetric> metrics, boolean critical) {
        boolean enabled = settings.enabled(KEYS.get(id));
        return new ManagedService(id, label, description, enabled ? "RUNNING" : "STOPPED",
                true, critical, metrics, enabled ? List.of("STOP", "RESTART") : List.of("START"));
    }

    private ManagedService fixed(String id, String label, String description, String state,
                                 boolean controllable, boolean critical, List<ServiceMetric> metrics) {
        return new ManagedService(id, label, description, state, controllable, critical, metrics, List.of());
    }
}
