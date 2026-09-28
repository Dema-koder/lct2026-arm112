package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.AlertCallAttempt;
import ru.lct.arm112.api.ApiModels.PhoneCallStatusUpdate;
import ru.lct.arm112.persistence.AlertCallRepository;
import ru.lct.arm112.service.PhoneCallAlertService;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhoneCallAlertServiceTest {
    private final AlertCallRepository repository = mock(AlertCallRepository.class);
    private final PhoneCallAlertService service = new PhoneCallAlertService(
            new ObjectMapper(), repository, "https://pbx.invalid/calls", "gateway-secret", "", "+7 495 000-12-34");

    @Test
    void acceptsAuthenticatedGatewayStatusAndNormalizesAnswer() {
        Instant now = Instant.now();
        when(repository.findByGatewayCallId("pbx-42")).thenReturn(Optional.of(new AlertCallAttempt(
                UUID.randomUUID(), null, "jobs", "SERVICE_PROBLEM", "•••• 1234", "Сбой",
                "ACCEPTED", null, 1, 1, "pbx-42", null, now, now)));
        when(repository.updateByGatewayCallId(eq("pbx-42"), eq("ANSWERED"), eq(true), eq(null), any()))
                .thenReturn(1);

        service.updateStatus("pbx-42", "gateway-secret", new PhoneCallStatusUpdate("answered", null, null));

        verify(repository).updateByGatewayCallId(eq("pbx-42"), eq("ANSWERED"), eq(true), eq(null), any());
    }

    @Test
    void rejectsWrongGatewayToken() {
        assertThatThrownBy(() -> service.updateStatus("pbx-42", "wrong",
                new PhoneCallStatusUpdate("ANSWERED", true, null)))
                .isInstanceOf(ApiException.class);
        verify(repository, never()).updateByGatewayCallId(any(), any(), any(), any(), any());
    }

    @Test
    void recordsFailureWithMaskedRecipientWhenGatewayIsNotConfigured() {
        PhoneCallAlertService disabled = new PhoneCallAlertService(
                new ObjectMapper(), repository, "", "", "", "+7 495 000-12-34");
        disabled.sendDetailed("Проверка", "TEST", null);

        ArgumentCaptor<AlertCallAttempt> captor = ArgumentCaptor.forClass(AlertCallAttempt.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
        assertThat(captor.getValue().recipient()).isEqualTo("•••• 1234");
        assertThat(captor.getValue().errorMessage()).isEqualTo("Телефонный шлюз не настроен");
    }
}
