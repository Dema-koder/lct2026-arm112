package ru.lct.arm112.service.generation;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.GenerateRequest;
import ru.lct.arm112.api.ApiModels.GenerationJob;
import ru.lct.arm112.api.ApiModels.GenerationReport;
import ru.lct.arm112.api.ApiModels.GenerationStatus;
import ru.lct.arm112.api.ApiModels.Scenario;
import ru.lct.arm112.persistence.JobRepository;
import ru.lct.arm112.persistence.JobRepository.JobRow;
import ru.lct.arm112.service.ScenarioService;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * Генерация сценариев для преподавателя: постановка задачи в очередь и её статус.
 *
 * <p>Генерация идёт только через {@link GenerationHandler}: кандидаты проходят валидатор,
 * в библиотеку попадает сошедшееся со справочниками. На модели пачка занимает минуту-полторы
 * (LLM_EXPERIMENT.md §4д), поэтому запрос преподавателя ставит задачу и сразу возвращается,
 * а итог — число принятых и причины отсева — читается по идентификатору задачи.
 */
@Service
public class ScenarioGenerationService {

    private static final int ATTEMPTS = 2;

    private final JobRepository jobs;
    private final GenerationHandler handler;
    private final ScenarioService scenarios;
    private final ObjectMapper objectMapper;
    private final ScenarioGenerator generator;
    private final ru.lct.arm112.service.ReferenceDataService references;

    public ScenarioGenerationService(JobRepository jobs, GenerationHandler handler, ScenarioService scenarios,
                                     ObjectMapper objectMapper, ScenarioGenerator generator,
                                     ru.lct.arm112.service.ReferenceDataService references) {
        this.generator = generator;
        this.references = references;
        this.jobs = jobs;
        this.handler = handler;
        this.scenarios = scenarios;
        this.objectMapper = objectMapper;
    }

    /** Источник до запуска: преподаватель видит, подключена ли модель, ещё не нажав кнопку. */
    public GenerationStatus status() {
        return new GenerationStatus(generator.name());
    }

    public GenerationJob enqueue(GenerateRequest request, UUID actor) {
        requireKnownCategory(request.category());
        GenerationHandler.Request payload = new GenerationHandler.Request(
                request.category(), request.count(), request.difficulty(), actor);
        UUID id = jobs.enqueue(GenerationHandler.TYPE, objectMapper.writeValueAsString(payload), actor, ATTEMPTS);
        return job(id, actor);
    }

    /** Статус задачи; чужая задача не видна — как и чужие занятия. */
    public GenerationJob job(UUID id, UUID actor) {
        JobRow row = jobs.findById(id)
                .filter(r -> GenerationHandler.TYPE.equals(r.type()) && actor.equals(r.createdBy()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Задача генерации не найдена"));
        GenerationHandler.Request request = objectMapper.readValue(row.payload(), GenerationHandler.Request.class);
        GenerationReport report = row.result() == null ? null
                : objectMapper.readValue(row.result(), GenerationReport.class);
        return new GenerationJob(row.id(), row.state(), request.category(), request.count(), row.error(),
                row.createdAt(), row.finishedAt(), report);
    }

    /**
     * Синхронная генерация для старого вызова {@code POST /teacher/scenarios/generate}.
     * Тот же путь с валидатором, что и у очереди: несуразная перестановка билетов, которую
     * этот вызов раньше записывал в библиотеку без проверки, сюда больше не попадает.
     */
    public List<Scenario> generateNow(GenerateRequest request, UUID actor) {
        requireKnownCategory(request.category());
        GenerationHandler.Report report = handler.run(new GenerationHandler.Request(
                request.category(), request.count(), request.difficulty(), actor));
        return report.savedIds().stream().map(scenarios::require).toList();
    }

    /**
     * Категория — позиция «Что случилось?», по которой есть типы в классификаторе.
     * Вкладка, открытая до перехода на разделы ЕКП, присылала FIRE: задача молча
     * завершалась с нулём сценариев, и было непонятно почему.
     */
    private void requireKnownCategory(String category) {
        boolean known = references.incidentTypes().stream().anyMatch(t -> t.category().equals(category));
        if (!known) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Неизвестная категория «" + category + "»: обновите страницу");
        }
    }
}
