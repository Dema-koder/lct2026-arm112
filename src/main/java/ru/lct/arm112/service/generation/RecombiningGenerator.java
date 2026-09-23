package ru.lct.arm112.service.generation;

import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.Scenario;
import ru.lct.arm112.api.ApiModels.ScenarioUpsert;
import ru.lct.arm112.persistence.ScenarioRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Генератор без модели: ситуация одного билета плюс адрес другого из той же категории.
 *
 * <p>Это нынешнее поведение {@code ScenarioService.generate}, вынесенное за интерфейс.
 * Оно остаётся запасным вариантом на случай, когда языковая модель недоступна:
 * приложение обязано работать и без сайдкара.
 *
 * <p><b>Честная оценка качества.</b> Перестановка даёт несуразности — текст про подъезд
 * с адресом железнодорожного переезда, — и именно поэтому она ценна как проверка
 * валидатора: часть кандидатов заведомо негодна, и видно, сколько отсеивается.
 * Как источник учебных сценариев это временная мера.
 */
@Component
public class RecombiningGenerator implements ScenarioGenerator {

    private final ScenarioRepository repository;
    private final Random random = new Random();

    public RecombiningGenerator(ScenarioRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "recombination";
    }

    @Override
    public List<ScenarioUpsert> generate(String category, int count, int difficulty) {
        List<Scenario> pool = repository.find(category, "TICKET", null, 500);
        if (pool.size() < 2) return List.of();

        List<ScenarioUpsert> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Scenario situation = pool.get(random.nextInt(pool.size()));
            Scenario address = pool.get(random.nextInt(pool.size()));
            result.add(new ScenarioUpsert(null,
                    "Сгенерировано: " + situation.title(), category, difficulty,
                    situation.callerText(), situation.caller(), address.rawAddress(), address.expectedAddress(),
                    situation.expectedIncidentTypes(), situation.expectedServices(),
                    "ACCEPT", null, true));
        }
        return result;
    }
}
