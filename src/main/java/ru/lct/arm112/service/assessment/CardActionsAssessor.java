package ru.lct.arm112.service.assessment;

import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.Assessment;
import ru.lct.arm112.api.ApiModels.AssessmentIssue;
import ru.lct.arm112.api.ApiModels.CardTimelineEntry;
import ru.lct.arm112.persistence.TrainingStateStore.CallState;
import ru.lct.arm112.persistence.TrainingStateStore.CardState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Оценка режима действий с карточкой: время 30, действия 40, коммуникация 15, грамотность 15.
 * Правила — из памятки ДДС (типовые нарушения): отказ от профильного происшествия,
 * неполный комментарий, просрочка нормативов.
 */
@Component
public class CardActionsAssessor {
    private static final double W_TIME = 30, W_ACTIONS = 40, W_COMM = 15, W_LANG = 15;
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "WORK_REFUSED", "NOT_ACCEPTED");

    private final LanguageChecker language;

    public CardActionsAssessor(LanguageChecker language) {
        this.language = language;
    }

    public Assessment assess(UUID sessionId, List<CardState> cards, List<CallState> calls) {
        List<AssessmentIssue> issues = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        double timing = 0, actions = 0, comm = 0, lang = 0;
        int syntaxErrors = 0;
        int n = Math.max(1, cards.size());

        for (CardState card : cards) {
            // --- время
            double t = 100;
            if (card.acceptanceOverdue()) {
                t -= 40;
                issues.add(new AssessmentIssue("ACCEPTANCE_OVERDUE", "WARNING",
                        "Превышен норматив 30 секунд на принятие карточки", card.id(), 30, null));
            }
            if (card.processingOverdue()) {
                t -= 30;
                issues.add(new AssessmentIssue("PROCESSING_OVERDUE", "WARNING",
                        "Превышен норматив 3 минуты на отработку карточки", card.id(), 180, null));
            }
            timing += TextUtil.clamp(t);

            // --- действия: корректность решения и полнота цепочки
            double a = 100;
            boolean traineeTouched = card.timeline().stream().anyMatch(e -> "TRAINEE".equals(e.actorRole()));
            if (!TERMINAL.contains(card.status())) {
                a = 0;
                issues.add(new AssessmentIssue("CARD_NOT_FINISHED", "CRITICAL",
                        "Работа с карточкой не завершена", card.id(), "COMPLETED", card.status()));
            } else {
                String expected = card.expectedDecision() == null ? "ACCEPT" : card.expectedDecision();
                if (expected.equals("ACCEPT") && card.status().equals("NOT_ACCEPTED")) {
                    a -= 70;
                    issues.add(new AssessmentIssue("REFUSED_PROFILE_INCIDENT", "CRITICAL",
                            "Отказ от реагирования на профильное происшествие", card.id(), "ACCEPT", "DECLINE"));
                } else if (expected.equals("DECLINE") && card.status().equals("COMPLETED")) {
                    a -= 40;
                    issues.add(new AssessmentIssue("ACCEPTED_FOREIGN_INCIDENT", "WARNING",
                            "Принята карточка вне компетенции службы", card.id(), "DECLINE", "ACCEPT"));
                }
                boolean wrongThenFixed = card.timeline().stream().anyMatch(e -> "DECLINE".equals(e.action()))
                        && !card.status().equals("NOT_ACCEPTED");
                if (wrongThenFixed && expected.equals("ACCEPT")) {
                    a -= 10;
                    issues.add(new AssessmentIssue("DECLINE_CORRECTED", "INFO",
                            "Ошибочный отказ исправлен на «Принята» — верно, но с потерей времени", card.id(), null, null));
                }
                if (card.status().equals("WORK_REFUSED")) {
                    boolean afterWork = card.timeline().stream().anyMatch(e -> "ARRIVE".equals(e.action()));
                    if (!afterWork) {
                        a -= 20;
                        issues.add(new AssessmentIssue("REFUSE_WITHOUT_RESPONSE", "WARNING",
                                "Отказ от выполнения работ без выезда", card.id(), null, null));
                    }
                }
                if (card.requirements() != null && card.requirements().outboundCallRequired()
                        && card.status().equals("COMPLETED")) {
                    boolean called = calls.stream().anyMatch(c -> c.cardId().equals(card.id())
                            && Set.of("ACKNOWLEDGED", "ENDED").contains(c.state()));
                    if (!called) {
                        a -= 30;
                        issues.add(new AssessmentIssue("CALL_MISSING", "WARNING",
                                "Не выполнен обязательный доклад руководителю", card.id(), null, null));
                    }
                }
            }
            if (!traineeTouched) {
                // карточку не трогали — ни действий, ни комментариев оценивать нечего
                actions += 0;
                comm += 0;
                lang += 0;
                continue;
            }
            actions += TextUtil.clamp(a);

            // --- коммуникация: содержательность комментариев
            List<CardTimelineEntry> commented = card.timeline().stream()
                    .filter(e -> Set.of("DECLINE", "REFUSE_WORK", "COMPLETE", "ACCEPT").contains(e.action()))
                    .toList();
            double c = 100;
            StringBuilder allText = new StringBuilder();
            for (CardTimelineEntry entry : commented) {
                String comment = entry.comment() == null ? "" : entry.comment().trim();
                allText.append(comment).append(". ");
                boolean mustComment = !entry.action().equals("ACCEPT");
                if (mustComment && comment.length() < 15) {
                    c -= 30;
                    issues.add(new AssessmentIssue("COMMENT_INCOMPLETE", "WARNING",
                            "Неполный комментарий к статусу «" + entry.action() + "»: нет основания или результата",
                            card.id(), null, comment));
                }
            }
            comm += TextUtil.clamp(c);

            LanguageChecker.Result lr = language.check(allText.toString());
            syntaxErrors += lr.errors();
            lang += lr.score();
            lr.findings().forEach(f -> issues.add(new AssessmentIssue("LANGUAGE", "INFO", f, card.id(), null, null)));
        }

        timing /= n; actions /= n; comm /= n; lang /= n;
        double total = (timing * W_TIME + actions * W_ACTIONS + comm * W_COMM + lang * W_LANG) / 100.0;

        if (timing < 100) recommendations.add("Открывайте карточку сразу при появлении в журнале: норматив 30 секунд считается с момента поступления.");
        if (actions < 100) recommendations.add("Проверяйте компетенцию службы по классификатору перед отказом; ошибочный отказ исправляется статусом «Принята».");
        if (comm < 100) recommendations.add("Комментарий к статусу должен содержать основание и результат: кому передано, что сделано.");
        if (syntaxErrors > 0) recommendations.add("Следите за грамотностью комментариев — их читает следующий диспетчер.");

        return new Assessment(UUID.randomUUID(), sessionId, "COMPLETED", "CARD_ACTIONS",
                TextUtil.round(total), TextUtil.round(timing), TextUtil.round(actions), TextUtil.round(comm),
                TextUtil.round(lang), null, null, null, syntaxErrors, issues, recommendations,
                "AI", TextUtil.round(total), null, null, null, List.of(), List.of());
    }
}
