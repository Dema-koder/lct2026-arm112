package ru.lct.arm112.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import ru.lct.arm112.service.TrainingEngine;

import java.util.UUID;

import static ru.lct.arm112.api.ApiModels.*;

@RestController
@RequestMapping("/api/v1")
public class TraineeController {
    private final TrainingEngine engine;

    public TraineeController(TrainingEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/trainee/context")
    public TraineeContext context() {
        return engine.context();
    }

    @GetMapping("/references")
    public ResponseEntity<ReferenceBundle> references(@RequestParam(required = false) String version) {
        ReferenceBundle bundle = engine.references();
        return ResponseEntity.ok().eTag("\"" + bundle.checksum() + "\"").body(bundle);
    }

    @GetMapping("/training-sessions/active")
    public TrainingSession activeSession() {
        return engine.session();
    }

    @GetMapping("/training-sessions/{sessionId}")
    public TrainingSession session(@PathVariable UUID sessionId) {
        return engine.session(sessionId);
    }

    @PostMapping("/training-sessions/{sessionId}/submit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmitResponse submit(@PathVariable UUID sessionId,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return engine.submit(sessionId, idempotencyKey);
    }

    @GetMapping("/training-sessions/{sessionId}/events")
    public EventPage events(@PathVariable UUID sessionId,
                            @RequestParam(defaultValue = "0") long afterSequence) {
        return engine.events(sessionId, afterSequence);
    }

    @GetMapping("/cards")
    public CardPage cards(@RequestParam UUID sessionId,
                          @RequestParam(required = false) String status,
                          @RequestParam(defaultValue = "25") int limit) {
        return engine.cards(sessionId, status, Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/cards/{cardId}")
    public IncidentCard card(@PathVariable UUID cardId) {
        return engine.card(cardId);
    }

    @PostMapping("/cards/{cardId}/acceptance")
    public IncidentCard acceptance(@PathVariable UUID cardId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                   @Valid @RequestBody AcceptanceCommand command) {
        return engine.acceptance(cardId, command, idempotencyKey);
    }

    @PostMapping("/cards/{cardId}/reaction-events")
    public IncidentCard reaction(@PathVariable UUID cardId,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @Valid @RequestBody ReactionCommand command) {
        return engine.reaction(cardId, command, idempotencyKey);
    }

    @PostMapping("/cards/{cardId}/outbound-calls")
    @ResponseStatus(HttpStatus.CREATED)
    public OutboundCall startCall(@PathVariable UUID cardId,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                  @Valid @RequestBody StartCallRequest request) {
        return engine.startCall(cardId, request, idempotencyKey);
    }

    @GetMapping("/outbound-calls/{callId}")
    public OutboundCall call(@PathVariable UUID callId) {
        return engine.call(callId);
    }

    @PostMapping("/outbound-calls/{callId}/end")
    public OutboundCall endCall(@PathVariable UUID callId,
                                @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return engine.endCall(callId, idempotencyKey);
    }

    @GetMapping("/assessments/{assessmentId}")
    public Assessment assessment(@PathVariable UUID assessmentId) {
        return engine.assessment(assessmentId);
    }
}
