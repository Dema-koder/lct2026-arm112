package ru.lct.arm112.service.assessment;

import ru.lct.arm112.api.ApiModels.AssessmentIssue;
import ru.lct.arm112.api.ApiModels.FormalAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сверка формализованного адреса с эталоном. Главный критерий по Q&A §6 — опечатка в улице
 * отправляет службы не туда («Дубнинская / Дубининская»).
 */
public final class AddressMatcher {
    private AddressMatcher() {}

    public record Result(double score, List<AssessmentIssue> issues) {}

    public static Result score(FormalAddress actual, FormalAddress expected, UUID cardId) {
        List<AssessmentIssue> issues = new ArrayList<>();
        if (expected == null) {
            return new Result(100, issues);
        }
        String expStreet = TextUtil.normalize(expected.street());
        String actStreet = TextUtil.normalize(actual == null ? null : actual.street());
        String expHouse = TextUtil.normalize(expected.house());
        String actHouse = TextUtil.normalize(actual == null ? null : actual.house());
        String expLocality = TextUtil.normalize(expected.locality());
        String actLocality = TextUtil.normalize(actual == null ? null : actual.locality());

        double score = 100;
        if (!expStreet.isEmpty()) {
            if (actStreet.isEmpty()) {
                score -= 60;
                issues.add(new AssessmentIssue("ADDRESS_STREET_MISSING", "CRITICAL",
                        "Не указана улица", cardId, expected.street(), null));
            } else if (!actStreet.equals(expStreet)) {
                int distance = TextUtil.levenshtein(actStreet, expStreet);
                boolean contains = actStreet.contains(expStreet) || expStreet.contains(actStreet);
                if (distance <= 2 && !contains) {
                    // почти правильно — это и есть опечатка в названии улицы
                    score -= 40;
                    issues.add(new AssessmentIssue("ADDRESS_STREET_MISMATCH", "CRITICAL",
                            "Опечатка в названии улицы: службы могут выехать не по тому адресу",
                            cardId, expected.street(), actual.street()));
                } else if (contains) {
                    score -= 5;
                } else {
                    score -= 60;
                    issues.add(new AssessmentIssue("ADDRESS_STREET_WRONG", "CRITICAL",
                            "Улица не совпадает с уточнённым адресом", cardId, expected.street(), actual.street()));
                }
            }
        }
        if (!expHouse.isEmpty()) {
            if (actHouse.isEmpty()) {
                score -= 20;
                issues.add(new AssessmentIssue("ADDRESS_HOUSE_MISSING", "WARNING",
                        "Не указан номер дома", cardId, expected.house(), null));
            } else if (!actHouse.equals(expHouse)) {
                score -= 20;
                issues.add(new AssessmentIssue("ADDRESS_HOUSE_MISMATCH", "WARNING",
                        "Номер дома не совпадает с уточнённым адресом", cardId, expected.house(), actual.house()));
            }
        }
        if (!expLocality.isEmpty() && !actLocality.isEmpty() && !actLocality.equals(expLocality)) {
            score -= 10;
            issues.add(new AssessmentIssue("ADDRESS_LOCALITY_MISMATCH", "WARNING",
                    "Населённый пункт не совпадает", cardId, expected.locality(), actual.locality()));
        }
        for (String[] pair : new String[][]{
                {"building", expected.building(), actual == null ? null : actual.building()},
                {"structure", expected.structure(), actual == null ? null : actual.structure()},
                {"apartment", expected.apartment(), actual == null ? null : actual.apartment()}}) {
            String exp = TextUtil.normalize(pair[1]);
            String act = TextUtil.normalize(pair[2]);
            if (!exp.isEmpty() && !exp.equals(act)) {
                score -= 5;
                issues.add(new AssessmentIssue("ADDRESS_DETAIL_MISMATCH", "INFO",
                        "Не совпадает поле адреса: " + pair[0], cardId, pair[1], pair[2]));
            }
        }
        return new Result(TextUtil.clamp(score), issues);
    }
}
