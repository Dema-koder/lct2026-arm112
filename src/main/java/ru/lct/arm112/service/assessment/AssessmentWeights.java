package ru.lct.arm112.service.assessment;

import ru.lct.arm112.api.ApiModels.Assessment;
import ru.lct.arm112.api.ApiModels.CriterionScore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Критерии и веса по режимам — единый источник для оценщиков ИИ, пересчёта итога
 * после правок преподавателя и подписей в интерфейсе.
 */
public final class AssessmentWeights {
    private AssessmentWeights() {}

    public record Criterion(String code, String label, double weight) {}

    public static final List<Criterion> CARD_FILL = List.of(
            new Criterion("address", "Адрес", 40),
            new Criterion("classification", "Тип происшествия", 20),
            new Criterion("services", "Службы", 20),
            new Criterion("timing", "Время", 15),
            new Criterion("language", "Грамотность", 5));

    public static final List<Criterion> CARD_ACTIONS = List.of(
            new Criterion("timing", "Время", 30),
            new Criterion("actions", "Действия", 40),
            new Criterion("communication", "Коммуникация", 15),
            new Criterion("language", "Грамотность", 15));

    public static List<Criterion> forMode(String mode) {
        return "CARD_FILL".equals(mode) ? CARD_FILL : CARD_ACTIONS;
    }

    /** Баллы ИИ по критериям режима в виде списка — для ответа API и пересчёта. */
    public static List<CriterionScore> aiCriteria(Assessment ai) {
        List<CriterionScore> result = new ArrayList<>();
        for (Criterion c : forMode(ai.mode())) {
            result.add(new CriterionScore(c.code(), scoreOf(ai, c.code()), null));
        }
        return result;
    }

    public static Double scoreOf(Assessment ai, String code) {
        return switch (code) {
            case "timing" -> ai.timingScore();
            case "actions" -> ai.actionsScore();
            case "communication" -> ai.communicationScore();
            case "language" -> ai.languageScore();
            case "address" -> ai.addressScore();
            case "classification" -> ai.classificationScore();
            case "services" -> ai.servicesScore();
            default -> null;
        };
    }

    /**
     * Итог по весам: балл преподавателя по критерию, если он задан, иначе балл ИИ.
     * Критерии без балла ни у ИИ, ни у преподавателя в расчёт не входят (веса нормируются).
     */
    public static double total(Assessment ai, List<CriterionScore> teacher) {
        Map<String, Double> overrides = new LinkedHashMap<>();
        for (CriterionScore c : teacher) {
            if (c.score() != null) overrides.put(c.code(), c.score());
        }
        double sum = 0, weights = 0;
        for (Criterion c : forMode(ai.mode())) {
            Double score = overrides.containsKey(c.code()) ? overrides.get(c.code()) : scoreOf(ai, c.code());
            if (score == null) continue;
            sum += score * c.weight();
            weights += c.weight();
        }
        return weights == 0 ? 0 : Math.round(sum / weights * 10.0) / 10.0;
    }
}
