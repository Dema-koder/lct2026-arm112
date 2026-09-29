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
 *   <li>Если модель недоступна или её текст не прошёл проверку, разбора от модели
 *       нет — так и записывается ({@code UNAVAILABLE}, {@code REJECTED}). Подменять его
 *       текстом по правилам не нужно: рекомендации по правилам обучающийся видит
 *       сразу в оценке (RecommendationRules), а разбор модели — отдельный блок.</li>
 * </ol>
 */
@Component
public class DebriefHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(DebriefHandler.class);

    public static final String TYPE = "SESSION_DEBRIEF";
    static final String SOURCE_LLM = "LLM";
    static final String SOURCE_UNAVAILABLE = "UNAVAILABLE";
    static final String SOURCE_REJECTED = "REJECTED";

    private final AssessmentRepository assessments;
    private final DebriefRepository debriefs;
    private final DebriefWriter writer;
    private final RussianTextGuard guard;
    private final ObjectMapper objectMapper;
    private final ru.lct.arm112.service.ReferenceDataService references;

    public DebriefHandler(AssessmentRepository assessments, DebriefRepository debriefs,
                          DebriefWriter writer, RussianTextGuard guard, ObjectMapper objectMapper,
                          ru.lct.arm112.service.ReferenceDataService references) {
        this.references = references;
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
        String text = writer.write(facts(ai), structured);
        String source = SOURCE_LLM;
        if (text == null) {
            text = "Языковая модель не подключена — разбор не составлен.";
            source = SOURCE_UNAVAILABLE;
        } else {
            RussianTextGuard.Check check = guard.check(text);
            if (!check.usable()) {
                // Показывать обучающемуся такое нельзя ни при каких условиях
                log.warn("Разбор от модели отклонён ({}): оценка {}", check.reason(), ai.id());
                text = "Текст модели не прошёл проверку (" + check.reason() + ") и не показывается.";
                source = SOURCE_REJECTED;
            }
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

        // Одно замечание на код: двадцать строк «не оповещена служба» по разным карточкам
        // модель путала и писала, что оповещена «только» пропущенная служба. Неоповещённые
        // службы подаются одним списком, остальное — первым замечанием каждого кода.
        List<Map<String, Object>> issues = new ArrayList<>();
        java.util.Set<String> seenCodes = new java.util.LinkedHashSet<>();
        java.util.Set<Object> missingServices = new java.util.LinkedHashSet<>();
        // Замечания уровня INFO тоже подаются: о деталях адреса и находках проверки текста
        // обучающийся получает рекомендации, и без фактов модель их выдумывала
        // («корпус Б», «кватрира», «улица Ленина, 20» — ничего этого в карточке не было).
        List<String> textFindings = new ArrayList<>();
        for (AssessmentIssue issue : ai.issues() == null ? List.<AssessmentIssue>of() : ai.issues()) {
            if ("LANGUAGE".equals(issue.code())) {
                if (textFindings.size() < 3) textFindings.add(issue.message());
                continue;
            }
            if ("INFO".equals(issue.severity()) && !"ADDRESS_DETAIL_MISMATCH".equals(issue.code())) continue;
            if ("SERVICE_MISSING".equals(issue.code())) {
                // название без точек: «Мос.Без.» грамматика разбора обрывала на «Мос.»
                if (issue.expected() instanceof String code) {
                    missingServices.add(references.service(code).label().replace(".", " ").replaceAll("\\s+", " ").trim());
                }
                continue;
            }
            if (!seenCodes.add(issue.code())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("что", issue.message());
            if (issue.expected() != null) item.put("как_надо", issue.expected());
            if (issue.actual() != null) item.put("как_сделано", issue.actual());
            issues.add(item);
            if (issues.size() >= 5) break;
        }
        summary.put("замечания", issues);
        if (!textFindings.isEmpty()) summary.put("находки_проверки_текста", textFindings);
        if (!missingServices.isEmpty()) summary.put("не_оповещены_службы", List.copyOf(missingServices));
        // рекомендации по правилам обучающийся уже видит: модель их объясняет, а не пересказывает
        // без хвоста «(карточек: N)» — иначе модель повторяет его отдельным предложением
        // и без рекомендации про службы: их список уже есть выше, без сокращений с точками
        summary.put("рекомендации_по_правилам", ai.recommendations() == null ? List.of()
                : ai.recommendations().stream()
                        .filter(r -> !r.startsWith("Не оповещены службы"))
                        .map(r -> r.replaceAll("\\s*\\(карточек: \\d+\\)$", "")).toList());
        return objectMapper.writeValueAsString(summary);
    }

    /**
     * Факты для пунктов разбора — по одному на вид ошибки, критичные первыми, не больше трёх.
     *
     * <p>Формулирует их код, а не модель: «вы записали «X», правильно — «Y»» нельзя
     * перепутать местами, если писала его не модель.
     */
    public List<String> facts(Assessment ai) {
        List<AssessmentIssue> all = ai.issues() == null ? List.of() : ai.issues();
        List<AssessmentIssue> ordered = new ArrayList<>(all);
        ordered.sort(java.util.Comparator.comparingInt(i -> switch (i.severity() == null ? "" : i.severity()) {
            case "CRITICAL" -> 0;
            case "WARNING" -> 1;
            default -> 2;
        }));
        List<String> facts = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (AssessmentIssue issue : ordered) {
            if (facts.size() >= 3) break;
            if (!seen.add(issue.code())) continue;
            if ("SERVICE_MISSING".equals(issue.code())) {
                List<String> names = all.stream().filter(i -> "SERVICE_MISSING".equals(i.code()))
                        .map(i -> i.expected() instanceof String c ? references.service(c).label() : null)
                        .filter(java.util.Objects::nonNull).distinct().toList();
                String listed = String.join(", ", names.subList(0, Math.min(5, names.size())))
                        + (names.size() > 5 ? " и другие" : "");
                facts.add("Не оповещены службы: " + listed + ".");
            } else if ("LANGUAGE".equals(issue.code())) {
                facts.add("Проверка текста: " + trimDot(issue.message()) + ".");
            } else {
                StringBuilder fact = new StringBuilder(trimDot(issue.message()));
                String actual = value(issue.actual());
                String expected = value(issue.expected());
                if (actual != null) fact.append(": вы записали «").append(actual).append("»");
                if (expected != null) fact.append(actual != null ? ", правильно — «" : ": правильно — «").append(expected).append("»");
                facts.add(fact.append('.').toString());
            }
        }
        return facts;
    }

    private static String value(Object value) {
        if (value == null) return null;
        String text = value instanceof java.util.Collection<?> c
                ? String.join(", ", c.stream().map(String::valueOf).toList()) : String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String trimDot(String text) {
        String t = text == null ? "" : text.trim();
        return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }
}
