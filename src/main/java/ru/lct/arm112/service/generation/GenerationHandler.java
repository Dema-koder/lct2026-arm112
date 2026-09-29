package ru.lct.arm112.service.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.Scenario;
import ru.lct.arm112.api.ApiModels.ScenarioUpsert;
import ru.lct.arm112.service.ScenarioService;
import ru.lct.arm112.service.generation.ScenarioValidator.Verdict;
import ru.lct.arm112.service.generation.ScenarioValidator.Violation;
import ru.lct.arm112.service.job.JobHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Фоновая генерация сценариев: кандидаты → проверка → библиотека.
 *
 * <p>Порядок важен: генератор ничего не сохраняет сам, всё проходит через
 * {@link ScenarioValidator}. Модель может выдумать что угодно — в библиотеку попадает
 * только сошедшееся со справочниками, и всё равно неподтверждённым:
 * решение за преподавателем (q-and-a.md §3).
 *
 * <p>Отчёт о задаче содержит долю отсева и причины по кодам. Это и есть метрика
 * качества генератора (METRICS.md §5.8): по ней видно, стоит ли менять источник,
 * и что именно он делает не так.
 */
@Component
public class GenerationHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(GenerationHandler.class);

    public static final String TYPE = "SCENARIO_GENERATION";

    private final ScenarioGenerator generator;
    private final ScenarioValidator validator;
    private final ScenarioService scenarios;
    private final ObjectMapper objectMapper;

    public GenerationHandler(ScenarioGenerator generator, ScenarioValidator validator,
                             ScenarioService scenarios, ObjectMapper objectMapper) {
        this.generator = generator;
        this.validator = validator;
        this.scenarios = scenarios;
        this.objectMapper = objectMapper;
    }

    /** Что положили в задачу при постановке. */
    public record Request(String category, int count, int difficulty, UUID actor) {}

    /** Что получилось; ложится в колонку результата и показывается преподавателю. */
    public record Report(String generator, int requested, int produced, int accepted, int rejected,
                         Map<String, Integer> rejectionReasons, Map<String, Integer> warnings,
                         List<String> savedIds) {}

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String handle(String payload) {
        return objectMapper.writeValueAsString(run(objectMapper.readValue(payload, Request.class)));
    }

    /** Генерация с проверкой; из очереди и из синхронного вызова идёт одна и та же. */
    public Report run(Request request) {
        Map<String, Integer> rejections = new LinkedHashMap<>();
        Map<String, Integer> warnings = new LinkedHashMap<>();
        List<String> saved = new ArrayList<>();
        int produced = 0;
        // Добор до заказанного: валидатор отсеивает часть кандидатов, и без добора
        // «заказал 3 — получил 2». Попыток не больше двух на заказанный сценарий,
        // чтобы при системно негодном выводе модели задача не крутилась бесконечно.
        int budget = request.count() * 2;
        while (saved.size() < request.count() && produced < budget) {
            int need = Math.min(request.count() - saved.size(), budget - produced);
            List<ScenarioUpsert> candidates = generator.generate(request.category(), need, request.difficulty());
            if (candidates.isEmpty()) break;
            produced += candidates.size();
            for (ScenarioUpsert candidate : candidates) {
                Verdict verdict = validator.validate(candidate);
                verdict.warnings().forEach(v -> warnings.merge(v.code(), 1, Integer::sum));
                if (!verdict.valid()) {
                    for (Violation violation : verdict.fatal()) {
                        rejections.merge(violation.code(), 1, Integer::sum);
                    }
                    continue;
                }
                Scenario created = scenarios.create(candidate, request.actor());
                saved.add(created.id());
                if (saved.size() >= request.count()) break;
            }
        }

        Report report = new Report(generator.name(), request.count(), produced,
                saved.size(), produced - saved.size(), rejections, warnings, saved);
        log.info("Генерация «{}»: запрошено {}, выдано {}, принято {}, отсеяно {} — причины {}",
                request.category(), request.count(), produced, saved.size(),
                produced - saved.size(), rejections);
        return report;
    }
}
