package ru.lct.arm112.assessment;

import org.junit.jupiter.api.Test;
import ru.lct.arm112.api.ApiModels.AssessmentIssue;
import ru.lct.arm112.service.assessment.RecommendationRules;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Рекомендации по правилам: одна на код замечания, воспроизводимо, критичное сверху. */
class RecommendationRulesTest {

    private static final UUID CARD_1 = UUID.randomUUID();
    private static final UUID CARD_2 = UUID.randomUUID();

    private static AssessmentIssue issue(String code, String severity, UUID card, Object expected) {
        return new AssessmentIssue(code, severity, "сообщение", card, expected, null);
    }

    @Test
    void oneRecommendationPerCodeCriticalFirst() {
        List<String> result = RecommendationRules.build(List.of(
                issue("PROCESSING_OVERDUE", "WARNING", CARD_1, null),
                issue("ADDRESS_STREET_MISMATCH", "CRITICAL", CARD_1, "Беломорская"),
                issue("ADDRESS_STREET_MISMATCH", "CRITICAL", CARD_2, "Берзарина"),
                issue("PROCESSING_OVERDUE", "WARNING", CARD_2, null)), code -> code);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).startsWith("Опечатка в названии улицы").endsWith("(карточек: 2)");
        assertThat(result.get(1)).startsWith("Карточка заполнялась дольше 3 минут");
    }

    @Test
    void missingServicesAreNamed() {
        List<String> result = RecommendationRules.build(List.of(
                issue("SERVICE_MISSING", "CRITICAL", CARD_1, "101"),
                issue("SERVICE_MISSING", "WARNING", CARD_1, "CEMP")),
                code -> code.equals("CEMP") ? "ЦЭМП" : "Служба " + code);
        assertThat(result).containsExactly(
                "Не оповещены службы: Служба 101, ЦЭМП. Не удаляйте службы, подобранные по классификатору, "
                        + "и добавляйте нужные по обстановке.");
    }

    @Test
    void sameIssuesGiveSameText() {
        List<AssessmentIssue> issues = List.of(issue("CALL_MISSED", "WARNING", null, null),
                issue("INCIDENT_TYPE_MISMATCH", "CRITICAL", CARD_1, List.of("Задымление")));
        assertThat(RecommendationRules.build(issues, code -> code))
                .isEqualTo(RecommendationRules.build(issues, code -> code));
    }

    @Test
    void noIssuesNoRecommendations() {
        assertThat(RecommendationRules.build(List.of(), code -> code)).isEmpty();
    }
}
