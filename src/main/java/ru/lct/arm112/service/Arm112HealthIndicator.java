package ru.lct.arm112.service;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import ru.lct.arm112.persistence.JobRepository;

@Component("arm112Operations")
public class Arm112HealthIndicator implements HealthIndicator {
    private final BackupService backups;
    private final JobRepository jobs;
    private final EventService events;

    public Arm112HealthIndicator(BackupService backups, JobRepository jobs, EventService events) {
        this.backups = backups;
        this.jobs = jobs;
        this.events = events;
    }

    @Override
    public Health health() {
        BackupService.BackupHealth backup = backups.health();
        Health.Builder result = backup.healthy() ? Health.up() : Health.down();
        return result.withDetail("backup", backup)
                .withDetail("jobsReady", jobs.countByState(JobRepository.READY))
                .withDetail("jobsRunning", jobs.countByState(JobRepository.RUNNING))
                .withDetail("jobsFailed", jobs.countByState(JobRepository.FAILED))
                .withDetail("websocketConnections", events.openSockets())
                .build();
    }
}
