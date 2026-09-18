package ru.lct.arm112.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class TrainingEngine {
    public static final UUID USER_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
    public static final UUID WORKSTATION_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
    public static final UUID SESSION_ID = UUID.fromString("62c0a11f-cfb0-4cff-922f-a56118079602");
    public static final UUID CARD_ID = UUID.fromString("11b418c1-f5a4-4a91-8be0-7ba970f63a45");

    private static final String REFERENCE_VERSION = "classifier-046-24";
    private static final CallTarget SHIFT_SUPERVISOR = new CallTarget(
            "target.shift_supervisor", "1102", "Начальник дежурной смены",
            "Пожарно-спасательный центр", "MALE");

    private final EventService events;
    private final Map<UUID, MutableCard> cards = new ConcurrentHashMap<>();
    private final Map<UUID, MutableCall> calls = new ConcurrentHashMap<>();
    private final Map<UUID, Assessment> assessments = new ConcurrentHashMap<>();
    private final Map<String, IdempotentResult> idempotency = new ConcurrentHashMap<>();
    private final ScheduledExecutorService callScheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "training-call-simulator");
        thread.setDaemon(true);
        return thread;
    });

    private volatile String sessionState = "ACTIVE";
    private final Instant sessionStartedAt = Instant.now();
    private volatile Instant sessionCompletedAt;

    public TrainingEngine(EventService events) {
        this.events = events;
    }

    @PostConstruct
    void seed() {
        Instant receivedAt = Instant.now();
        MutableCard card = new MutableCard();
        card.id = CARD_ID;
        card.sessionId = SESSION_ID;
        card.number = "112-2026-000001";
        card.receivedAt = receivedAt;
        card.source = "SYSTEM_112";
        card.senderLabel = "Оператор 112";
        card.status = "RECEIVED";
        card.acceptanceDeadlineAt = receivedAt.plusSeconds(30);
        card.caller = new Caller("Сидоров Иван Сергеевич", "+7 916 126-34-71", "очевидец");
        card.address = new IncidentAddress(
                "Москва, у станции Москва-Пассажирская-Киевская, около участкового пункта полиции",
                "Москва", null, null, null,
                "станция Москва-Пассажирская-Киевская, участковый пункт полиции", null, null);
        card.description = "Горит мусорный контейнер. Пострадавших нет.";
        card.incidentType = new DictionaryItem("incident.fire.garbage_container",
                "FIRE_GARBAGE_CONTAINER", "Возгорание мусорного контейнера");
        card.features = List.of("Открытое горение", "Пострадавших нет");
        card.assignedServices = List.of(new DictionaryItem("service.dds.fire", "101",
                "Пожарно-спасательная служба"));
        card.requirements = new ScenarioRequirements(true,
                List.of(SHIFT_SUPERVISOR.id()), true);
        card.callTargets = List.of(SHIFT_SUPERVISOR);
        card.timeline.add(new CardTimelineEntry(UUID.randomUUID(), "DELIVERED", "RECEIVED",
                null, null, receivedAt, "Система"));
        cards.put(card.id, card);
    }

    @PreDestroy
    void shutdown() {
        callScheduler.shutdownNow();
    }

    public User user() {
        return new User(USER_ID, "Иванов Иван Иванович", "TRAINEE");
    }

    public Workstation workstation() {
        return new Workstation(WORKSTATION_ID, "12", "Учебное место №12");
    }

    public TraineeContext context() {
        return new TraineeContext(user(), workstation(), Instant.now(), session());
    }

    public TrainingSession session() {
        return new TrainingSession(SESSION_ID, "CARD_ACTIONS", sessionState,
                "Отработка действий ДДС — уровень 4", 4, REFERENCE_VERSION,
                Instant.now(), sessionStartedAt, sessionCompletedAt,
                cards.values().stream().map(card -> card.id).toList());
    }

    public TrainingSession session(UUID id) {
        requireSession(id);
        return session();
    }

    public CardPage cards(UUID sessionId, String status, int limit) {
        requireSession(sessionId);
        List<CardListItem> result = cards.values().stream()
                .filter(card -> status == null || card.status.equals(status))
                .sorted(Comparator.comparing((MutableCard card) -> card.receivedAt).reversed())
                .limit(limit)
                .map(this::toListItem)
                .toList();
        return new CardPage(Instant.now(), result, null);
    }

    public IncidentCard card(UUID id) {
        MutableCard card = requireCard(id);
        synchronized (card) {
            // Открытие карточки диспетчером на АРМ-112 автоматически переводит её
            // в статус «Получена службой» — см. памятку ДДС, таблица статусов реагирования.
            if (card.status.equals("RECEIVED")) {
                card.status = "RECEIVED_BY_SERVICE";
                addTimeline(card, "RECEIVE", null, null);
                publishCard(card, "card.updated");
            }
            return toView(card);
        }
    }

    public IncidentCard acceptance(UUID cardId, AcceptanceCommand command,
                                   String idempotencyKey) {
        return idempotent("acceptance:" + cardId, idempotencyKey, command.toString(), () -> {
            MutableCard card = requireCard(cardId);
            synchronized (card) {
                requireActiveSession();
                boolean fresh = card.status.equals("RECEIVED") || card.status.equals("RECEIVED_BY_SERVICE");
                if (command.action() == AcceptanceAction.DECLINE) {
                    if (!fresh) throw invalidTransition(card.status);
                    requireReason(command.reasonCode(), command.comment());
                    card.status = "NOT_ACCEPTED";
                    addTimeline(card, "DECLINE", command.reasonCode(), command.comment());
                } else {
                    // Из «Не принята» единственный доступный переход — «Принята»:
                    // диспетчер обязан исправить ошибочный отказ. См. памятку ДДС, «Что делать если…».
                    if (!fresh && !card.status.equals("NOT_ACCEPTED")) throw invalidTransition(card.status);
                    card.status = "ACCEPTED";
                    card.acceptedAt = Instant.now();
                    card.processingDeadlineAt = card.acceptedAt.plusSeconds(180);
                    addTimeline(card, "ACCEPT", command.reasonCode(), command.comment());
                }
                publishCard(card, "card.updated");
                return toView(card);
            }
        });
    }

    public IncidentCard reaction(UUID cardId, ReactionCommand command,
                                 String idempotencyKey) {
        return idempotent("reaction:" + cardId, idempotencyKey, command.toString(), () -> {
            MutableCard card = requireCard(cardId);
            synchronized (card) {
                requireActiveSession();
                switch (command.action()) {
                    // «Выбрать статусы реагирования можно только последовательно» — памятка ДДС,
                    // раздел «Проставление статусов реагирования». Перепрыгнуть через статус нельзя.
                    case START_RESPONSE -> {
                        requireState(card, "ACCEPTED");
                        card.status = "RESPONSE_STARTED";
                    }
                    case ARRIVE -> {
                        requireState(card, "RESPONSE_STARTED");
                        card.status = "ARRIVED";
                    }
                    case START_WORK -> {
                        requireState(card, "ARRIVED");
                        card.status = "WORK_IN_PROGRESS";
                    }
                    // Отказ от выполнения работ доступен на любом этапе после приёма —
                    // он присутствует в выпадающем списке наравне со статусами хода работ.
                    case REFUSE_WORK -> {
                        requireOneOf(card, "ACCEPTED", "RESPONSE_STARTED", "ARRIVED", "WORK_IN_PROGRESS");
                        requireReason(command.reasonCode(), command.comment());
                        card.status = "WORK_REFUSED";
                    }
                    case COMPLETE -> {
                        requireState(card, "WORK_IN_PROGRESS");
                        if (card.requirements.commentRequiredOnCompletion() && isBlank(command.comment())) {
                            throw validation("comment", "REQUIRED", "Для завершения требуется комментарий");
                        }
                        if (card.requirements.outboundCallRequired() && !requiredCallCompleted(card)) {
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "VALIDATION_ERROR", "Не выполнен обязательный исходящий звонок");
                        }
                        card.status = "COMPLETED";
                    }
                }
                addTimeline(card, command.action().name(), command.reasonCode(), command.comment());
                publishCard(card, "card.updated");
                return toView(card);
            }
        });
    }

    public OutboundCall startCall(UUID cardId, StartCallRequest request,
                                  String idempotencyKey) {
        return idempotent("start-call:" + cardId, idempotencyKey, request.toString(), () -> {
            MutableCard card = requireCard(cardId);
            synchronized (card) {
                requireActiveSession();
                requireOneOf(card, "ACCEPTED", "RESPONSE_STARTED", "ARRIVED", "WORK_IN_PROGRESS");
                CallTarget target = card.callTargets.stream()
                        .filter(item -> item.shortNumber().equals(request.shortNumber()))
                        .findFirst()
                        .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "CALL_TARGET_NOT_ALLOWED", "Короткий номер отсутствует в сценарии"));
                boolean hasActive = card.callIds.stream().map(calls::get)
                        .anyMatch(call -> call != null && !isTerminalCall(call.state));
                if (hasActive) {
                    throw new ApiException(HttpStatus.CONFLICT, "CALL_ALREADY_ACTIVE",
                            "Для карточки уже выполняется звонок");
                }
                MutableCall call = new MutableCall();
                call.id = UUID.randomUUID();
                call.cardId = card.id;
                call.target = target;
                call.state = "DIALING";
                call.startedAt = Instant.now();
                calls.put(call.id, call);
                card.callIds.add(call.id);
                publishCall(call);
                scheduleCallState(call.id, "RINGING", 300);
                scheduleCallState(call.id, "CONNECTED", 700);
                scheduleCallState(call.id, "ACKNOWLEDGED", 1200);
                return toView(call);
            }
        });
    }

    public OutboundCall call(UUID id) {
        return toView(requireCall(id));
    }

    public OutboundCall endCall(UUID callId, String idempotencyKey) {
        return idempotent("end-call:" + callId, idempotencyKey, "END", () -> {
            MutableCall call = requireCall(callId);
            synchronized (call) {
                if (isTerminalCall(call.state)) {
                    throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                            "Звонок уже завершён");
                }
                call.state = "ENDED";
                call.endedAt = Instant.now();
                publishCall(call);
                return toView(call);
            }
        });
    }

    public SubmitResponse submit(UUID sessionId, String idempotencyKey) {
        return idempotent("submit:" + sessionId, idempotencyKey, "SUBMIT", () -> {
            requireSession(sessionId);
            requireActiveSession();
            boolean unfinished = cards.values().stream().anyMatch(card ->
                    !List.of("NOT_ACCEPTED", "WORK_REFUSED", "COMPLETED").contains(card.status));
            if (unfinished) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                        "Не все карточки завершены");
            }
            UUID assessmentId = UUID.randomUUID();
            List<AssessmentIssue> issues = new ArrayList<>();
            cards.values().stream().filter(card -> card.acceptanceOverdue)
                    .forEach(card -> issues.add(new AssessmentIssue("ACCEPTANCE_OVERDUE", "WARNING",
                            "Превышено время принятия карточки", card.id, 30, null)));
            Assessment assessment = new Assessment(assessmentId, SESSION_ID, "COMPLETED",
                    issues.isEmpty() ? 100.0 : 90.0,
                    issues.isEmpty() ? 100.0 : 70.0, 100.0, 100.0, 100.0, issues);
            assessments.put(assessmentId, assessment);
            sessionState = "COMPLETED";
            sessionCompletedAt = Instant.now();
            events.publish(SESSION_ID, "training.session_state_changed", SESSION_ID.toString(),
                    Map.of("state", sessionState));
            events.publish(SESSION_ID, "assessment.completed", assessmentId.toString(),
                    Map.of("assessmentId", assessmentId.toString()));
            return new SubmitResponse(assessmentId, "CALCULATING");
        });
    }

    public Assessment assessment(UUID id) {
        Assessment assessment = assessments.get(id);
        if (assessment == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Результат не найден");
        }
        return assessment;
    }

    public EventPage events(UUID sessionId, long afterSequence) {
        requireSession(sessionId);
        return new EventPage(events.after(sessionId, afterSequence));
    }

    public ReferenceBundle references() {
        return new ReferenceBundle(REFERENCE_VERSION, "sha256:demo-classifier-046-24",
                List.of(new DictionaryItem("service.dds.fire", "101", "Пожарно-спасательная служба")),
                List.of(
                        new DictionaryItem("reason.wrong_recipient", "WRONG_RECIPIENT", "Карточка направлена ошибочно"),
                        new DictionaryItem("reason.no_resources", "NO_RESOURCES", "Нет доступных сил и средств")));
    }

    @Scheduled(fixedRate = 1000)
    void detectOverdue() {
        Instant now = Instant.now();
        for (MutableCard card : cards.values()) {
            synchronized (card) {
                if (!card.acceptanceOverdue && card.status.equals("RECEIVED")
                        && now.isAfter(card.acceptanceDeadlineAt)) {
                    card.acceptanceOverdue = true;
                    publishCard(card, "card.acceptance_overdue");
                }
                if (!card.processingOverdue && card.processingDeadlineAt != null
                        && !List.of("NOT_ACCEPTED", "WORK_REFUSED", "COMPLETED").contains(card.status)
                        && now.isAfter(card.processingDeadlineAt)) {
                    card.processingOverdue = true;
                    publishCard(card, "card.processing_overdue");
                }
            }
        }
    }

    private void scheduleCallState(UUID callId, String state, long delayMs) {
        callScheduler.schedule(() -> {
            MutableCall call = calls.get(callId);
            if (call == null) return;
            synchronized (call) {
                if (isTerminalCall(call.state)) return;
                call.state = state;
                if (state.equals("CONNECTED")) call.connectedAt = Instant.now();
                publishCall(call);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void publishCard(MutableCard card, String type) {
        events.publish(SESSION_ID, type, card.id.toString(),
                Map.of("cardId", card.id.toString(), "status", card.status));
    }

    private void publishCall(MutableCall call) {
        events.publish(SESSION_ID, "outbound_call.updated", call.id.toString(),
                Map.of("callId", call.id.toString(), "cardId", call.cardId.toString(),
                        "state", call.state));
    }

    private void addTimeline(MutableCard card, String action, String reasonCode, String comment) {
        // «Добавлена» и «Получена службой» — технические статусы, их проставляет система.
        boolean technical = action.equals("DELIVERED") || action.equals("RECEIVE");
        card.timeline.add(new CardTimelineEntry(UUID.randomUUID(), action, card.status,
                reasonCode, comment, Instant.now(), technical ? "Система" : "Обучающийся"));
    }

    private boolean requiredCallCompleted(MutableCard card) {
        return card.callIds.stream().map(calls::get).filter(call -> call != null)
                .anyMatch(call -> card.requirements.requiredTargetIds().contains(call.target.id())
                        && List.of("ACKNOWLEDGED", "ENDED").contains(call.state));
    }

    private CardListItem toListItem(MutableCard card) {
        return new CardListItem(card.id, card.number, card.receivedAt,
                card.incidentType.label(), card.address.raw(), card.description,
                card.senderLabel, card.status, allowedActions(card), sla(card));
    }

    private IncidentCard toView(MutableCard card) {
        synchronized (card) {
            return new IncidentCard(card.id, card.sessionId, card.number, card.receivedAt,
                    card.source, card.senderLabel, card.status, allowedActions(card), sla(card),
                    card.caller, card.address, card.description, card.incidentType, card.features,
                    card.assignedServices, card.requirements, card.callTargets,
                    List.copyOf(card.timeline), card.callIds.stream().map(calls::get)
                    .filter(call -> call != null).map(this::toView).toList());
        }
    }

    private CardSla sla(MutableCard card) {
        return new CardSla(card.acceptanceDeadlineAt, card.processingDeadlineAt,
                card.acceptanceOverdue, card.processingOverdue);
    }

    private List<String> allowedActions(MutableCard card) {
        return switch (card.status) {
            case "RECEIVED", "RECEIVED_BY_SERVICE" -> List.of("ACCEPT", "DECLINE");
            case "NOT_ACCEPTED" -> List.of("ACCEPT");
            case "ACCEPTED" -> List.of("START_RESPONSE", "REFUSE_WORK");
            case "RESPONSE_STARTED" -> List.of("ARRIVE", "REFUSE_WORK");
            case "ARRIVED" -> List.of("START_WORK", "REFUSE_WORK");
            case "WORK_IN_PROGRESS" -> List.of("COMPLETE", "REFUSE_WORK");
            default -> List.of();
        };
    }

    private OutboundCall toView(MutableCall call) {
        synchronized (call) {
            return new OutboundCall(call.id, call.cardId, call.target, call.state,
                    call.startedAt, call.connectedAt, call.endedAt,
                    new CallMedia(null, null, null, call.startedAt.plusSeconds(900)));
        }
    }

    private MutableCard requireCard(UUID id) {
        MutableCard card = cards.get(id);
        if (card == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Карточка не найдена");
        return card;
    }

    private MutableCall requireCall(UUID id) {
        MutableCall call = calls.get(id);
        if (call == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Звонок не найден");
        return call;
    }

    private void requireSession(UUID id) {
        if (!SESSION_ID.equals(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Занятие не найдено");
        }
    }

    private void requireActiveSession() {
        if (!sessionState.equals("ACTIVE")) {
            throw new ApiException(HttpStatus.CONFLICT, "SESSION_NOT_ACTIVE", "Занятие не активно");
        }
    }

    private void requireState(MutableCard card, String expected) {
        if (!card.status.equals(expected)) throw invalidTransition(card.status);
    }

    private void requireOneOf(MutableCard card, String... allowed) {
        for (String state : allowed) {
            if (card.status.equals(state)) return;
        }
        throw invalidTransition(card.status);
    }

    private ApiException invalidTransition(String currentState) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "Действие недоступно в текущем состоянии карточки", List.of(),
                Map.of("currentState", currentState));
    }

    private void requireReason(String reasonCode, String comment) {
        if (isBlank(reasonCode) && isBlank(comment)) {
            throw validation("comment", "REQUIRED", "Укажите причину или комментарий");
        }
    }

    private ApiException validation(String path, String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                "Проверьте переданные поля", List.of(new FieldError(path, code, message)), Map.of());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isTerminalCall(String state) {
        return List.of("ENDED", "NO_ANSWER", "FAILED", "CANCELLED").contains(state);
    }

    @SuppressWarnings("unchecked")
    private <T> T idempotent(String operation, String key, String signature, Supplier<T> action) {
        try {
            UUID.fromString(key);
        } catch (Exception ex) {
            throw validation("Idempotency-Key", "INVALID", "Ожидается UUID");
        }
        String storageKey = operation + ":" + key;
        IdempotentResult existing = idempotency.get(storageKey);
        if (existing != null) {
            if (!existing.signature.equals(signature)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "Ключ уже использован с другим запросом");
            }
            return (T) existing.value;
        }
        synchronized (idempotency) {
            existing = idempotency.get(storageKey);
            if (existing != null) return (T) existing.value;
            T value = action.get();
            idempotency.put(storageKey, new IdempotentResult(signature, value));
            return value;
        }
    }

    private static final class MutableCard {
        UUID id;
        UUID sessionId;
        String number;
        Instant receivedAt;
        String source;
        String senderLabel;
        String status;
        Instant acceptanceDeadlineAt;
        Instant processingDeadlineAt;
        Instant acceptedAt;
        boolean acceptanceOverdue;
        boolean processingOverdue;
        Caller caller;
        IncidentAddress address;
        String description;
        DictionaryItem incidentType;
        List<String> features = List.of();
        List<DictionaryItem> assignedServices = List.of();
        ScenarioRequirements requirements;
        List<CallTarget> callTargets = List.of();
        List<CardTimelineEntry> timeline = new ArrayList<>();
        List<UUID> callIds = new ArrayList<>();
    }

    private static final class MutableCall {
        UUID id;
        UUID cardId;
        CallTarget target;
        String state;
        Instant startedAt;
        Instant connectedAt;
        Instant endedAt;
    }

    private record IdempotentResult(String signature, Object value) {}
}
