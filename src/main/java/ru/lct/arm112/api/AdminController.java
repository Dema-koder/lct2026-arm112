package ru.lct.arm112.api;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.lct.arm112.persistence.SessionRepository;
import ru.lct.arm112.security.CurrentUser;
import ru.lct.arm112.service.AuditService;
import ru.lct.arm112.service.BackupService;
import ru.lct.arm112.service.EventService;
import ru.lct.arm112.service.LessonService;
import ru.lct.arm112.service.SettingsService;
import ru.lct.arm112.service.ServiceManagementService;
import ru.lct.arm112.service.PhoneCallAlertService;
import ru.lct.arm112.service.PhoneGatewayMockService;
import ru.lct.arm112.service.TrainingEngine;
import ru.lct.arm112.service.UserService;
import ru.lct.arm112.service.analytics.CalibrationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ru.lct.arm112.api.ApiModels.*;

/** Рабочее место администратора: учётки, группы, настройки, аудит, состояние, резервные копии — всё в UI (решение №9). */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final UserService users;
    private final LessonService lessons;
    private final SettingsService settings;
    private final AuditService audit;
    private final BackupService backups;
    private final EventService events;
    private final SessionRepository sessions;
    private final TrainingEngine engine;
    private final ServiceManagementService serviceManagement;
    private final PhoneCallAlertService serviceAlerts;
    private final PhoneGatewayMockService mockPhoneGateway;
    private final CalibrationService calibration;
    private final JdbcTemplate jdbc;
    private final String version;
    private final Path logFile;

    public AdminController(UserService users, LessonService lessons, SettingsService settings, AuditService audit,
                           BackupService backups, EventService events, SessionRepository sessions,
                           TrainingEngine engine, ServiceManagementService serviceManagement, JdbcTemplate jdbc,
                           PhoneCallAlertService serviceAlerts, PhoneGatewayMockService mockPhoneGateway,
                           CalibrationService calibration,
                           @Value("${arm112.version:0.3.0}") String version,
                           @Value("${arm112.log-file:./logs/arm112.log}") String logFile) {
        this.users = users;
        this.lessons = lessons;
        this.settings = settings;
        this.audit = audit;
        this.backups = backups;
        this.events = events;
        this.sessions = sessions;
        this.engine = engine;
        this.serviceManagement = serviceManagement;
        this.serviceAlerts = serviceAlerts;
        this.mockPhoneGateway = mockPhoneGateway;
        this.calibration = calibration;
        this.jdbc = jdbc;
        this.version = version;
        this.logFile = Path.of(logFile);
    }

    // ---------------------------------------------------------------- users

    @GetMapping("/users")
    public List<UserAdminView> userList(@RequestParam(required = false) String role,
                                        @RequestParam(required = false) Boolean active) {
        return users.list(role, active);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserAdminView createUser(@Valid @RequestBody UserCreate request, CurrentUser actor) {
        return users.create(request, actor.id());
    }

    @PutMapping("/users/{id}")
    public UserAdminView updateUser(@PathVariable UUID id, @Valid @RequestBody UserUpdate request, CurrentUser actor) {
        return users.update(id, request, actor.id());
    }

    @PostMapping("/users/{id}/block")
    public UserAdminView block(@PathVariable UUID id, CurrentUser actor) {
        return users.setActive(id, false, actor.id());
    }

    @PostMapping("/users/{id}/unblock")
    public UserAdminView unblock(@PathVariable UUID id, CurrentUser actor) {
        return users.setActive(id, true, actor.id());
    }

    @PostMapping("/users/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable UUID id, @Valid @RequestBody PasswordReset request) {
        users.resetPassword(id, request.password());
    }

    // ---------------------------------------------------------------- groups

    @GetMapping("/groups")
    public List<Group> groups(CurrentUser actor) {
        return lessons.groups(actor);
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public Group createGroup(@Valid @RequestBody GroupUpsert request, CurrentUser actor) {
        if (request.teacherId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Укажите преподавателя группы");
        }
        return lessons.createGroup(request, actor);
    }

    @PutMapping("/groups/{id}")
    public Group updateGroup(@PathVariable UUID id, @Valid @RequestBody GroupUpsert request) {
        return lessons.updateGroup(id, request);
    }

    @PutMapping("/groups/{id}/members")
    public Group updateGroupMembers(@PathVariable UUID id, @Valid @RequestBody GroupMembersUpdate request) {
        return lessons.replaceGroupMembers(id, request.memberIds());
    }

    // ---------------------------------------------------------------- correction of AI assessment

    @GetMapping("/assessment-calibration")
    public AdminCalibrationState calibration(@RequestParam(defaultValue = "CARD_FILL") String mode) {
        return calibration.state(mode);
    }

    @PostMapping("/assessment-calibration/activate")
    public AdminCalibrationState activateCalibration(@Valid @RequestBody CalibrationCommand request,
                                                       CurrentUser actor) {
        return calibration.activate(request.mode(), actor.id());
    }

    @PostMapping("/assessment-calibration/deactivate")
    public AdminCalibrationState deactivateCalibration(@Valid @RequestBody CalibrationCommand request,
                                                         CurrentUser actor) {
        return calibration.deactivate(request.mode(), actor.id());
    }

    // ---------------------------------------------------------------- settings

    @GetMapping("/settings")
    public Map<String, String> settings() {
        return settings.all();
    }

    @PutMapping("/settings")
    public Map<String, String> updateSettings(@RequestBody Map<String, String> patch, CurrentUser actor) {
        return settings.update(patch, actor.id());
    }

    // ---------------------------------------------------------------- audit / logs

    @GetMapping("/audit")
    public AuditPage auditLog(@RequestParam(required = false) UUID actorId,
                              @RequestParam(required = false) String role,
                              @RequestParam(required = false) String action,
                              @RequestParam(required = false) Instant from,
                              @RequestParam(required = false) Instant to,
                              @RequestParam(required = false) String cursor,
                              @RequestParam(defaultValue = "50") int limit) {
        return audit.query(actorId, role, action, from, to, cursor, Math.max(1, Math.min(limit, 500)));
    }

    @GetMapping("/system/log")
    public LogTail log(@RequestParam(defaultValue = "200") int lines) throws IOException {
        if (!Files.isRegularFile(logFile)) return new LogTail(List.of());
        List<String> all = Files.readAllLines(logFile);
        int from = Math.max(0, all.size() - Math.max(1, Math.min(lines, 2000)));
        return new LogTail(all.subList(from, all.size()));
    }

    // ---------------------------------------------------------------- system / backups

    @GetMapping("/system/health")
    public SystemHealth health() {
        String database;
        try {
            jdbc.queryForObject("select 1", Integer.class);
            database = "UP";
        } catch (RuntimeException ex) {
            database = "DOWN";
        }
        return new SystemHealth(database.equals("UP") ? "UP" : "DOWN", database, events.openSockets(),
                sessions.findByState("ACTIVE").size(), version, Instant.now());
    }

    @GetMapping("/system/services")
    public List<ManagedService> services() {
        return serviceManagement.list();
    }

    @PostMapping("/system/services/diagnostics")
    public List<ManagedService> checkServicesNow() {
        return serviceManagement.list();
    }

    @PostMapping("/system/services/{id}/actions")
    public ManagedService serviceAction(@PathVariable String id, @Valid @RequestBody ServiceAction request,
                                        CurrentUser actor) {
        return serviceManagement.execute(id, request.action(), actor);
    }

    @GetMapping("/system/services/history")
    public List<ServiceEvent> serviceHistory(@RequestParam(required = false) String serviceId,
                                             @RequestParam(defaultValue = "50") int limit) {
        return serviceManagement.history(serviceId, Math.max(1, Math.min(limit, 500)));
    }

    @GetMapping("/system/alerts")
    public AlertConfiguration alertConfiguration() {
        return new AlertConfiguration(serviceAlerts.configured(), "PHONE_CALL", serviceAlerts.recipientCount());
    }

    @PostMapping("/system/alerts/test")
    public NotificationTestResult testAlert() {
        if (!serviceAlerts.configured()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALERTS_NOT_CONFIGURED",
                    "Телефонные оповещения не настроены на сервере");
        }
        AlertCallAttempt attempt = serviceAlerts.sendDetailed(
                "ARM-112: тестовое уведомление администратора", "TEST", null);
        if (attempt.status().equals("FAILED")) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ALERT_DELIVERY_FAILED",
                    "Телефонный шлюз не подтвердил запуск тестового звонка");
        }
        return new NotificationTestResult("SENT", "Тестовый звонок запущен");
    }

    @GetMapping("/system/alerts/mock")
    public MockPhoneGatewaySettings mockAlertSettings() {
        return mockPhoneGateway.status();
    }

    @PutMapping("/system/alerts/mock")
    public MockPhoneGatewaySettings updateMockAlertSettings(@Valid @RequestBody MockPhoneGatewayUpdate request) {
        return mockPhoneGateway.update(request);
    }

    @GetMapping("/system/alerts/history")
    public List<AlertCallAttempt> alertHistory(@RequestParam(defaultValue = "50") int limit) {
        return serviceAlerts.history(Math.max(1, Math.min(limit, 500)));
    }

    @PostMapping("/system/alerts/history/{id}/retry")
    public AlertCallAttempt retryAlert(@PathVariable UUID id) {
        return serviceAlerts.retry(id);
    }

    @GetMapping("/backups")
    public List<BackupInfo> backupList() {
        return backups.list();
    }

    @PostMapping("/backups")
    @ResponseStatus(HttpStatus.CREATED)
    public BackupInfo createBackup() {
        return backups.create();
    }

    @PostMapping("/backups/{fileName}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable String fileName, @Valid @RequestBody RestoreRequest request) {
        if (!"RESTORE".equals(request.confirm())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONFIRMATION_REQUIRED",
                    "Для восстановления введите слово RESTORE");
        }
        backups.restore(fileName);
        // память процесса должна отражать восстановленную базу
        settings.reload();
        engine.reload();
    }
}
