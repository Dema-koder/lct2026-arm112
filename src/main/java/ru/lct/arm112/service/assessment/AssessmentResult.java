package ru.lct.arm112.service.assessment;

import ru.lct.arm112.api.ApiModels.Assessment;

import java.util.List;
import java.util.UUID;

/**
 * Оценка сессии вместе с разбором по карточкам.
 *
 * <p>Разбор намеренно не входит в {@link Assessment}: тот отдаётся наружу по контракту,
 * где {@code additionalProperties: false}, и новое поле сломало бы строгих клиентов.
 * Внутри же разбор нужен двум потребителям — блоку «по карточкам» на экране разбора
 * и матрице «обучающийся × сценарий» для оценки сложности по Рашу.
 */
public record AssessmentResult(Assessment assessment, List<CardBreakdown> cards) {

    /**
     * Баллы по одной карточке до усреднения по сессии.
     *
     * <p>Критерии, неприменимые к режиму, приходят как {@code null}: у карточки оператора 112
     * нет «действий» и «коммуникации», у карточки ДДС — «адреса», «типа» и «служб».
     */
    public record CardBreakdown(
            UUID cardId,
            String scenarioId,
            Double address,
            Double classification,
            Double services,
            Double timing,
            Double language,
            Double actions,
            Double communication,
            /** Сколько секунд заняла работа с карточкой; null — если карточку не закрыли. */
            Long spentSeconds,
            int issues,
            int criticalIssues) {}
}
