package ru.lct.arm112.service.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.ScenarioUpsert;

import java.util.List;

/**
 * Выбор источника сценариев: языковая модель, если она отвечает.
 *
 * <p>Без модели новых сценариев нет, и это сознательно. Раньше здесь был откат на перестановку
 * билетов — текст одного билета с адресом другого. Она давала несуразности, которые видел
 * преподаватель: «рабочий упал в котлован» с адресом квартиры, «драка» в категории
 * «медицина». Источник сценариев без модели — банк, написанный заранее вне контура
 * (seed/scenarios-generated.json): он уже в библиотеке и проверен валидатором.
 *
 * <p>Перестановка оставлена за флагом {@code arm112.generation.recombination-fallback}:
 * она нужна замерам и тестам конвейера, где важно, что валидатор отсеивает негодное.
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
    private final boolean recombination;

    public GeneratorRouter(LlmGenerator llm, RecombiningGenerator fallback,
                           @Value("${arm112.generation.recombination-fallback:false}") boolean recombination) {
        this.llm = llm;
        this.fallback = fallback;
        this.recombination = recombination;
    }

    /** Имя источника для отчёта; {@code none} — модели нет, откат выключен. */
    @Override
    public String name() {
        if (llm.available()) return llm.name();
        return recombination ? fallback.name() : "none";
    }

    @Override
    public List<ScenarioUpsert> generate(String category, int count, int difficulty) {
        if (llm.available()) {
            List<ScenarioUpsert> candidates = llm.generate(category, count, difficulty);
            if (!candidates.isEmpty()) return candidates;
            log.warn("Модель доступна, но кандидатов не выдала");
        }
        return recombination ? fallback.generate(category, count, difficulty) : List.of();
    }
}
