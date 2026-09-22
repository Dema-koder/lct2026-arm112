package ru.lct.arm112.service.assessment;

import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.Assessment;
import ru.lct.arm112.api.ApiModels.AssessmentIssue;
import ru.lct.arm112.api.ApiModels.CardDraft;
import ru.lct.arm112.api.ApiModels.DraftService;
import ru.lct.arm112.api.ApiModels.Hint;
import ru.lct.arm112.api.ApiModels.Scenario;
import ru.lct.arm112.service.ReferenceDataService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ru.lct.arm112.service.assessment.AssessmentResult.CardBreakdown;

/**
 * Оценка режима заполнения карточки: адрес 40, тип 20, службы 20, время 15, грамотность 5.
 * Эталон — билет (уточнённый адрес) и ЕКП (тип, службы).
 */
@Component
public class CardFillAssessor {
    private static final double W_ADDRESS = 40, W_TYPE = 20, W_SERVICES = 20, W_TIME = 15, W_LANGUAGE = 5;
    private static final Duration NORM = Duration.ofMinutes(3);

    private final LanguageChecker language;
    private final ReferenceDataService references;

    public CardFillAssessor(LanguageChecker language, ReferenceDataService references) {
        this.language = language;
        this.references = references;
    }

    public AssessmentResult assess(UUID sessionId, List<CardDraft> drafts, List<Scenario> scenarios) {
        return assess(sessionId, drafts, scenarios, 0, 0);
    }

    /**
     * @param missedCalls сколько раз входящий вызов не был принят за время звонка (заявитель перезвонил)
     * @param lostCalls   сколько вызовов потеряно окончательно (заявитель не дозвонился)
     */
    public AssessmentResult assess(UUID sessionId, List<CardDraft> drafts, List<Scenario> scenarios,
                                   int missedCalls, int lostCalls) {
        List<AssessmentIssue> issues = new ArrayList<>();
        List<CardBreakdown> breakdown = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        double address = 0, type = 0, services = 0, timing = 0, lang = 0;
        int syntaxErrors = 0;
        int n = Math.max(1, drafts.size());

        for (CardDraft draft : drafts) {
            Scenario scenario = scenarios.stream().filter(s -> s.id().equals(draft.scenarioId())).findFirst().orElse(null);
            if (scenario == null) {
                address += 100; type += 100; services += 100; timing += 100; lang += 100;
                breakdown.add(new CardBreakdown(draft.id(), draft.scenarioId(), 100.0, 100.0, 100.0, 100.0, 100.0,
                        null, null, null, 0, 0));
                continue;
            }

            AddressMatcher.Result addr = AddressMatcher.score(draft.address(), scenario.expectedAddress(), draft.id());
            address += addr.score();
            issues.addAll(addr.issues());

            double typeScore = jaccard(draft.incidentTypeIds(), scenario.expectedIncidentTypes());
            if (scenario.expectedIncidentTypes().isEmpty()) typeScore = draft.incidentTypeIds().isEmpty() ? 0 : 100;
            if (typeScore < 100 && !scenario.expectedIncidentTypes().isEmpty()) {
                issues.add(new AssessmentIssue("INCIDENT_TYPE_MISMATCH", typeScore == 0 ? "CRITICAL" : "WARNING",
                        typeScore == 0 ? "Тип происшествия определён неверно" : "Тип происшествия определён не полностью",
                        draft.id(), labels(scenario.expectedIncidentTypes()), labels(draft.incidentTypeIds())));
            }
            type += typeScore;

            // территориальные ДДС сравниваются с колонкой «Терр. ОИВ» эталона, а не буквально по коду
            List<String> actualServices = draft.services().stream().map(DraftService::code)
                    .map(references::matrixGroupOf).distinct().toList();
            List<String> expectedServices = scenario.expectedServices().stream()
                    .map(references::matrixGroupOf).distinct().toList();
            double serviceScore = expectedServices.isEmpty() ? 100 : jaccard(actualServices, expectedServices);
            for (String code : expectedServices) {
                if (!actualServices.contains(code)) {
                    issues.add(new AssessmentIssue("SERVICE_MISSING", isEmergency(code) ? "CRITICAL" : "WARNING",
                            "Не оповещена служба " + references.service(code).label(), draft.id(), code, null));
                }
            }
            services += serviceScore;

            double cardTiming;
            Long spentSeconds = null;
            if (draft.savedAt() != null && draft.startedAt() != null) {
                Duration spent = Duration.between(draft.startedAt(), draft.savedAt());
                spentSeconds = spent.toSeconds();
                double t = spent.compareTo(NORM) <= 0 ? 100
                        : TextUtil.clamp(100 - 100.0 * (spent.toSeconds() - NORM.toSeconds()) / NORM.toSeconds());
                if (t < 100) {
                    issues.add(new AssessmentIssue("PROCESSING_OVERDUE", "WARNING",
                            "Карточка заполнялась дольше норматива 3 минуты", draft.id(), 180, spent.toSeconds()));
                }
                timing += t;
                cardTiming = t;
            } else {
                timing += 0;
                cardTiming = 0;
                issues.add(new AssessmentIssue("CARD_NOT_SAVED", "CRITICAL", "Карточка не сохранена", draft.id(), null, null));
            }

            LanguageChecker.Result lr = language.check(draft.description());
            syntaxErrors += lr.errors();
            lang += lr.score();
            lr.findings().forEach(f -> issues.add(new AssessmentIssue("LANGUAGE", "INFO", f, draft.id(), null, null)));

            List<AssessmentIssue> cardIssues = issues.stream()
                    .filter(i -> draft.id().equals(i.cardId())).toList();
            breakdown.add(new CardBreakdown(draft.id(), draft.scenarioId(), addr.score(), typeScore, serviceScore,
                    cardTiming, lr.score(), null, null, spentSeconds, cardIssues.size(),
                    (int) cardIssues.stream().filter(i -> "CRITICAL".equals(i.severity())).count()));
        }

        address /= n; type /= n; services /= n; timing /= n; lang /= n;
        // Пропущенные и потерянные вызовы бьют по времени реакции: оператор 112 обязан ответить сразу.
        for (int i = 0; i < missedCalls; i++) {
            issues.add(new AssessmentIssue("CALL_MISSED", "WARNING",
                    "Входящий вызов не принят вовремя — заявитель перезванивал", null, null, null));
        }
        for (int i = 0; i < lostCalls; i++) {
            issues.add(new AssessmentIssue("CALL_LOST", "CRITICAL",
                    "Вызов потерян: заявитель не дозвонился", null, null, null));
        }
        timing = TextUtil.clamp(timing - 10 * missedCalls - 30 * lostCalls);
        if (missedCalls + lostCalls > 0) recommendations.add("Отвечайте на вызов сразу — заявитель ждёт не дольше 30 секунд.");
        double total = (address * W_ADDRESS + type * W_TYPE + services * W_SERVICES + timing * W_TIME + lang * W_LANGUAGE) / 100.0;

        if (address < 100) recommendations.add("Сверяйте улицу и дом с уточнённым адресом: ориентир заявителя нужно привести к формализованному адресу.");
        if (type < 100) recommendations.add("Используйте поиск по типу происшествия и синонимы — тип определяет состав служб.");
        if (services < 100) recommendations.add("Не удаляйте автоматически подобранные службы: список формируется по классификатору.");
        if (timing < 100) recommendations.add("Укладывайтесь в 3 минуты на карточку: сначала адрес и тип, подробности — в описание.");
        if (syntaxErrors > 0) recommendations.add("Проверяйте набор: опечатка в названии улицы отправит службы не по тому адресу.");

        Assessment assessment = new Assessment(UUID.randomUUID(), sessionId, "COMPLETED", "CARD_FILL",
                TextUtil.round(total), TextUtil.round(timing), TextUtil.round((type + services) / 2), null,
                TextUtil.round(lang), TextUtil.round(address), TextUtil.round(type), TextUtil.round(services),
                syntaxErrors, issues, recommendations, "AI", TextUtil.round(total), null, null, null, List.of(), List.of());
        return new AssessmentResult(assessment, List.copyOf(breakdown));
    }

    /** Подсказки по ходу заполнения — только для занятий вида «тренировка». */
    public List<Hint> hints(CardDraft draft, Scenario scenario) {
        List<Hint> hints = new ArrayList<>();
        if (scenario == null) return hints;
        if (scenario.expectedAddress() != null && draft.address() != null) {
            String exp = TextUtil.normalize(scenario.expectedAddress().street());
            String act = TextUtil.normalize(draft.address().street());
            if (!exp.isEmpty() && !act.isEmpty() && !act.equals(exp) && !exp.contains(act) && !act.contains(exp)) {
                hints.add(new Hint("address.street", "Улица не совпадает с уточнённым адресом заявителя"));
            }
            String expHouse = TextUtil.normalize(scenario.expectedAddress().house());
            String actHouse = TextUtil.normalize(draft.address().house());
            if (!expHouse.isEmpty() && !actHouse.isEmpty() && !actHouse.equals(expHouse)) {
                hints.add(new Hint("address.house", "Проверьте номер дома"));
            }
        }
        if (!scenario.expectedIncidentTypes().isEmpty() && !draft.incidentTypeIds().isEmpty()
                && draft.incidentTypeIds().stream().noneMatch(scenario.expectedIncidentTypes()::contains)) {
            hints.add(new Hint("incidentTypeIds", "Тип происшествия не соответствует описанию заявителя"));
        }
        if (draft.incidentTypeIds().isEmpty() && draft.address() != null && draft.address().street() != null) {
            hints.add(new Hint("incidentTypeIds", "Выберите тип происшествия — без него не подберутся службы"));
        }
        return hints;
    }

    private static double jaccard(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 100;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        return union.isEmpty() ? 100 : 100.0 * inter.size() / union.size();
    }

    private List<String> labels(List<String> ids) {
        return ids.stream().map(id -> {
            ReferenceDataService.IncidentType t = references.incidentType(id);
            return t == null ? id : t.label();
        }).toList();
    }

    private static boolean isEmergency(String code) {
        return code.equals("101") || code.equals("102") || code.equals("103") || code.equals("104");
    }
}
