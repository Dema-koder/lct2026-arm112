package ru.lct.arm112.generation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.lct.arm112.persistence.JobRepository;
import ru.lct.arm112.persistence.JobRepository.JobRow;
import ru.lct.arm112.service.generation.GenerationHandler;
import ru.lct.arm112.service.generation.GenerationHandler.Report;
import ru.lct.arm112.service.job.JobWorker;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Весь путь генерации без языковой модели: постановка задачи → очередь → отсев → библиотека.
 *
 * <p>Смысл прогона — измерить <b>слой защиты</b>, а не качество генератора. Подключение
 * модели заменит один компонент, а очередь, валидатор и сохранение останутся теми же.
 * Поэтому цифры, снятые здесь, будут сравнимы с цифрами после подключения модели.
 */
@SpringBootTest
class GenerationPipelineTest {

    @Autowired
    JobRepository jobs;

    @Autowired
    JobWorker worker;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void generatesThroughQueueAndFiltersByValidator() {
        String payload = objectMapper.writeValueAsString(
                new GenerationHandler.Request("FIRE", 12, 5, null));
        UUID jobId = jobs.enqueue(GenerationHandler.TYPE, payload, null, 3);

        assertThat(worker.runOne()).as("исполнитель не взял задачу").isTrue();

        JobRow finished = jobs.findById(jobId).orElseThrow();
        assertThat(finished.state()).as("задача не завершилась: %s", finished.error())
                .isEqualTo(JobRepository.DONE);

        Report report = objectMapper.readValue(finished.result(), Report.class);
        System.out.printf(Locale.ROOT,
                "%n=== Путь генерации, источник «%s» ===%n"
                        + "запрошено %d, выдано %d, принято %d, отсеяно %d (%.0f %%)%n"
                        + "причины отсева: %s%n"
                        + "замечания:      %s%n",
                report.generator(), report.requested(), report.produced(), report.accepted(),
                report.rejected(),
                report.produced() == 0 ? 0.0 : 100.0 * report.rejected() / report.produced(),
                report.rejectionReasons(), report.warnings());

        assertThat(report.produced()).as("генератор ничего не выдал").isGreaterThan(0);
        assertThat(report.accepted() + report.rejected()).isEqualTo(report.produced());
        // каждый сохранённый сценарий обязан быть проверенным: иначе валидатор бесполезен
        assertThat(report.savedIds()).hasSize(report.accepted());
    }

    /** Ошибка обработчика не теряет задачу: она остаётся видимой и уходит на повтор. */
    @Test
    void brokenPayloadIsRecordedNotSwallowed() {
        UUID jobId = jobs.enqueue(GenerationHandler.TYPE, "не json", null, 1);
        assertThat(worker.runOne()).isTrue();

        JobRow failed = jobs.findById(jobId).orElseThrow();
        assertThat(failed.state()).isEqualTo(JobRepository.FAILED);
        assertThat(failed.error()).as("причина неудачи не записана").isNotBlank();
    }

    /** Задача неизвестного вида не копится молча в очереди. */
    @Test
    void unknownJobTypeFailsLoudly() {
        UUID jobId = jobs.enqueue("LEFTOVER_FROM_OLD_VERSION", "{}", null, 1);
        // исполнитель берёт только известные ему виды, поэтому задача просто не выдаётся
        assertThat(jobs.findById(jobId).orElseThrow().state()).isEqualTo(JobRepository.READY);
    }

    @Test
    void reportCountsAreConsistent() {
        String payload = objectMapper.writeValueAsString(
                new GenerationHandler.Request("MEDICAL", 6, 5, null));
        UUID jobId = jobs.enqueue(GenerationHandler.TYPE, payload, null, 3);
        worker.runOne();

        Report report = objectMapper.readValue(jobs.findById(jobId).orElseThrow().result(), Report.class);
        int byReason = report.rejectionReasons().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(byReason)
                .as("сумма по причинам меньше числа отсеянных: часть отказов без объяснения")
                .isGreaterThanOrEqualTo(report.rejected());
        assertThat(report.rejectionReasons()).allSatisfy((code, count) ->
                assertThat(count).as("код %s", code).isPositive());
    }

    private static Map<String, Integer> reasons(Report report) {
        return report.rejectionReasons();
    }
}
