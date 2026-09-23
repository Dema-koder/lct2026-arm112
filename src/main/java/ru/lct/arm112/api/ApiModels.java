package ru.lct.arm112.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    // ---------------------------------------------------------------- auth / users

    public record LoginRequest(@NotBlank @Size(max = 100) String username,
                               @NotBlank @Size(max = 200) String password) {}

    public record AuthResponse(String accessToken, Instant expiresAt, User user,
                               String refreshToken, Instant refreshExpiresAt) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record LogoutRequest(@NotBlank String refreshToken) {}
    public record User(UUID id, String displayName, String role, String login,
                       String workstationNumber, UUID groupId) {}
    public record PasswordChangeRequest(@NotBlank String current,
                                        @NotBlank @Size(min = 4, max = 200) String next) {}
    public record Workstation(UUID id, String number, String label) {}
    public record TraineeContext(User user, Workstation workstation, Instant serverTime,
                                 TrainingSession activeSession) {}
    public record WsTicket(String ticket, Instant expiresAt) {}

    // ---------------------------------------------------------------- sessions

    public record TrainingSession(UUID id, String mode, String state, String title,
                                  @Min(1) @Max(10) int difficulty, String referenceVersion,
                                  Instant serverTime, Instant startedAt, Instant completedAt,
                                  List<UUID> cardIds, List<UUID> draftIds, UUID lessonId,
                                  String lessonKind, int pendingScenarios, String ownServiceCode,
                                  String intensity, IncomingCall incomingCall, int queuedCalls) {}

    /** Входящий вызов оператору 112: звонит, пока обучающийся не примет его (POST /card-drafts). */
    public record IncomingCall(UUID id, String phone, String callerName, Instant ringingSince, int missedCount) {}

    /** Строка журнала оператора 112: своя сохранённая карточка или фоновая карточка смены. */
    public record JournalRow(UUID id, String kind, String number, Instant receivedAt, String workstationNumber,
                             String incidentTypeLabel, String addressLabel, String description,
                             String callerName, List<String> services, String status) {}
    public record JournalPage(List<JournalRow> rows) {}

    public record SessionSummary(UUID id, UUID lessonId, String lessonTitle, String lessonKind,
                                 String mode, String state, Instant startedAt, Instant completedAt) {}

    // ---------------------------------------------------------------- cards (CARD_ACTIONS)

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
                               List<OutboundCall> outboundCalls,
                               List<Hint> hints, String ownServiceCode, String scenarioTitle) {}

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
                                    String actorLabel, String actorRole) {}

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

    /** Подсказка обучающемуся в режиме тренировки (решение №10). */
    public record Hint(String field, String message) {}

    // ---------------------------------------------------------------- scenarios / card fill

    public record FormalAddress(String country, String region, String locality, String object,
                                String okrug, String district, String street, String house,
                                String building, String structure, String apartment,
                                String entrance, String floor, String code, String descriptive) {}

    public record ScenarioCaller(String fullName, String phone) {}

    public record Scenario(String id, String title, String source, String category, @Min(1) @Max(10) int difficulty,
                           String callerText, ScenarioCaller caller, String rawAddress,
                           FormalAddress expectedAddress, List<String> expectedIncidentTypes,
                           List<String> expectedServices, boolean addressClarified,
                           String expectedDecision, String expectedDecisionReason,
                           boolean outboundCallRequired,
                           boolean referenceConfirmed, Instant referenceConfirmedAt,
                           UUID createdBy, Instant createdAt) {}

    public record ScenarioListItem(String id, String title, String source, String category, int difficulty,
                                   String callerText, String rawAddress, FormalAddress expectedAddress,
                                   List<String> expectedIncidentTypes, List<String> expectedServices,
                                   boolean referenceConfirmed, Instant createdAt) {}

    public record ScenarioUpsert(@Size(max = 64) String id, @NotBlank @Size(max = 200) String title,
                                 @NotBlank String category,
                                 @Min(1) @Max(10) int difficulty,
                                 @NotBlank @Size(max = 2000) String callerText, ScenarioCaller caller,
                                 @Size(max = 1000) String rawAddress, FormalAddress expectedAddress,
                                 List<String> expectedIncidentTypes, List<String> expectedServices,
                                 String expectedDecision, String expectedDecisionReason,
                                 boolean outboundCallRequired) {}

    public record GenerateRequest(@NotBlank String category, @Min(1) @Max(20) int count,
                                  @Min(1) @Max(10) int difficulty) {}

    public record CardDraft(UUID id, UUID sessionId, String scenarioId, String number,
                            Instant startedAt, Instant savedAt, Instant deadlineAt, String state,
                            String callerText, DraftPhones phones, DraftCaller caller,
                            FormalAddress address, DraftFlags flags, List<String> incidentTypeIds,
                            List<SurveyAnswer> surveyAnswers, String description,
                            List<DraftService> services, List<Hint> hints, String scenarioTitle,
                            String callerAddress, String topTypeId) {}

    public record DraftPhones(String ani, String provided, String onSite) {}
    public record DraftCaller(String fullName, String status) {}
    public record DraftFlags(boolean victims, Integer victimsCount, boolean ambulanceRefused,
                             boolean blocked, boolean noContact, boolean callDropped) {}
    public record SurveyAnswer(String questionId, String optionId, String text) {}
    public record DraftService(String code, String label, boolean auto) {}

    public record CardDraftPatch(DraftPhones phones, DraftCaller caller, FormalAddress address,
                                 DraftFlags flags, List<String> incidentTypeIds,
                                 List<SurveyAnswer> surveyAnswers,
                                 @Size(max = 1999) String description,
                                 List<String> extraServiceCodes, String topTypeId) {}

    public record CreateDraftRequest(@NotNull UUID sessionId) {}

    public record IncidentTypeItem(String id, String label, String category,
                                   boolean frequent, boolean significant) {}
    /** Позиция списка «что случилось?» (КАРТОЧКА 112.docx). */
    public record TopTypeItem(String id, String label, boolean frequent) {}
    /** Опросная карта типа верхнего уровня: вопросы ветвятся по ответам (showWhen). */
    public record SurveyTree(String topTypeId, String label, List<SurveyQuestion> questions, String defaultType) {}
    public record SurveyCard(String id, String incidentTypeId, List<SurveyQuestion> questions) {}
    public record SurveyQuestion(String id, String text, String kind, List<DictionaryItem> options,
                                 List<Map<String, List<String>>> showWhen, boolean synthetic) {}
    /** Служба из справочника ПОВ-112 (СЛУЖБЫ 112.docx): вид и территория обслуживания. */
    public record ServiceItem(String code, String label, String fullName, String kind,
                              String okrug, String district, String settlement) {}

    // ---------------------------------------------------------------- assessment

    public record SubmitResponse(UUID assessmentId, String state) {}
    public record Assessment(UUID id, UUID sessionId, String state, String mode, Double totalScore,
                             Double timingScore, Double actionsScore,
                             Double communicationScore, Double languageScore,
                             Double addressScore, Double classificationScore, Double servicesScore,
                             Integer syntaxErrors,
                             List<AssessmentIssue> issues, List<String> recommendations,
                             String source, Double aiTotalScore, Double teacherTotalScore,
                             String teacherComment, Instant teacherAssessedAt,
                             List<CriterionScore> aiCriteria, List<CriterionScore> teacherCriteria) {}
    /** Оценка по одному критерию: код, балл (null — оставить оценку ИИ), комментарий. */
    public record CriterionScore(@NotBlank String code, @Min(0) @Max(100) Double score,
                                 @Size(max = 1000) String comment) {}
    public record AssessmentIssue(String code, String severity, String message,
                                  UUID cardId, Object expected, Object actual) {}
    /** Итог считается сервером по весам режима, если переданы критерии; иначе берётся total. */
    public record TeacherAssessment(@Min(0) @Max(100) Double total,
                                    @Size(max = 2000) String comment,
                                    List<@Valid CriterionScore> criteria) {}

    public record ResultItem(UUID sessionId, UUID lessonId, String lessonTitle, String lessonKind,
                             String mode, Instant completedAt, boolean visible,
                             Double finalTotal, String source, UUID assessmentId) {}
    public record Rating(Double value, Integer rank, Integer groupSize, Integer completedSessions) {}

    // ---------------------------------------------------------------- lessons (teacher)

    public record Group(UUID id, String name, UUID teacherId, String teacherName,
                        List<User> members) {}
    public record GroupUpsert(@NotBlank @Size(max = 200) String name, UUID teacherId) {}

    public record Lesson(UUID id, UUID teacherId, UUID groupId, String groupName, String title,
                         String kind, String mode, String cardSource, String state,
                         List<String> scenarioIds, Instant createdAt, Instant startedAt,
                         Instant completedAt, Instant resultsPublishedAt, int sessionCount,
                         String serviceCode, String intensity) {}

    /** serviceCode — служба обучающегося в режиме действий (по умолчанию 101); intensity — поток вводных. */
    public record LessonCreate(@NotBlank @Size(max = 200) String title, UUID groupId,
                               @NotBlank String kind, @NotBlank String mode, @NotBlank String cardSource,
                               @NotEmpty List<String> scenarioIds, @NotEmpty List<UUID> traineeIds,
                               String serviceCode, String intensity) {}

    public record LessonMonitor(Lesson lesson, List<MonitorRow> sessions) {}
    public record MonitorRow(UUID sessionId, UUID traineeId, String traineeName, String workstationNumber,
                             String state, String currentStatus, boolean acceptanceOverdue,
                             boolean processingOverdue, Instant startedAt, Instant completedAt,
                             int completedCards, int totalCards, Double finalTotal) {}

    public record LessonReport(Lesson lesson, List<ReportRow> rows, Double groupRating) {}
    public record ReportRow(UUID sessionId, UUID traineeId, String traineeName, String workstationNumber,
                            Double timingScore, Integer syntaxErrors, Double level,
                            Double aiTotal, Double teacherTotal, Double finalTotal, String state) {}

    public record SessionDetail(SessionSummary session, User trainee, List<IncidentCard> cards,
                                List<CardDraft> drafts, Assessment assessment) {}

    public record Material(UUID id, UUID teacherId, String title, String fileName, String contentType,
                           long sizeBytes, Instant uploadedAt, List<UUID> groupIds) {}

    // ---------------------------------------------------------------- admin

    public record UserAdminView(UUID id, String login, String displayName, String role,
                                String workstationNumber, UUID groupId, boolean active,
                                Instant createdAt) {}
    public record UserCreate(@NotBlank @Size(max = 100) String login,
                             @NotBlank @Size(min = 4, max = 200) String password,
                             @NotBlank @Size(max = 200) String displayName,
                             @NotBlank String role, @Size(max = 10) String workstationNumber,
                             UUID groupId) {}
    public record UserUpdate(@NotBlank @Size(max = 200) String displayName,
                             @NotBlank String role, @Size(max = 10) String workstationNumber,
                             UUID groupId) {}
    public record PasswordReset(@NotBlank @Size(min = 4, max = 200) String password) {}

    public record AuditEntry(UUID id, UUID actorUserId, String actorLogin, String actorRole,
                             String action, String resourceType, String resourceId,
                             Integer httpStatus, UUID requestId, String clientIp,
                             String payload, Instant occurredAt) {}
    public record AuditPage(List<AuditEntry> items, String nextCursor) {}

    public record SystemHealth(String status, String database, int openSockets,
                               int activeSessions, String version, Instant serverTime) {}
    public record BackupInfo(String fileName, long sizeBytes, Instant createdAt) {}
    public record RestoreRequest(@NotBlank String confirm) {}
    public record LogTail(List<String> lines) {}

    // ---------------------------------------------------------------- realtime / errors

    public record RealtimeEvent(UUID eventId, String type, Instant occurredAt,
                                Instant serverTime, UUID sessionId, long sequence,
                                String resourceId, Map<String, Object> payload) {}

    public record EventPage(List<RealtimeEvent> items) {}
    public record FieldError(String path, String code, String message) {}
    public record ErrorBody(String code, String message, UUID requestId,
                            List<FieldError> fieldErrors, Map<String, Object> details) {}
    public record ErrorEnvelope(ErrorBody error) {}
}
