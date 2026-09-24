package ru.lct.arm112.service.assessment;

import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.Assessment;
import ru.lct.arm112.api.ApiModels.AssessmentIssue;
import ru.lct.arm112.api.ApiModels.CardTimelineEntry;
import ru.lct.arm112.persistence.TrainingStateStore.CallState;
import ru.lct.arm112.persistence.TrainingStateStore.CardState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.UUID;
import ru.lct.arm112.service.assessment.AssessmentResult.CardBreakdown;

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

    public AssessmentResult assess(UUID sessionId, List<CardState> cards, List<CallState> calls) {
        return assess(sessionId, cards, calls, DEFAULT_MIN_REACTION_SECONDS);
    }

    public AssessmentResult assess(UUID sessionId, List<CardState> cards, List<CallState> calls,
                                   int minReactionSeconds) {
        List<AssessmentIssue> issues = new ArrayList<>();
        List<CardBreakdown> breakdown = new ArrayList<>();
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
            double cardTiming = TextUtil.clamp(t);
            timing += cardTiming;

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
                String clicked = clickThrough(card, minReactionSeconds);
                if (clicked != null) {
                    a -= 25;
                    issues.add(new AssessmentIssue("CLICK_THROUGH", "WARNING",
                            "Статусы проставлены подряд без паузы (" + clicked + "): карточку не читали",
                            card.id(), minReactionSeconds, null));
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
                breakdown.add(breakdownOf(card, issues, cardTiming, 0.0, 0.0, 0.0));
                continue;
            }
            actions += TextUtil.clamp(a);

            // --- коммуникация: содержательность комментариев
            List<CardTimelineEntry> commented = card.timeline().stream()
                    .filter(e -> Set.of("DECLINE", "REFUSE_WORK", "COMPLETE", "ACCEPT").contains(e.action()))
                    .toList();
            double c = 100;
            // Комментарии склеиваются для проверки грамотности; разделитель добавляется только между
            // непустыми репликами и не дублирует точку в конце — иначе оценщик штрафует за «..»,
            // которые сам же и вставил (находка эталонного прогона R09/R10).
            StringBuilder allText = new StringBuilder();
            for (CardTimelineEntry entry : commented) {
                String comment = entry.comment() == null ? "" : entry.comment().trim();
                if (!comment.isEmpty()) {
                    if (!allText.isEmpty()) allText.append(' ');
                    allText.append(comment);
                    if (".!?".indexOf(comment.charAt(comment.length() - 1)) < 0) allText.append('.');
                }
                boolean mustComment = !entry.action().equals("ACCEPT");
                if (mustComment) {
                    if (comment.length() < MIN_COMMENT) {
                        c -= 30;
                        issues.add(new AssessmentIssue("COMMENT_INCOMPLETE", "WARNING",
                                "Неполный комментарий к статусу «" + entry.action() + "»: нет основания или результата",
                                card.id(), null, comment));
                    } else {
                        // Комментарий достаточной длины ещё не значит содержательный: «передано в работу»
                        // проходит по длине, но не отвечает на вопросы памятки ДДС — что сделано,
                        // кому передано и на каком основании.
                        for (String missing : missingSlots(entry, comment)) {
                            c -= 15;
                            issues.add(new AssessmentIssue("COMMENT_INCOMPLETE", "WARNING",
                                    "Комментарий к статусу «" + entry.action() + "»: " + missing,
                                    card.id(), null, comment));
                        }
                    }
                }
            }
            comm += TextUtil.clamp(c);

            LanguageChecker.Result lr = language.check(allText.toString());
            syntaxErrors += lr.errors();
            lang += lr.score();
            lr.findings().forEach(f -> issues.add(new AssessmentIssue("LANGUAGE", "INFO", f, card.id(), null, null)));

            breakdown.add(breakdownOf(card, issues, cardTiming, TextUtil.clamp(a), TextUtil.clamp(c), lr.score()));
        }

        timing /= n; actions /= n; comm /= n; lang /= n;
        double total = (timing * W_TIME + actions * W_ACTIONS + comm * W_COMM + lang * W_LANG) / 100.0;

        if (timing < 100) recommendations.add("Открывайте карточку сразу при появлении в журнале: норматив 30 секунд считается с момента поступления.");
        if (actions < 100) recommendations.add("Проверяйте компетенцию службы по классификатору перед отказом; ошибочный отказ исправляется статусом «Принята».");
        if (comm < 100) recommendations.add("Комментарий к статусу должен содержать основание и результат: кому передано, что сделано.");
        if (syntaxErrors > 0) recommendations.add("Следите за грамотностью комментариев — их читает следующий диспетчер.");

        Assessment assessment = new Assessment(UUID.randomUUID(), sessionId, "COMPLETED", "CARD_ACTIONS",
                TextUtil.round(total), TextUtil.round(timing), TextUtil.round(actions), TextUtil.round(comm),
                TextUtil.round(lang), null, null, null, syntaxErrors, issues, recommendations,
                "AI", TextUtil.round(total), null, null, null, List.of(), List.of());
        return new AssessmentResult(assessment, List.copyOf(breakdown));
    }

    /** Норматив по умолчанию, если настройка не задана. */
    private static final int DEFAULT_MIN_REACTION_SECONDS = 5;
    /** Статусы, между которыми обучающийся обязан был что-то прочитать или сделать. */
    private static final List<String> REACTION_CHAIN =
            List.of("ACCEPT", "START_RESPONSE", "ARRIVE", "START_WORK", "COMPLETE");

    /**
     * Прокликивание: два статуса реагирования подряд быстрее норматива.
     *
     * <p>Между «начал реагирование» и «прибыл» обучающийся должен был хотя бы прочитать
     * карточку. Секунда между ними означает, что статусы проставлены не глядя —
     * работа сымитирована, хотя формально цепочка полная.
     *
     * @return подпись перехода, на котором поймано, или null
     */
    private static String clickThrough(CardState card, int minReactionSeconds) {
        List<CardTimelineEntry> chain = card.timeline().stream()
                .filter(e -> "TRAINEE".equals(e.actorRole()) && REACTION_CHAIN.contains(e.action()))
                .sorted(java.util.Comparator.comparing(CardTimelineEntry::occurredAt))
                .toList();
        for (int i = 1; i < chain.size(); i++) {
            long gap = Duration.between(chain.get(i - 1).occurredAt(), chain.get(i).occurredAt()).toSeconds();
            if (gap < minReactionSeconds) {
                return chain.get(i - 1).action() + " → " + chain.get(i).action();
            }
        }
        return null;
    }

    /** Ниже этой длины комментарий считается отпиской без разбора слотов. */
    private static final int MIN_COMMENT = 15;
    /** Что сделано. */
    private static final Pattern ACTION = Pattern.compile(
            "(?iu)(передан|направл|выехал|выезжа|прибыл|устранен|ликвидирован|оказан|доставлен|"
            + "зарегистрирован|отказан|принят|выполнен|проведен|проведён|обнаружен|локализован)");
    /** Кому или чем: служба, подразделение, силы и средства. */
    private static final Pattern ADDRESSEE = Pattern.compile(
            "(?iu)(служб|дежурн|диспетчер|руководител|бригад|расчет|расчёт|наряд|подразделен|"
            + "отдел|участков|скор|полиц|пожарн|спасател|дпс|ддс|мчс|экипаж)");
    /** На каком основании — словами; код причины засчитывается отдельно. */
    private static final Pattern REASON = Pattern.compile(
            "(?iu)(так как|потому|по причине|в связи|не в компетенц|компетенц|дубл|ошибочн|"
            + "принадлежност|территор|отсутств|нет сил|нет свободн)");
    /** Статусы, для которых памятка ДДС требует основание, а не только результат. */
    private static final Set<String> NEEDS_REASON = Set.of("DECLINE", "REFUSE_WORK");

    /** Какие смысловые слоты в комментарии не заполнены. */
    private static List<String> missingSlots(CardTimelineEntry entry, String comment) {
        List<String> missing = new ArrayList<>();
        if (!ACTION.matcher(comment).find()) missing.add("не сказано, что сделано");
        if (!ADDRESSEE.matcher(comment).find()) missing.add("не указано, кому передано или какие силы задействованы");
        if (NEEDS_REASON.contains(entry.action())
                && entry.reasonCode() == null && !REASON.matcher(comment).find()) {
            missing.add("не указано основание");
        }
        return missing;
    }

    /** Строка разбора по карточке ДДС: применимые критерии плюс счётчики замечаний. */
    private static CardBreakdown breakdownOf(CardState card, List<AssessmentIssue> issues, double timing,
                                             double actions, double communication, double language) {
        List<AssessmentIssue> own = issues.stream().filter(i -> card.id().equals(i.cardId())).toList();
        Long spent = card.acceptedAt() == null ? null
                : Duration.between(card.acceptedAt(), lastTouch(card)).toSeconds();
        return new CardBreakdown(card.id(), card.scenarioId(), null, null, null, timing, language,
                actions, communication, spent, null, null, null, null, own.size(),
                (int) own.stream().filter(i -> "CRITICAL".equals(i.severity())).count());
    }

    /** Момент последнего действия по карточке — по нему считается, сколько заняла отработка. */
    private static Instant lastTouch(CardState card) {
        return card.timeline().stream().map(CardTimelineEntry::occurredAt)
                .max(Instant::compareTo).orElse(card.acceptedAt());
    }
}
