package ru.lct.arm112.service.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.lct.arm112.persistence.JobRepository;
import ru.lct.arm112.persistence.JobRepository.JobRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Разбирает очередь фоновых задач.
 *
 * <p>Тяжёлое — генерация сценариев и персональный разбор — считается здесь, а не на пути
 * запроса. Заказчик прямо просил не звать модель в момент, когда обучающийся работает:
 * норматив в 30 секунд не должен тратиться на ожидание
 * ([q-and-a.md](../../../../../../docs/materials/q-and-a.md), «Время отклика без GPU»).
 *
 * <p>За один тик берётся ограниченное число задач: пик приходится на конец занятия,
 * когда группа завершает сессии разом, и разбирать его надо ровно, не забивая процессор
 * в ущерб отклику интерфейса.
 */
@Service
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository jobs;
    private final Map<String, JobHandler> handlers;
    private final int batchSize;

    public JobWorker(JobRepository jobs, List<JobHandler> handlers,
                     @Value("${arm112.jobs.batch-size:2}") int batchSize) {
        this.jobs = jobs;
        this.batchSize = Math.max(1, batchSize);
        this.handlers = handlers.stream().collect(Collectors.toMap(
                JobHandler::type, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        if (!this.handlers.isEmpty()) {
            log.info("Очередь задач: обработчиков {}, за тик берём до {}", this.handlers.size(), this.batchSize);
        }
    }

    @Scheduled(fixedDelayString = "${arm112.jobs.poll-ms:1000}")
    public void poll() {
        if (handlers.isEmpty()) return;
        for (int i = 0; i < batchSize; i++) {
            if (!runOne()) return;
        }
    }

    /** @return true, если задача нашлась и была обработана; false — очередь пуста */
    public boolean runOne() {
        Optional<JobRow> claimed = jobs.claim(List.copyOf(handlers.keySet()));
        if (claimed.isEmpty()) return false;

        JobRow job = claimed.get();
        JobHandler handler = handlers.get(job.type());
        if (handler == null) {
            // вид задачи остался от прежней версии приложения: помечаем неудачей,
            // а не молча держим в очереди — иначе она копилась бы незаметно
            jobs.fail(job.id(), "Нет обработчика для вида задачи " + job.type());
            return true;
        }
        long startedAt = System.nanoTime();
        try {
            String result = handler.handle(job.payload());
            jobs.complete(job.id(), result);
            log.info("Задача выполнена: id={} вид={} попытка={} за {} мс",
                    job.id(), job.type(), job.attempts(), (System.nanoTime() - startedAt) / 1_000_000);
        } catch (Exception exception) {
            jobs.fail(job.id(), exception.getClass().getSimpleName() + ": " + exception.getMessage());
            log.warn("Задача не выполнена: id={} вид={} попытка={} из {} — {}",
                    job.id(), job.type(), job.attempts(), job.maxAttempts(), exception.getMessage());
        }
        return true;
    }

    /** Для тестов и ручного запуска: разобрать очередь до конца. */
    public int drain(int limit) {
        int done = 0;
        while (done < limit && runOne()) done++;
        return done;
    }

    public Optional<JobRow> status(UUID id) {
        return jobs.findById(id);
    }
}
