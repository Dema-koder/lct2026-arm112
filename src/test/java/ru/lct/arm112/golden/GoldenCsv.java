package ru.lct.arm112.golden;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Запись результатов эталонных прогонов в CSV: одна строка на прогон, файл на этап.
 * Формат намеренно плоский — читается Excel, pandas и `duckdb read_csv` без подготовки.
 *
 * <p>Parquet сознательно не используется: ради одной таблицы на 10 строк пришлось бы тащить
 * parquet-avro и hadoop-common в тестовые зависимости (~40 МБ) в локальный контур без интернета.
 * При росте набора конвертация делается одной строкой в pandas или duckdb.
 */
final class GoldenCsv {

    /** Каталог с результатами в корне репозитория — данные коммитятся, чтобы сравнивать этапы. */
    static final Path DIR = Path.of("benchmarks");

    private static final String HEADER = String.join(";",
            "stage", "run_id", "run_name", "mode", "kind", "scenario_id",
            "expected_total", "actual_total", "total_delta",
            "expected_criteria", "actual_criteria", "criteria_delta_max",
            "expected_issues", "actual_issues", "missing_issues", "unexpected_issues",
            "syntax_errors", "db_issues", "status",
            "run_ms", "setup_ms", "work_ms", "assess_ms", "fetch_ms",
            "http_calls", "commit", "recorded_at");

    private final Path file;
    private final List<String> rows = new ArrayList<>();

    GoldenCsv(String stage) throws IOException {
        Files.createDirectories(DIR);
        this.file = DIR.resolve("golden-" + stage + ".csv");
    }

    void add(GoldenResult r, String stage, String commit) {
        rows.add(String.join(";",
                q(stage), q(r.runId()), q(r.runName()), q(r.mode()), q(r.kind()), q(r.scenarioId()),
                num(r.expectedTotal()), num(r.actualTotal()), num(r.totalDelta()),
                q(r.expectedCriteria()), q(r.actualCriteria()), num(r.maxCriterionDelta()),
                q(r.expectedIssues()), q(r.actualIssues()), q(r.missingIssues()), q(r.unexpectedIssues()),
                String.valueOf(r.syntaxErrors()), String.valueOf(r.dbIssues()), q(r.status()),
                String.valueOf(r.runMs()), String.valueOf(r.setupMs()), String.valueOf(r.workMs()),
                String.valueOf(r.assessMs()), String.valueOf(r.fetchMs()),
                String.valueOf(r.httpCalls()), q(commit), q(Instant.now().toString())));
    }

    /** Пишет файл целиком: BOM для Excel, `;` как разделитель, запятая как десятичный знак. */
    void write() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.addAll(rows);
        Files.write(file, ("﻿" + String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    Path file() {
        return file;
    }

    private static String num(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value).replace('.', ',');
    }

    private static String q(String value) {
        if (value == null) return "";
        return value.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }
}
