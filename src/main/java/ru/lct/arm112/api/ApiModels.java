package ru.lct.arm112.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    public record LoginRequest(@NotBlank @Size(max = 100) String username,
                               @NotBlank @Size(max = 200) String password) {}

    public record AuthResponse(String accessToken, Instant expiresAt, User user) {}
    public record User(UUID id, String displayName, String role) {}
    public record Workstation(UUID id, String number, String label) {}
    public record TraineeContext(User user, Workstation workstation, Instant serverTime,
                                 TrainingSession activeSession) {}
    public record WsTicket(String ticket, Instant expiresAt) {}

    public record TrainingSession(UUID id, String mode, String state, String title,
                                  @Min(1) @Max(10) int difficulty, String referenceVersion,
                                  Instant serverTime, Instant startedAt, Instant completedAt,
                                  List<UUID> cardIds) {}

    public record CardPage(Instant serverTime, List<CardListItem> items, String nextCursor) {}
    public record CardListItem(UUID id, String number, Instant receivedAt,
                               String incidentTypeLabel, String addressLabel, String description,
                               String senderLabel, String status, List<String> allowedActions, CardSla sla) {}

    public record IncidentCard(UUID id, UUID sessionId, String number, Instant receivedAt,
                               String source, String senderLabel, String status,
                               List<String> allowedActions, CardSla sla, Caller caller,
                               IncidentAddress address, String description,
                               DictionaryItem incidentType, List<String> features,
                               List<DictionaryItem> assignedServices,
                               ScenarioRequirements scenarioRequirements,
                               List<CallTarget> callTargets,
                               List<CardTimelineEntry> timeline,
                               List<OutboundCall> outboundCalls) {}

    public record CardSla(Instant acceptanceDeadlineAt, Instant processingDeadlineAt,
                          boolean acceptanceOverdue, boolean processingOverdue) {}

    public record AcceptanceCommand(@NotNull AcceptanceAction action,
                                    String reasonCode,
                                    @Size(max = 2000) String comment,
                                    Instant clientOccurredAt) {}

    public enum AcceptanceAction { ACCEPT, DECLINE }

    public record ReactionCommand(@NotNull ReactionAction action,
                                  String reasonCode,
                                  @Size(max = 2000) String comment,
                                  Instant clientOccurredAt) {}

    public enum ReactionAction { START_RESPONSE, ARRIVE, START_WORK, REFUSE_WORK, COMPLETE }

    public record Caller(String fullName, String phone, String relation) {}

    public record IncidentAddress(String raw, String region, String district,
                                  String street, String house, String landmark,
                                  Double latitude, Double longitude) {}

    public record ScenarioRequirements(boolean outboundCallRequired,
                                       List<String> requiredTargetIds,
                                       boolean commentRequiredOnCompletion) {}

    public record CallTarget(String id,
                             @Pattern(regexp = "^[0-9]{3,4}$") String shortNumber,
                             String displayName, String organization, String voice) {}

    public record CardTimelineEntry(UUID id, String action, String resultingStatus,
                                    String reasonCode, String comment, Instant occurredAt,
                                    String actorLabel) {}

    public record StartCallRequest(@NotBlank @Pattern(regexp = "^[0-9]{3,4}$") String shortNumber) {}

    public record OutboundCall(UUID id, UUID cardId, CallTarget target, String state,
                               Instant startedAt, Instant connectedAt, Instant endedAt,
                               CallMedia media) {}

    public record CallMedia(String ringbackUrl, String answerUrl,
                            String acknowledgementUrl, Instant expiresAt) {}

    public record DictionaryItem(String id, String code, String label) {}
    public record ReferenceBundle(String referenceVersion, String checksum,
                                  List<DictionaryItem> services,
                                  List<DictionaryItem> reactionReasons) {}

    public record SubmitResponse(UUID assessmentId, String state) {}
    public record Assessment(UUID id, UUID sessionId, String state, Double totalScore,
                             Double timingScore, Double actionsScore,
                             Double communicationScore, Double languageScore,
                             List<AssessmentIssue> issues) {}
    public record AssessmentIssue(String code, String severity, String message,
                                  UUID cardId, Object expected, Object actual) {}

    public record RealtimeEvent(UUID eventId, String type, Instant occurredAt,
                                Instant serverTime, UUID sessionId, long sequence,
                                String resourceId, Map<String, Object> payload) {}

    public record EventPage(List<RealtimeEvent> items) {}
    public record FieldError(String path, String code, String message) {}
    public record ErrorBody(String code, String message, UUID requestId,
                            List<FieldError> fieldErrors, Map<String, Object> details) {}
    public record ErrorEnvelope(ErrorBody error) {}
}
