package ru.lct.arm112.golden;

/**
 * Итог одного эталонного прогона: ожидаемое, фактическое, расхождение и тайминги.
 *
 * <p>Тайминги делятся на четыре части, чтобы после каждого этапа было видно,
 * что именно подорожало: подготовка занятия, действия обучающегося, оценка, чтение результата.
 */
record GoldenResult(
        String runId,
        String runName,
        String mode,
        String kind,
        String scenarioId,
        double expectedTotal,
        double actualTotal,
        String expectedCriteria,
        String actualCriteria,
        double maxCriterionDelta,
        String expectedIssues,
        String actualIssues,
        String missingIssues,
        String unexpectedIssues,
        int syntaxErrors,
        /** Сколько замечаний реально записано в assessment_issue. */
        int dbIssues,
        String status,
        long runMs,
        /** Логин, создание и старт занятия. */
        long setupMs,
        /** Действия обучающегося: приём вызова, правки, статусы, звонок. */
        long workMs,
        /** Сохранение последней карточки или завершение — здесь считается оценка. */
        long assessMs,
        /** Чтение готовой оценки через API. */
        long fetchMs,
        int httpCalls) {

    double totalDelta() {
        return actualTotal - expectedTotal;
    }
}
