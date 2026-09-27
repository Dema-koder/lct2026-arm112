package ru.lct.arm112.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.lct.arm112.persistence.JobRepository;
import ru.lct.arm112.persistence.JobRepository.JobRow;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Свойства очереди, ради которых она вообще нужна.
 *
 * <p>Очередь без этих гарантий хуже её отсутствия: задачи теряются или дублируются молча,
 * и обнаруживается это на занятии с группой, а не в разработке.
 */
@SpringBootTest
class JobQueueTest {

    private static final String TYPE = "TEST_JOB";

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void claimedJobIsNotGivenTwice() throws Exception {
        UUID id = jobs.enqueue(TYPE, "{}", null, 3);

        // два исполнителя одновременно тянутся за одной задачей
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        Set<UUID> claimed = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    jobs.claim(List.of(TYPE)).ifPresent(job -> claimed.add(job.id()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(claimed).as("задачу забрали больше одного раза").containsExactly(id);
        cleanup(id);
    }

    @Test
    void failureSchedulesRetryThenGivesUp() {
        UUID id = jobs.enqueue(TYPE, "{}", null, 2);

        jobs.claim(List.of(TYPE));
        jobs.fail(id, "первая неудача");
        JobRow afterFirst = jobs.findById(id).orElseThrow();
        assertThat(afterFirst.state()).as("после первой неудачи задача должна ждать повтора")
                .isEqualTo(JobRepository.READY);
        assertThat(afterFirst.nextAttemptAt()).as("повтор назначен не сразу")
                .isAfter(Instant.now().plusSeconds(1));

        // пауза перед повтором прошла
        release(id);
        jobs.claim(List.of(TYPE));
        jobs.fail(id, "вторая неудача");
        JobRow afterSecond = jobs.findById(id).orElseThrow();
        assertThat(afterSecond.state()).as("попытки исчерпаны").isEqualTo(JobRepository.FAILED);
        assertThat(afterSecond.error()).contains("вторая неудача");
        // проваленная задача остаётся видимой: преподаватель должен узнать, что не вышло
        assertThat(afterSecond.finishedAt()).isNotNull();
        cleanup(id);
    }

    /** Упавший исполнитель не должен уносить задачу с собой. */
    @Test
    void expiredLeaseReturnsJobToQueue() {
        UUID id = jobs.enqueue(TYPE, "{}", null, 3);
        assertThat(jobs.claim(List.of(TYPE))).isPresent();
        assertThat(jobs.claim(List.of(TYPE))).as("занятая задача не выдаётся").isEmpty();

        // исполнитель «умер»: аренда истекла
        jdbc.update("update job set lease_until = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), id);

        Optional<JobRow> reclaimed = jobs.claim(List.of(TYPE));
        assertThat(reclaimed).as("задача за упавшим исполнителем не подобрана").isPresent();
        assertThat(reclaimed.get().attempts()).as("повторный заход считается попыткой").isEqualTo(2);
        cleanup(id);
    }

    @Test
    void completedJobLeavesQueue() {
        UUID id = jobs.enqueue(TYPE, "{}", null, 3);
        jobs.claim(List.of(TYPE));
        jobs.complete(id, "{\"ok\":true}");

        assertThat(jobs.claim(List.of(TYPE))).as("завершённая задача выдана снова").isEmpty();
        JobRow done = jobs.findById(id).orElseThrow();
        assertThat(done.state()).isEqualTo(JobRepository.DONE);
        assertThat(done.result()).contains("ok");
        cleanup(id);
    }

    @Test
    void unknownTypeIsNotClaimed() {
        UUID id = jobs.enqueue("SOME_OTHER_TYPE", "{}", null, 3);
        assertThat(jobs.claim(List.of(TYPE))).isEmpty();
        cleanup(id);
    }

    /** Пауза перед повтором прошла — иначе задача честно ждала бы минуту. */
    private void release(UUID id) {
        jdbc.update("update job set next_attempt_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), id);
    }

    private void cleanup(UUID id) {
        jdbc.update("delete from job where id = ?", id);
    }
}
