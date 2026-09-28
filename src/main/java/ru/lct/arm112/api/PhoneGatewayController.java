package ru.lct.arm112.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.arm112.api.ApiModels.PhoneCallStatusUpdate;
import ru.lct.arm112.service.PhoneCallAlertService;

/** Обратный вызов внутреннего телефонного шлюза; доступ защищён отдельным серверным токеном. */
@RestController
@RequestMapping("/api/v1/integrations/phone/calls")
public class PhoneGatewayController {
    private final PhoneCallAlertService calls;

    public PhoneGatewayController(PhoneCallAlertService calls) {
        this.calls = calls;
    }

    @PutMapping("/{gatewayCallId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable String gatewayCallId,
                       @RequestHeader("X-ARM112-Gateway-Token") String token,
                       @Valid @RequestBody PhoneCallStatusUpdate update) {
        calls.updateStatus(gatewayCallId, token, update);
    }
}
