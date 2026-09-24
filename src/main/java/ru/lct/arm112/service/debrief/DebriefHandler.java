package ru.lct.arm112.service.debrief;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.Assessment;
import ru.lct.arm112.api.ApiModels.AssessmentIssue;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.persistence.AssessmentRepository.AssessmentRow;
import ru.lct.arm112.service.assessment.AssessmentWeights;
import ru.lct.arm112.service.assessment.AssessmentWeights.Criterion;
import ru.lct.arm112.service.assessment.RussianTextGuard;
import ru.lct.arm112.service.job.JobHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Персональный разбор занятия для обучающегося (задача D5 плана).
 *
 * <h2>Почему в фоновой задаче</h2>
 * Замер на целевом железе: 88 секунд на разбор, 44 минуты на класс из тридцати.
 * На пути завершения сессии этого быть не может — обучающийся увидит результат
 * сразу, а разбор придёт, когда будет готов.
 *
 * <h2>Что защищает обучающегося от выдумки модели</h2>
 * <ol>
 *   <li>Модели подаётся <b>структура замечаний</b>, а не сырые данные: ей нужно
 *       изложить известное, а не выяснить что-то новое.</li>
 *   <li>Текст проходит {@link RussianTextGuard} перед сохранением. Замер показал,
 *       что модель срывается на другие языки и в этой задаче — 1 раз из 3
 *       без ограничения выборки.</li>
 *   <li>Если модель недоступна или её текст не прошёл проверку, разбор
 *       собирается из правиловых рекомендаций. Обучающийся получает разбор
 *       всегда — вопрос лишь в том, кто его написал.</li>
 * </ol>
 */
@Component
public class DebriefHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(DebriefHandler.class);

    public static final String TYPE = "SESSION_DEBRIEF";
    static final String SOURCE_LLM = "LLM";
    static final String SOURCE_RULES = "RULES";

    private final AssessmentRepository assessments;
    private final DebriefRepository debriefs;
    private final DebriefWriter writer;
    private final RussianTextGuard guard;
    private final ObjectMapper objectMapper;

    public DebriefHandler(AssessmentRepository assessments, DebriefRepository debriefs,
                          DebriefWriter writer, RussianTextGuard guard, ObjectMapper objectMapper) {
        this.assessments = assessments;
        this.debriefs = debriefs;
        this.writer = writer;
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    public record Request(UUID assessmentId) {}

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String handle(String payload) {
        Request request = objectMapper.readValue(payload, Request.class);
        AssessmentRow row = assessments.findById(request.assessmentId())
                .orElseThrow(() -> new IllegalStateException("Оценка не найдена: " + request.assessmentId()));
        Assessment ai = row.ai();

        String structured = describe(ai);
        String text = writer.write(structured);
        String source = SOURCE_LLM;

        if (text != null) {
            RussianTextGuard.Check check = guard.check(text);
            if (!check.usable()) {
                // Показывать обучающемуся такое нельзя ни при каких условиях
                log.warn("Разбор от модели отклонён ({}), собираю по правилам: оценка {}",
                        check.reason(), ai.id());
                text = null;
            }
        }
        if (text == null) {
            text = fromRules(ai);
            source = SOURCE_RULES;
        }

        debriefs.save(ai.id(), text, source);
        log.info("Разбор готов: оценка={} источник={} длина={}", ai.id(), source, text.length());
        return objectMapper.writeValueAsString(Map.of("source", source, "length", text.length()));
    }

    /**
     * Структура замечаний для модели: потери по критериям и что именно не так.
     *
     * <p>Подаётся именно структура, а не свободный текст: у модели нет повода
     * выдумывать подробности, которых ей не дали.
     */
    private String describe(Assessment ai) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("итог", ai.totalScore());

        Map<String, Object> losses = new LinkedHashMap<>();
        for (Criterion criterion : AssessmentWeights.forMode(ai.mode())) {
            Double score = AssessmentWeights.scoreOf(ai, criterion.code());
            if (score == null || score >= 100) continue;
            // потеря в баллах итога, а не проценты: «адрес стоил 11 баллов» понятнее, чем «72 %»
            losses.put(criterion.label(),
                    Math.round((100 - score) * criterion.weight() / 100.0 * 10) / 10.0);
        }
        summary.put("потери_в_баллах", losses);

        List<Map<String, Object>> issues = new ArrayList<>();
        for (AssessmentIssue issue : ai.issues() == null ? List.<AssessmentIssue>of() : ai.issues()) {
            if ("INFO".equals(issue.severity())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("что", issue.message());
            if (issue.expected() != null) item.put("как_надо", issue.expected());
            if (issue.actual() != null) item.put("как_сделано", issue.actual());
            issues.add(item);
            if (issues.size() >= 6) break;
        }
        summary.put("замечания", issues);
        return objectMapper.writeValueAsString(summary);
    }

    /** Запасной разбор из правиловых рекомендаций — без модели и без риска выдумки. */
    private String fromRules(Assessment ai) {
        List<String> parts = new ArrayList<>();
        parts.add("Итог занятия: " + ai.totalScore() + " из 100.");

        List<String> losses = new ArrayList<>();
        for (Criterion criterion : AssessmentWeights.forMode(ai.mode())) {
            Double score = AssessmentWeights.scoreOf(ai, criterion.code());
            if (score == null || score >= 100) continue;
            double lost = Math.round((100 - score) * criterion.weight() / 100.0 * 10) / 10.0;
            losses.add(criterion.label() + " — минус " + lost);
        }
        if (!losses.isEmpty()) {
            parts.add("Где потеряны баллы: " + String.join(", ", losses) + ".");
        }
        List<String> recommendations = ai.recommendations() == null ? List.of() : ai.recommendations();
        for (int i = 0; i < recommendations.size(); i++) {
            parts.add((i + 1) + ". " + recommendations.get(i));
        }
        if (recommendations.isEmpty()) {
            parts.add("Замечаний нет — занятие отработано без ошибок.");
        }
        return String.join(System.lineSeparator(), parts);
    }
}
