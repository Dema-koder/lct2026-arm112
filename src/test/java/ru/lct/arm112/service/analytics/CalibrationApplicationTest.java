package ru.lct.arm112.service.analytics;

import org.junit.jupiter.api.Test;
import ru.lct.arm112.api.ApiModels.Assessment;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.persistence.CalibrationRepository;
import ru.lct.arm112.persistence.CalibrationRepository.ModelRow;
import ru.lct.arm112.persistence.CalibrationRepository.Parameter;
import ru.lct.arm112.persistence.AssessmentRepository.AssessmentRow;
import ru.lct.arm112.api.ApiModels.AdminCalibrationState;
import ru.lct.arm112.api.ApiModels.CriterionScore;
import ru.lct.arm112.service.assessment.AssessmentResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

class CalibrationApplicationTest {
    @Test
    void activeVersionChangesOnlyConfiguredCriteriaAndRecalculatesTotal() {
        AssessmentRepository assessments = mock(AssessmentRepository.class);
        CalibrationRepository models = mock(CalibrationRepository.class);
        CalibrationService service = new CalibrationService(assessments, models);
        UUID modelId = UUID.randomUUID();
        when(models.active("CARD_ACTIONS")).thenReturn(Optional.of(new ModelRow(modelId, "CARD_ACTIONS", 3,
                List.of(new Parameter("timing", "Время", 1, 10, 8, 2, 75, 40)),
                40, 8d, 2d, true, UUID.randomUUID(), Instant.now(), Instant.now(), null)));
        Assessment raw = assessment("CARD_ACTIONS", 50d, 80d, 60d, 70d);

        CalibrationService.AppliedAssessment applied = service.applyActive(new AssessmentResult(raw, List.of()));

        assertThat(applied.raw()).isSameAs(raw);
        assertThat(applied.modelId()).isEqualTo(modelId);
        assertThat(applied.modelVersion()).isEqualTo(3);
        assertThat(applied.result().assessment().timingScore()).isEqualTo(60);
        assertThat(applied.result().assessment().actionsScore()).isEqualTo(80);
        assertThat(applied.result().assessment().totalScore()).isEqualTo(69.5);
    }

    @Test
    void withoutActiveVersionKeepsRawAssessmentUntouched() {
        AssessmentRepository assessments = mock(AssessmentRepository.class);
        CalibrationRepository models = mock(CalibrationRepository.class);
        CalibrationService service = new CalibrationService(assessments, models);
        when(models.active("CARD_FILL")).thenReturn(Optional.empty());
        Assessment raw = assessment("CARD_FILL", 50d, null, null, 70d);
        AssessmentResult result = new AssessmentResult(raw, List.of());

        CalibrationService.AppliedAssessment applied = service.applyActive(result);

        assertThat(applied.result()).isSameAs(result);
        assertThat(applied.raw()).isSameAs(raw);
        assertThat(applied.modelId()).isNull();
    }

    @Test
    void activationCreatesVersionOnlyFromValidatedCandidate() {
        AssessmentRepository assessments = mock(AssessmentRepository.class);
        CalibrationRepository models = mock(CalibrationRepository.class);
        CalibrationService service = new CalibrationService(assessments, models);
        List<AssessmentRow> rows = java.util.stream.IntStream.range(0, 30).mapToObj(index -> {
            double aiScore = 20 + index * 2;
            Assessment ai = assessment("CARD_ACTIONS", aiScore, 80d, 60d, 70d);
            return new AssessmentRow(ai.id(), ai.sessionId(), ai.mode(), ai, ai.totalScore(), ai.timingScore(),
                    ai.languageScore(), 0, UUID.randomUUID(), null, null,
                    List.of(new CriterionScore("timing", aiScore * 0.8 + 10, null)),
                    Instant.now(), Instant.now());
        }).toList();
        UUID actor = UUID.randomUUID();
        ModelRow saved = new ModelRow(UUID.randomUUID(), "CARD_ACTIONS", 1, List.of(), 30,
                8d, 1d, true, actor, Instant.now(), Instant.now(), null);
        when(assessments.findTeacherAssessed("CARD_ACTIONS")).thenReturn(rows);
        when(models.activate(eq("CARD_ACTIONS"), anyList(), anyInt(), anyDouble(), anyDouble(), eq(actor)))
                .thenReturn(saved);
        when(models.active("CARD_ACTIONS")).thenReturn(Optional.of(saved));
        when(models.history("CARD_ACTIONS")).thenReturn(List.of(saved));

        AdminCalibrationState state = service.activate("CARD_ACTIONS", actor);

        assertThat(state.active()).isNotNull();
        assertThat(state.active().version()).isEqualTo(1);
        assertThat(state.candidate().criteria()).extracting("code").contains("timing");
    }

    private static Assessment assessment(String mode, Double timing, Double actions,
                                         Double communication, Double language) {
        return new Assessment(UUID.randomUUID(), UUID.randomUUID(), "COMPLETED", mode, 66.5,
                timing, actions, communication, language,
                "CARD_FILL".equals(mode) ? 80d : null,
                "CARD_FILL".equals(mode) ? 60d : null,
                "CARD_FILL".equals(mode) ? 70d : null,
                0, List.of(), List.of(), "AI", 66.5, null, null, null, List.of(), List.of());
    }
}
