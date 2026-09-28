package ru.lct.arm112.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiModels.ManagedService;
import ru.lct.arm112.api.ApiModels.ServiceEvent;
import ru.lct.arm112.persistence.ServiceEventRepository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Фиксирует смену состояния и посылает одно оповещение на переход, без спама при каждом опросе. */
@Service
public class ServiceStateMonitor {
    private static final Logger log = LoggerFactory.getLogger(ServiceStateMonitor.class);
    private final ServiceManagementService services;
    private final ServiceEventRepository events;
    private final PhoneCallAlertService alerts;
    private final Map<String, String> previous = new ConcurrentHashMap<>();
    private volatile boolean checkFailed;

    public ServiceStateMonitor(ServiceManagementService services, ServiceEventRepository events,
                               PhoneCallAlertService alerts) {
        this.services = services;
        this.events = events;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelayString = "${arm112.service-monitor.interval-ms:30000}",
            initialDelayString = "${arm112.service-monitor.initial-delay-ms:15000}")
    public void check() {
        try {
            for (ManagedService service : services.list()) {
                String old = previous.put(service.id(), service.state());
                boolean problem = service.state().equals("FAILED") || service.state().equals("DEGRADED");
                if ((old == null && !problem) || service.state().equals(old)) continue;
                boolean recovery = old != null && (old.equals("FAILED") || old.equals("DEGRADED"));
                String message = problem
                        ? "ARM-112: сервис «" + service.label() + "» перешёл в состояние " + service.state()
                        : "ARM-112: сервис «" + service.label() + "» восстановлен (" + service.state() + ")";
                boolean notified = (problem || recovery) && alerts.send(message,
                        recovery ? "SERVICE_RECOVERY" : "SERVICE_PROBLEM", service.id());
                events.insert(new ServiceEvent(UUID.randomUUID(), service.id(), "STATE_CHANGE", old,
                        service.state(), null, "SUCCESS", message, null, null, notified, Instant.now()));
            }
            if (checkFailed) {
                String message = "ARM-112: автоматическая проверка сервисов снова работает";
                boolean notified = alerts.send(message, "MONITOR_RECOVERY", "database");
                events.insert(new ServiceEvent(UUID.randomUUID(), "database", "STATE_CHANGE", "FAILED",
                        "RUNNING", null, "SUCCESS", message, null, null, notified, Instant.now()));
                checkFailed = false;
            }
        } catch (RuntimeException exception) {
            log.warn("Проверка состояния сервисов завершилась ошибкой: {}", exception.getMessage());
            if (!checkFailed) {
                checkFailed = true;
                alerts.send("ARM-112: автоматическая проверка сервисов недоступна; проверьте backend и PostgreSQL",
                        "MONITOR_FAILURE", "database");
            }
        }
    }
}
