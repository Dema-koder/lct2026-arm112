package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Очередь фоновых задач на таблице: {@code SELECT ... FOR UPDATE SKIP LOCKED}.
 *
 * <p>Гарантия — «хотя бы один раз». Исполнитель может упасть после работы, но до отметки
 * о завершении, и тогда задача будет взята повторно. Поэтому обработчики обязаны быть
 * идемпотентными; для генерации сценариев это выполняется естественно — повторный прогон
 * создаст ещё сценарии, а лишние преподаватель не подтвердит.
 */
@Repository
public class JobRepository {

    public static final String READY = "READY";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    /** Сколько задача считается занятой исполнителем; после — её можно забрать заново. */
    private static final Duration LEASE = Duration.ofMinutes(30);
    /** Пауза перед повтором растёт с числом неудач: 1, 4, 9 минут. */
    private static final Duration RETRY_BASE = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
    private final RowMapper<JobRow> mapper = JobRepository::map;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record JobRow(UUID id, String type, String state, String payload, String result, String error,
                         int attempts, int maxAttempts, Instant nextAttemptAt, Instant leaseUntil,
                         UUID createdBy, Instant createdAt, Instant startedAt, Instant finishedAt) {}

    /**
     * Поставить задачу. Вызывается внутри транзакции того, кто её породил, — поэтому
     * задача и породившая запись появляются вместе или не появляются вовсе.
     */
    public UUID enqueue(String type, String payload, UUID createdBy, int maxAttempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into job (id, type, state, payload, max_attempts) values (?, ?, ?, ?, ?)
                """, id, type, READY, payload, maxAttempts);
        if (createdBy != null) {
            jdbc.update("update job set created_by = ? where id = ?", createdBy, id);
        }
        return id;
    }

    /**
     * Забрать очередную задачу.
     *
     * <p>{@code SKIP LOCKED} пропускает строки, уже заблокированные другим исполнителем,
     * поэтому несколько исполнителей разбирают очередь без ожидания друг друга и без
     * риска взять одну задачу дважды. Истёкшая аренда возвращает задачу в оборот —
     * так подбирается работа за упавшим исполнителем.
     */
    public Optional<JobRow> claim(List<String> types) {
        if (types.isEmpty()) return Optional.empty();
        String placeholders = String.join(",", types.stream().map(t -> "?").toList());
        Object[] args = new Object[types.size() + 1];
        args[0] = Timestamp.from(Instant.now());
        for (int i = 0; i < types.size(); i++) args[i + 1] = types.get(i);

        List<UUID> ids = jdbc.query("""
                select id from job
                 where next_attempt_at <= ?
                   and type in (%s)
                   and (state = 'READY' or (state = 'RUNNING' and lease_until < current_timestamp))
                 order by created_at
                 limit 1
                 for update skip locked
                """.formatted(placeholders), (rs, row) -> rs.getObject("id", UUID.class), args);
        if (ids.isEmpty()) return Optional.empty();

        UUID id = ids.get(0);
        int updated = jdbc.update("""
                update job set state = ?, attempts = attempts + 1, started_at = coalesce(started_at, current_timestamp),
                               lease_until = ?
                 where id = ? and (state = 'READY' or (state = 'RUNNING' and lease_until < current_timestamp))
                """, RUNNING, Timestamp.from(Instant.now().plus(LEASE)), id);
        // строку успел забрать другой исполнитель между выборкой и обновлением
        return updated == 0 ? Optional.empty() : findById(id);
    }

    public void complete(UUID id, String result) {
        jdbc.update("""
                update job set state = ?, result = ?, error = null, finished_at = current_timestamp,
                               lease_until = null
                 where id = ?
                """, DONE, result, id);
    }

    /**
     * Отметить неудачу: либо назначить повтор с растущей паузой, либо признать задачу
     * проваленной, если попытки исчерпаны. Проваленная задача остаётся в таблице —
     * преподаватель должен увидеть, что генерация не удалась, а не гадать.
     */
    public void fail(UUID id, String error) {
        JobRow job = findById(id).orElse(null);
        if (job == null) return;
        String message = error == null ? "" : error.length() > 2000 ? error.substring(0, 2000) : error;
        if (job.attempts() >= job.maxAttempts()) {
            jdbc.update("""
                    update job set state = ?, error = ?, finished_at = current_timestamp, lease_until = null
                     where id = ?
                    """, FAILED, message, id);
            return;
        }
        long delaySeconds = RETRY_BASE.toSeconds() * (long) job.attempts() * job.attempts();
        jdbc.update("""
                update job set state = ?, error = ?, next_attempt_at = ?, lease_until = null
                 where id = ?
                """, READY, message, Timestamp.from(Instant.now().plusSeconds(delaySeconds)), id);
    }

    public Optional<JobRow> findById(UUID id) {
        return jdbc.query("select * from job where id = ?", mapper, id).stream().findFirst();
    }

    /** Задачи, поставленные преподавателем, — для показа статуса в его интерфейсе. */
    public List<JobRow> findByCreator(UUID createdBy, int limit) {
        return jdbc.query("select * from job where created_by = ? order by created_at desc limit ?",
                mapper, createdBy, limit);
    }

    public int countByState(String state) {
        Integer count = jdbc.queryForObject("select count(*) from job where state = ?", Integer.class, state);
        return count == null ? 0 : count;
    }

    private static JobRow map(ResultSet rs, int row) throws SQLException {
        return new JobRow(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("state"),
                rs.getString("payload"), rs.getString("result"), rs.getString("error"),
                rs.getInt("attempts"), rs.getInt("max_attempts"),
                instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("lease_until")),
                rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
