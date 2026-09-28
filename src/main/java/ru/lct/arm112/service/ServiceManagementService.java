package ru.lct.arm112.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.ManagedService;
import ru.lct.arm112.api.ApiModels.ServiceEvent;
import ru.lct.arm112.api.ApiModels.ServiceMetric;
import ru.lct.arm112.persistence.JobRepository;
import ru.lct.arm112.persistence.SessionRepository;
import ru.lct.arm112.persistence.ServiceEventRepository;
import ru.lct.arm112.security.CurrentUser;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ServiceEventRepository history;
    private final TrainingEngine engine;
    private final String version;
    private final Map<String, Instant> lastSuccessfulChecks = new ConcurrentHashMap<>();

    public ServiceManagementService(SettingsService settings, JdbcTemplate jdbc, EventService events,
                                    SessionRepository sessions, JobRepository jobs, TrainingEngine engine,
                                    ServiceEventRepository history,
                                    @Value("${arm112.version:0.3.0}") String version) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.events = events;
        this.sessions = sessions;
        this.jobs = jobs;
        this.history = history;
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
        List<ManagedService> raw = List.of(
                fixed("frontend", "Веб-интерфейс", "Доступен через браузер; перезапускается средствами Docker/CI.",
                        "Показывает рабочие места обучающегося, преподавателя и администратора.",
                        "Страницы временно перестанут открываться.", "После обновления или если страницы не загружаются.",
                        "RUNNING", false, true, List.of(new ServiceMetric("Состояние", "страница загружена"))),
                fixed("backend", "Backend API", "Основное приложение; жизненным циклом управляет Docker/CI.",
                        "Выполняет всю бизнес-логику и связывает интерфейс с базой данных.",
                        "Все действия в системе станут недоступны.", "После обновления, изменения серверных настроек или ошибки healthcheck.",
                        "RUNNING", false, true, List.of(new ServiceMetric("Версия", version))),
                fixed("database", "PostgreSQL", "Основная база; остановка из приложения запрещена.",
                        "Хранит пользователей, занятия, карточки, оценки и системную историю.",
                        "Чтение и сохранение данных станет невозможно.", "Только при обслуживании БД специалистом и при отсутствии пользователей.",
                        databaseState, false, true, List.of(new ServiceMetric("Версия", databaseVersion))),
                runtime("simulation", "Движок симуляции", "Доставка вводных и контроль нормативов времени.",
                        "По расписанию выдаёт карточки, поддерживает их одновременную обработку и считает время реакции.",
                        "Новые карточки и таймеры занятий будут поставлены на паузу.",
                        "Если карточки перестали приходить или таймеры занятия не двигаются.",
                        List.of(new ServiceMetric("Активных сессий", String.valueOf(sessions.findByState("ACTIVE").size()))), true),
                runtime("telephony", "Симулятор телефонии", "Исходящие учебные звонки и смена их состояний.",
                        "Имитирует звонки руководителям: гудки, соединение и ответ абонента.",
                        "Новые учебные звонки не начнутся; данные текущих звонков сохранятся.",
                        "Если звонок завис в одном состоянии или перестал воспроизводиться ответ.",
                        List.of(new ServiceMetric("Активных звонков", String.valueOf(engine.activeCallCount()))), false),
                runtime("realtime", "Доставка событий", "WebSocket-уведомления и повтор недоставленных событий.",
                        "Мгновенно передаёт в браузеры новые карточки, статусы и изменения без обновления страницы.",
                        "Данные сохранятся, но экраны могут обновляться с задержкой до запуска модуля.",
                        "Если данные есть после обновления страницы, но не появляются автоматически.",
                        List.of(new ServiceMetric("Подключений", String.valueOf(events.openSockets()))), false),
                runtime("jobs", "Фоновые задания", "Генерация сценариев и персональные разборы занятий.",
                        "Выполняет долгие операции в очереди, не блокируя работу пользователя.",
                        "Очередь сохранится, но генерация и разборы будут ждать запуска.",
                        "Если число «В очереди» долго не уменьшается или появились задания «С ошибкой».",
                        List.of(new ServiceMetric("В очереди", String.valueOf(jobs.countByState(JobRepository.READY))),
                                new ServiceMetric("В работе", String.valueOf(jobs.countByState(JobRepository.RUNNING))),
                                new ServiceMetric("С ошибкой", String.valueOf(jobs.countByState(JobRepository.FAILED)))), false,
                        jobs.countByState(JobRepository.FAILED) > 0 ? "DEGRADED" : null)
        );
        return raw.stream().map(this::diagnose).toList();
    }

    public ManagedService execute(String id, String requestedAction, CurrentUser actor) {
        String key = KEYS.get(id);
        if (key == null) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_NOT_CONTROLLABLE",
                    "Этот компонент управляется через Docker/CI и недоступен для переключения из приложения");
        }
        boolean wasEnabled = settings.enabled(key);
        String previousState = wasEnabled ? "RUNNING" : "STOPPED";
        String action = requestedAction == null ? "" : requestedAction.trim().toUpperCase(Locale.ROOT);
        if (!List.of("START", "STOP", "RESTART").contains(action)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Допустимые действия: START, STOP, RESTART");
        }
        try {
            switch (action) {
                case "START" -> settings.update(Map.of(key, "true"), actor.id());
                case "STOP" -> settings.update(Map.of(key, "false"), actor.id());
                case "RESTART" -> {
                    settings.update(Map.of(key, "false"), actor.id());
                    settings.update(Map.of(key, "true"), actor.id());
                }
                default -> throw new IllegalStateException("Неизвестное действие после валидации");
            }
            if (id.equals("telephony") && !wasEnabled && settings.enabled(key)) engine.resumeTelephony();
            ManagedService result = list().stream().filter(service -> service.id().equals(id)).findFirst().orElseThrow();
            record(id, previousState, result.state(), action, "SUCCESS", "Команда выполнена", actor, false);
            return result;
        } catch (RuntimeException exception) {
            String currentState = settings.enabled(key) ? "RUNNING" : "STOPPED";
            record(id, previousState, currentState, action, "FAILED", exception.getMessage(), actor, false);
            throw exception;
        }
    }

    public List<ServiceEvent> history(String serviceId, int limit) {
        return history.list(serviceId, limit);
    }

    private void record(String serviceId, String previousState, String currentState, String action,
                        String outcome, String message, CurrentUser actor, boolean notified) {
        history.insert(new ServiceEvent(UUID.randomUUID(), serviceId, "ACTION",
                previousState, currentState, action, outcome, message, actor.id(), actor.login(), notified, Instant.now()));
    }

    private ManagedService runtime(String id, String label, String description, String purpose,
                                   String stopEffect, String restartWhen,
                                   List<ServiceMetric> metrics, boolean critical) {
        return runtime(id, label, description, purpose, stopEffect, restartWhen, metrics, critical, null);
    }

    private ManagedService runtime(String id, String label, String description, String purpose,
                                   String stopEffect, String restartWhen,
                                   List<ServiceMetric> metrics, boolean critical, String healthState) {
        boolean enabled = settings.enabled(KEYS.get(id));
        String state = enabled ? (healthState == null ? "RUNNING" : healthState) : "STOPPED";
        return new ManagedService(id, label, description, purpose, stopEffect, restartWhen, state,
                true, critical, metrics, enabled ? List.of("STOP", "RESTART") : List.of("START"),
                null, null, null, null, null, List.of());
    }

    private ManagedService fixed(String id, String label, String description, String purpose,
                                 String stopEffect, String restartWhen, String state,
                                 boolean controllable, boolean critical, List<ServiceMetric> metrics) {
        return new ManagedService(id, label, description, purpose, stopEffect, restartWhen, state,
                controllable, critical, metrics, List.of(), null, null, null, null, null, List.of());
    }

    private ManagedService diagnose(ManagedService service) {
        Instant checkedAt = Instant.now();
        long started = System.nanoTime();
        String state = service.state();
        String issue = defaultIssue(service);
        try {
            switch (service.id()) {
                case "database" -> jdbc.queryForObject("select 1", Integer.class);
                case "simulation" -> sessions.findByState("ACTIVE").size();
                case "telephony" -> engine.activeCallCount();
                case "realtime" -> events.openSockets();
                case "jobs" -> jobs.countByState(JobRepository.READY);
                default -> { /* frontend/backend подтверждаются самим успешным ответом API */ }
            }
        } catch (RuntimeException exception) {
            state = "FAILED";
            issue = "Проверка завершилась ошибкой: " + safeMessage(exception);
        }
        Long responseTimeMs = service.id().equals("frontend") ? null
                : Math.max(0, (System.nanoTime() - started) / 1_000_000);
        if (state.equals("RUNNING")) lastSuccessfulChecks.put(service.id(), checkedAt);
        Instant lastSuccessful = lastSuccessfulChecks.get(service.id());
        return new ManagedService(service.id(), service.label(), service.description(), service.purpose(),
                service.stopEffect(), service.restartWhen(), state, service.controllable(), service.critical(),
                service.metrics(), service.allowedActions(), checkedAt, lastSuccessful, responseTimeMs,
                issue, recommendation(service.id(), state, issue), dependencies(service.id()));
    }

    private String defaultIssue(ManagedService service) {
        if (service.state().equals("STOPPED")) return "Остановлен администратором";
        if (service.state().equals("DEGRADED") && service.id().equals("jobs")) {
            String failed = service.metrics().stream().filter(metric -> metric.label().equals("С ошибкой"))
                    .map(ServiceMetric::value).findFirst().orElse("несколько");
            return "Фоновые задания с ошибкой: " + failed;
        }
        if (service.state().equals("FAILED")) return "Компонент не прошёл проверку доступности";
        return null;
    }

    private String recommendation(String id, String state, String issue) {
        if (state.equals("RUNNING")) return "Действий не требуется";
        if (state.equals("STOPPED")) return KEYS.containsKey(id)
                ? "Запустите модуль, когда его функции снова понадобятся"
                : "Запустите компонент средствами Docker/CI";
        if (id.equals("jobs") && state.equals("DEGRADED")) {
            return "Проверьте ошибки фоновых заданий; если очередь не движется — перезапустите модуль";
        }
        return serviceRestartRecommendation(id, issue);
    }

    private String serviceRestartRecommendation(String id, String issue) {
        return switch (id) {
            case "database" -> "Проверьте PostgreSQL и место на диске; не перезапускайте БД при активных пользователях";
            case "backend", "frontend" -> "Проверьте журнал и healthcheck, затем выполните перевыкладку через CI";
            default -> "Проверьте журнал; если причина не устранена — перезапустите модуль";
        };
    }

    private List<String> dependencies(String id) {
        return switch (id) {
            case "frontend" -> List.of("Backend API");
            case "backend" -> List.of("PostgreSQL");
            case "database" -> List.of();
            case "simulation" -> List.of("Backend API", "PostgreSQL", "Доставка событий");
            case "telephony" -> List.of("Backend API", "PostgreSQL");
            case "realtime" -> List.of("Backend API", "PostgreSQL");
            case "jobs" -> List.of("Backend API", "PostgreSQL");
            default -> List.of();
        };
    }

    private static String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) return exception.getClass().getSimpleName();
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
