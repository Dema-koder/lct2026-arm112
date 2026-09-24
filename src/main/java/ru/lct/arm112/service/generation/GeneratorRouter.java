package ru.lct.arm112.service.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.ScenarioUpsert;

import java.util.List;

/**
 * Выбор источника сценариев: языковая модель, если она отвечает, иначе перестановка билетов.
 *
 * <p>Откат обязателен, а не желателен. Заказчик разворачивает систему в локальном контуре,
 * где сайдкар с моделью может быть не поставлен вовсе, не влезть в память или просто
 * не подняться. Генерация сценариев не должна в этом случае отказывать преподавателю —
 * она должна работать хуже.
 *
 * <p>Имя источника попадает в отчёт о задаче, поэтому по выгрузке всегда видно,
 * кто именно сгенерировал набор, и цифры отсева не смешиваются между источниками.
 */
@Component
@Primary
public class GeneratorRouter implements ScenarioGenerator {
    private static final Logger log = LoggerFactory.getLogger(GeneratorRouter.class);

    private final LlmGenerator llm;
    private final RecombiningGenerator fallback;

    public GeneratorRouter(LlmGenerator llm, RecombiningGenerator fallback) {
        this.llm = llm;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return llm.available() ? llm.name() : fallback.name();
    }

    @Override
    public List<ScenarioUpsert> generate(String category, int count, int difficulty) {
        if (llm.available()) {
            List<ScenarioUpsert> candidates = llm.generate(category, count, difficulty);
            if (!candidates.isEmpty()) return candidates;
            // модель отозвалась на проверку здоровья, но ничего не выдала: не оставляем
            // преподавателя без результата, дописываем перестановкой
            log.warn("Модель доступна, но кандидатов не выдала — откат на перестановку билетов");
        }
        return fallback.generate(category, count, difficulty);
    }
}
