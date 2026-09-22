package ru.lct.arm112.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.persistence.LessonRepository;
import ru.lct.arm112.persistence.MaterialRepository;
import ru.lct.arm112.persistence.MaterialRepository.MaterialRow;
import ru.lct.arm112.persistence.SessionRepository;
import ru.lct.arm112.persistence.SessionRepository.SessionRow;
import ru.lct.arm112.persistence.UserRepository.AppUser;
import ru.lct.arm112.security.CurrentUser;
import ru.lct.arm112.service.CardFillService;
import ru.lct.arm112.service.RatingService;
import ru.lct.arm112.service.ReferenceDataService;
import ru.lct.arm112.service.TrainingEngine;
import ru.lct.arm112.service.UserService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ru.lct.arm112.api.ApiModels.*;

/** Рабочее место обучающегося: оба режима, результаты, материалы, рейтинг. Преподаватель тоже допущен («делай, как я»). */
@RestController
@RequestMapping("/api/v1")
public class TraineeController {
    private final TrainingEngine engine;
    private final CardFillService cardFill;
    private final ReferenceDataService references;
    private final SessionRepository sessions;
    private final LessonRepository lessons;
    private final AssessmentRepository assessments;
    private final MaterialRepository materials;
    private final UserService users;
    private final RatingService ratings;

    public TraineeController(TrainingEngine engine, CardFillService cardFill, ReferenceDataService references,
                             SessionRepository sessions, LessonRepository lessons, AssessmentRepository assessments,
                             MaterialRepository materials, UserService users, RatingService ratings) {
        this.engine = engine;
        this.cardFill = cardFill;
        this.references = references;
        this.sessions = sessions;
        this.lessons = lessons;
        this.assessments = assessments;
        this.materials = materials;
        this.users = users;
        this.ratings = ratings;
    }

    // ---------------------------------------------------------------- context / references

    @GetMapping("/trainee/context")
    public TraineeContext context(CurrentUser actor) {
        return engine.context(actor);
    }

    @GetMapping("/references")
    public ResponseEntity<ReferenceBundle> references(@RequestParam(required = false) String version) {
        ReferenceBundle bundle = engine.references();
        return ResponseEntity.ok().eTag("\"" + bundle.checksum() + "\"").body(bundle);
    }

    @GetMapping("/references/incident-types")
    public List<IncidentTypeItem> incidentTypes(@RequestParam(required = false) String query) {
        return references.search(query);
    }

    /** Список «что случилось?» — типы верхнего уровня ПОВ-112. */
    @GetMapping("/references/card-types")
    public List<TopTypeItem> cardTypes(@RequestParam(required = false) String query) {
        return references.searchTopTypes(query);
    }

    /** Опросная карта типа верхнего уровня с ветвлением вопросов. */
    @GetMapping("/references/survey-trees/{topTypeId}")
    public SurveyTree surveyTree(@PathVariable String topTypeId) {
        SurveyTree tree = references.surveyTree(topTypeId);
        if (tree == null) throw TrainingEngine.notFound("Опросная карта не найдена: " + topTypeId);
        return tree;
    }

    /** Полный справочник служб ПОВ-112 с видом и территорией. */
    @GetMapping("/references/services")
    public List<ServiceItem> serviceCatalog() {
        return references.serviceCatalog();
    }

    @GetMapping("/references/survey-cards/{incidentTypeId}")
    public SurveyCard surveyCard(@PathVariable String incidentTypeId) {
        return references.surveyCard(incidentTypeId);
    }

    // ---------------------------------------------------------------- sessions

    @GetMapping("/training-sessions/active")
    public TrainingSession activeSession(CurrentUser actor) {
        return engine.activeSession(actor);
    }

    @GetMapping("/training-sessions")
    public List<SessionSummary> sessions(CurrentUser actor) {
        return engine.sessions(actor);
    }

    @GetMapping("/training-sessions/{sessionId}")
    public TrainingSession session(@PathVariable UUID sessionId, CurrentUser actor) {
        return engine.session(sessionId, actor);
    }

    @PostMapping("/training-sessions/{sessionId}/submit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmitResponse submit(@PathVariable UUID sessionId,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey, CurrentUser actor) {
        return engine.submit(sessionId, idempotencyKey, actor);
    }

    /** Журнал оператора 112: свои сохранённые карточки и фоновые карточки смены. */
    @GetMapping("/training-sessions/{sessionId}/journal")
    public JournalPage journal(@PathVariable UUID sessionId, CurrentUser actor) {
        return engine.journal(sessionId, actor);
    }

    @GetMapping("/training-sessions/{sessionId}/events")
    public EventPage events(@PathVariable UUID sessionId,
                            @RequestParam(defaultValue = "0") long afterSequence, CurrentUser actor) {
        return engine.events(sessionId, afterSequence, actor);
    }

    // ---------------------------------------------------------------- cards (CARD_ACTIONS)

    @GetMapping("/cards")
    public CardPage cards(@RequestParam UUID sessionId,
                          @RequestParam(required = false) String status,
                          @RequestParam(defaultValue = "25") int limit, CurrentUser actor) {
        return engine.cards(sessionId, status, Math.max(1, Math.min(limit, 100)), actor);
    }

    @GetMapping("/cards/{cardId}")
    public IncidentCard card(@PathVariable UUID cardId, CurrentUser actor) {
        return engine.card(cardId, actor);
    }

    @PostMapping("/cards/{cardId}/acceptance")
    public IncidentCard acceptance(@PathVariable UUID cardId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                   @Valid @RequestBody AcceptanceCommand command, CurrentUser actor) {
        return engine.acceptance(cardId, command, idempotencyKey, actor);
    }

    @PostMapping("/cards/{cardId}/reaction-events")
    public IncidentCard reaction(@PathVariable UUID cardId,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @Valid @RequestBody ReactionCommand command, CurrentUser actor) {
        return engine.reaction(cardId, command, idempotencyKey, actor);
    }

    @PostMapping("/cards/{cardId}/outbound-calls")
    @ResponseStatus(HttpStatus.CREATED)
    public OutboundCall startCall(@PathVariable UUID cardId,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                  @Valid @RequestBody StartCallRequest request, CurrentUser actor) {
        return engine.startCall(cardId, request, idempotencyKey, actor);
    }

    @GetMapping("/outbound-calls/{callId}")
    public OutboundCall call(@PathVariable UUID callId, CurrentUser actor) {
        return engine.call(callId, actor);
    }

    @PostMapping("/outbound-calls/{callId}/end")
    public OutboundCall endCall(@PathVariable UUID callId,
                                @RequestHeader("Idempotency-Key") String idempotencyKey, CurrentUser actor) {
        return engine.endCall(callId, idempotencyKey, actor);
    }

    // ---------------------------------------------------------------- drafts (CARD_FILL)

    @GetMapping("/card-drafts")
    public List<CardDraft> drafts(@RequestParam UUID sessionId, CurrentUser actor) {
        return cardFill.drafts(sessionId, actor);
    }

    @PostMapping("/card-drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public CardDraft createDraft(@Valid @RequestBody CreateDraftRequest request, CurrentUser actor) {
        return cardFill.start(request.sessionId(), actor);
    }

    @GetMapping("/card-drafts/{draftId}")
    public CardDraft draft(@PathVariable UUID draftId, CurrentUser actor) {
        return cardFill.draft(draftId, actor);
    }

    @PatchMapping("/card-drafts/{draftId}")
    public CardDraft patchDraft(@PathVariable UUID draftId, @Valid @RequestBody CardDraftPatch patch, CurrentUser actor) {
        return cardFill.patch(draftId, patch, actor);
    }

    @PostMapping("/card-drafts/{draftId}/save")
    public CardDraft saveDraft(@PathVariable UUID draftId,
                               @RequestHeader("Idempotency-Key") String idempotencyKey, CurrentUser actor) {
        return cardFill.save(draftId, idempotencyKey, actor);
    }

    // ---------------------------------------------------------------- results

    @GetMapping("/assessments/{assessmentId}")
    public Assessment assessment(@PathVariable UUID assessmentId, CurrentUser actor) {
        return engine.assessment(assessmentId, actor);
    }

    @GetMapping("/trainee/results")
    public List<ResultItem> results(CurrentUser actor) {
        List<ResultItem> result = new ArrayList<>();
        for (SessionRow row : sessions.findByTrainee(actor.id())) {
            Lesson lesson = lessons.findLesson(row.lessonId()).orElse(null);
            if (lesson == null) continue;
            AssessmentRepository.AssessmentRow assessment = assessments.findBySession(row.id()).orElse(null);
            // видимость по виду занятия (решение №10): зачёт — только после публикации
            boolean visible = assessment != null && (!lesson.kind().equals("EXAM") || lesson.resultsPublishedAt() != null);
            result.add(new ResultItem(row.id(), lesson.id(), lesson.title(), lesson.kind(), lesson.mode(),
                    row.completedAt(), visible,
                    visible ? assessment.finalTotal() : null,
                    visible ? (assessment.teacherTotal() != null ? "TEACHER" : "AI") : null,
                    visible ? assessment.id() : null));
        }
        return result;
    }

    @GetMapping("/trainee/rating")
    public Rating rating(CurrentUser actor) {
        AppUser user = users.require(actor.id());
        return ratings.traineeRating(user.id(), user.groupId());
    }

    @GetMapping("/trainee/materials")
    public List<Material> materials(CurrentUser actor) {
        AppUser user = users.require(actor.id());
        if (user.groupId() == null) return List.of();
        return materials.findByGroup(user.groupId()).stream().map(TraineeController::toMaterial).toList();
    }

    @GetMapping("/trainee/materials/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID id, CurrentUser actor) throws IOException {
        AppUser user = users.require(actor.id());
        MaterialRow material = materials.findById(id).orElseThrow(() -> TrainingEngine.notFound("Материал не найден"));
        boolean allowed = user.groupId() != null && materials.groupsOf(id).contains(user.groupId())
                || material.teacherId().equals(actor.id());
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Материал не назначен вашей группе");
        }
        Path path = Path.of(material.storagePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw TrainingEngine.notFound("Файл материала не найден");
        }
        String type = material.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : material.contentType();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(material.fileName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"))
                .contentType(MediaType.parseMediaType(type))
                .contentLength(Files.size(path))
                .body(new FileSystemResource(path));
    }

    static Material toMaterial(MaterialRow row) {
        return new Material(row.id(), row.teacherId(), row.title(), row.fileName(), row.contentType(),
                row.sizeBytes(), row.uploadedAt(), List.of());
    }
}
