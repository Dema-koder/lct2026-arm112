package ru.lct.arm112.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import ru.lct.arm112.persistence.JobRepository;

@Service
public class OperationalMetrics {
    private final Counter loginSuccess;
    private final Counter loginFailure;
    private final Counter loginBlocked;
    private final Counter realtimePublished;
    private final Counter realtimeDeliveryFailure;
    private final Counter backupSuccess;
    private final Counter backupFailure;

    public OperationalMetrics(MeterRegistry registry, JobRepository jobs) {
        loginSuccess = registry.counter("arm112.auth.login", "result", "success");
        loginFailure = registry.counter("arm112.auth.login", "result", "failure");
        loginBlocked = registry.counter("arm112.auth.login", "result", "blocked");
        realtimePublished = registry.counter("arm112.realtime.events", "result", "published");
        realtimeDeliveryFailure = registry.counter("arm112.realtime.events", "result", "delivery_failure");
        backupSuccess = registry.counter("arm112.backup.runs", "result", "success");
        backupFailure = registry.counter("arm112.backup.runs", "result", "failure");
        Gauge.builder("arm112.jobs.ready", jobs, value -> value.countByState(JobRepository.READY)).register(registry);
        Gauge.builder("arm112.jobs.running", jobs, value -> value.countByState(JobRepository.RUNNING)).register(registry);
        Gauge.builder("arm112.jobs.failed", jobs, value -> value.countByState(JobRepository.FAILED)).register(registry);
    }

    public void loginSuccess() { loginSuccess.increment(); }
    public void loginFailure() { loginFailure.increment(); }
    public void loginBlocked() { loginBlocked.increment(); }
    public void realtimePublished() { realtimePublished.increment(); }
    public void realtimeDeliveryFailure() { realtimeDeliveryFailure.increment(); }
    public void backupSuccess() { backupSuccess.increment(); }
    public void backupFailure() { backupFailure.increment(); }
}
