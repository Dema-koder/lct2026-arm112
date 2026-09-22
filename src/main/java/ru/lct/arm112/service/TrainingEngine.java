package ru.lct.arm112.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.*;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.persistence.AssessmentRepository.AssessmentRow;
import ru.lct.arm112.persistence.LessonRepository;
import ru.lct.arm112.persistence.SessionRepository;
import ru.lct.arm112.persistence.SessionRepository.SessionRow;
import ru.lct.arm112.persistence.TrainingStateStore;
import ru.lct.arm112.persistence.TrainingStateStore.CallState;
import ru.lct.arm112.persistence.TrainingStateStore.CardState;
import ru.lct.arm112.persistence.TrainingStateStore.IncomingCallState;
import ru.lct.arm112.persistence.TrainingStateStore.TrainingSnapshot;
import ru.lct.arm112.persistence.UserRepository;
import ru.lct.arm112.persistence.UserRepository.AppUser;
import ru.lct.arm112.security.CurrentUser;
import ru.lct.arm112.security.Role;
import ru.lct.arm112.service.assessment.CardActionsAssessor;
import ru.lct.arm112.service.assessment.CardFillAssessor;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Состояние персональных занятий (решение №1): по SessionState на обучающегося.
 * Режим CARD_ACTIONS (экран ДДС) реализован здесь; режим CARD_FILL — в CardFillService поверх того же состояния.
 */
@Service
public class TrainingEngine {
    private static final Logger log = LoggerFactory.getLogger(TrainingEngine.class);

    /** Служба по умолчанию, за которую играет обучающийся-диспетчер ДДС (в занятии можно выбрать другую). */
    public static final String OWN_SERVICE_CODE = "101";
    /** Сколько звонит входящий вызов оператору 112, прежде чем считается пропущенным. */
    static final int RING_SECONDS = 30;
    /** После скольких пропусков заявитель перестаёт перезванивать — вызов потерян. */
    static final int MAX_MISSES = 2;
    /** Через сколько секунд заявитель перезванивает после пропущенного вызова. */
    static final int REDIAL_SECONDS = 20;

    /**
     * Интенсивность потока (замечания 3, 5): интервал между приходом вводных, число фоновых карточек
     * в журнале оператора 112 и «сложность» сессии. SEQUENTIAL — по одной, следующая после закрытия текущей.
     */
    public enum Intensity {
        SEQUENTIAL(0, 0, 3, 5),
        LOW(90, 180, 3, 3),
        MEDIUM(45, 90, 6, 5),
        HIGH(15, 40, 10, 8);

        final int minSeconds;
        final int maxSeconds;
        final int backgroundCards;
        final int difficulty;

        Intensity(int minSeconds, int maxSeconds, int backgroundCards, int difficulty) {
            this.minSeconds = minSeconds; this.maxSeconds = maxSeconds;
            this.backgroundCards = backgroundCards; this.difficulty = difficulty;
        }

        public static boolean isValid(String value) {
            for (Intensity i : values()) if (i.name().equals(value)) return true;
            return false;
        }

        static Intensity of(String value) {
            return value == null || !isValid(value) ? SEQUENTIAL : valueOf(value);
        }

        Instant nextArrival(Instant from) {
            if (this == SEQUENTIAL) return null;
            return from.plusSeconds(ThreadLocalRandom.current().nextInt(minSeconds, maxSeconds + 1));
        }
    }
    static final CallTarget SHIFT_SUPERVISOR = new CallTarget(
            "target.shift_supervisor", "1102", "Начальник дежурной смены",
            "Пожарно-спасательный центр", "MALE");
    private static final List<String> TERMINAL_CARD = List.of("NOT_ACCEPTED", "WORK_REFUSED", "COMPLETED");

    private final EventService events;
    private final TrainingStateStore stateStore;
    private final SessionRepository sessionRepo;
    private final LessonRepository lessonRepo;
    private final UserRepository userRepo;
    private final AssessmentRepository assessmentRepo;
    private final ScenarioService scenarios;
    private final ReferenceDataService references;
    private final SettingsService settings;
    private final SessionAccess access;
    private final CardActionsAssessor actionsAssessor;
    private final CardFillAssessor fillAssessor;

    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> cardIndex = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> callIndex = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> draftIndex = new ConcurrentHashMap<>();
    private final Map<String, IdempotentResult> idempotency = new ConcurrentHashMap<>();
    private final ScheduledExecutorService callScheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "call-simulator");
        thread.setDaemon(true);
        return thread;
    });

    public TrainingEngine(EventService events, TrainingStateStore stateStore, SessionRepository sessionRepo,
                          LessonRepository lessonRepo, UserRepository userRepo, AssessmentRepository assessmentRepo,
                          ScenarioService scenarios, ReferenceDataService references, SettingsService settings,
                          SessionAccess access, CardActionsAssessor actionsAssessor, CardFillAssessor fillAssessor) {
        this.events = events;
        this.stateStore = stateStore;
        this.sessionRepo = sessionRepo;
        this.lessonRepo = lessonRepo;
        this.userRepo = userRepo;
        this.assessmentRepo = assessmentRepo;
        this.scenarios = scenarios;
        this.references = references;
        this.settings = settings;
        this.access = access;
        this.actionsAssessor = actionsAssessor;
        this.fillAssessor = fillAssessor;
    }

    @PostConstruct
    void initialize() {
        int restored = 0;
        for (SessionRow row : sessionRepo.findByState("ACTIVE")) {
            Optional<TrainingSnapshot> snapshot = stateStore.load(row.id());
            if (snapshot.isEmpty()) continue;
            Lesson lesson = lessonRepo.findLesson(row.lessonId()).orElse(null);
            if (lesson == null) continue;
            SessionState state = restore(snapshot.get(), lesson);
            sessions.put(state.id, state);
            access.remember(state.id, state.traineeId, state.teacherId, state.lessonId);
            restored++;
        }
        resumeActiveCalls();
        if (restored > 0) log.info("Восстановлено активных сессий: {}", restored);
    }

    /** Полная перезагрузка состояния из БД (после восстановления резервной копии). */
    public synchronized void reload() {
        sessions.clear();
        cardIndex.clear();
        callIndex.clear();
        draftIndex.clear();
        idempotency.clear();
        initialize();
    }

    @PreDestroy
    void shutdown() {
        callScheduler.shutdownNow();
    }

    // ================================================================= lifecycle (LessonService)

    /** Открывает персональную сессию: очередь сценариев, первая карточка (для ДДС) — сразу в журнал. */
    public void open(SessionRow row, Lesson lesson, List<Scenario> lessonScenarios) {
        SessionState state = new SessionState(row.id(), lesson.id(), row.traineeId(), lesson.teacherId(),
                lesson.mode(), lesson.kind(), row.workstationNumber(), lesson.title());
        state.state = "ACTIVE";
        state.startedAt = row.startedAt() == null ? Instant.now() : row.startedAt();
        state.ownServiceCode = lesson.serviceCode() == null ? OWN_SERVICE_CODE : lesson.serviceCode();
        state.intensity = Intensity.of(lesson.intensity());
        for (Scenario scenario : lessonScenarios) state.pending.add(scenario.id());
        sessions.put(state.id, state);
        access.remember(state.id, state.traineeId, state.teacherId, state.lessonId);
        Instant now = Instant.now();
        if (lesson.mode().equals("CARD_ACTIONS")) {
            // первая карточка сразу, остальные — по интенсивности
            deliverNextCard(state);
        } else {
            state.background.addAll(backgroundCards(state, lessonScenarios));
            arriveNextCall(state, now);
            presentNextCall(state, now);
        }
        state.nextArrivalAt = state.intensity.nextArrival(now);
        persist(state);
        events.publish(state.id, "training.session_state_changed", state.id.toString(),
                Map.of("state", "ACTIVE", "mode", state.mode));
    }

    /** Принудительное завершение преподавателем: оценивается то, что есть. */
    public Assessment forceComplete(UUID sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            SessionRow row = sessionRepo.findById(sessionId).orElseThrow(() -> notFound("Занятие не найдено"));
            return assessmentRepo.findBySession(row.id()).map(AssessmentRepository::finalOf).orElse(null);
        }
        synchronized (state) {
            if (!state.state.equals("ACTIVE")) {
                return assessmentRepo.findBySession(sessionId).map(AssessmentRepository::finalOf).orElse(null);
            }
            return complete(state);
        }
    }

    // ================================================================= trainee context

    public TraineeContext context(CurrentUser actor) {
        AppUser user = userRepo.findById(actor.id()).orElseThrow(() -> notFound("Пользователь не найден"));
        SessionState active = activeSessionOf(actor.id());
        String number = user.workstationNumber() == null ? "—" : user.workstationNumber();
        Workstation workstation = new Workstation(UUID.nameUUIDFromBytes(("ws-" + number).getBytes()), number,
                "Учебное место №" + number);
        return new TraineeContext(UserService.toUser(user), workstation, Instant.now(),
                active == null ? null : toView(active));
    }

    public TrainingSession activeSession(CurrentUser actor) {
        SessionState state = activeSessionOf(actor.id());
        if (state == null) throw notFound("Активного занятия нет");
        return toView(state);
    }

    public TrainingSession session(UUID id, CurrentUser actor) {
        return toView(requireAccessible(id, actor));
    }

    public List<SessionSummary> sessions(CurrentUser actor) {
        List<SessionSummary> result = new ArrayList<>();
        for (SessionRow row : sessionRepo.findByTrainee(actor.id())) {
            Lesson lesson = lessonRepo.findLesson(row.lessonId()).orElse(null);
            result.add(new SessionSummary(row.id(), row.lessonId(), lesson == null ? "" : lesson.title(),
                    lesson == null ? null : lesson.kind(), lesson == null ? null : lesson.mode(), row.state(),
                    row.startedAt(), row.completedAt()));
        }
        return result;
    }

    private SessionState activeSessionOf(UUID traineeId) {
        return sessions.values().stream()
                .filter(s -> s.traineeId.equals(traineeId) && s.state.equals("ACTIVE"))
                .max(Comparator.comparing(s -> s.startedAt))
                .orElse(null);
    }

    // ================================================================= cards (CARD_ACTIONS)

    public CardPage cards(UUID sessionId, String status, int limit, CurrentUser actor) {
        SessionState state = requireAccessible(sessionId, actor);
        List<CardListItem> result = state.cards.values().stream()
                .filter(card -> status == null || card.status.equals(status))
                .sorted(Comparator.comparing((MutableCard card) -> card.receivedAt).reversed())
                .limit(limit)
                .map(this::toListItem)
                .toList();
        return new CardPage(Instant.now(), result, null);
    }

    public IncidentCard card(UUID id, CurrentUser actor) {
        SessionState state = sessionOfCard(id, actor);
        MutableCard card = state.cards.get(id);
        boolean changed = false;
        IncidentCard view;
        synchronized (card) {
            // Открытие карточки диспетчером на АРМ-112 автоматически переводит её
            // в статус «Получена службой» — см. памятку ДДС, таблица статусов реагирования.
            if (card.status.equals("RECEIVED") && actor.is(Role.TRAINEE)) {
                card.status = "RECEIVED_BY_SERVICE";
                addTimeline(card, "RECEIVE", null, null, null);
                publishCard(state, card, "card.updated");
                changed = true;
            }
            view = toView(state, card);
        }
        if (changed) persist(state);
        return view;
    }

    public IncidentCard acceptance(UUID cardId, AcceptanceCommand command, String idempotencyKey, CurrentUser actor) {
        SessionState state = sessionOfCard(cardId, actor);
        return idempotent(state, "acceptance:" + cardId, idempotencyKey, command.toString(), () -> {
            MutableCard card = state.cards.get(cardId);
            synchronized (card) {
                requireActive(state);
                boolean fresh = card.status.equals("RECEIVED") || card.status.equals("RECEIVED_BY_SERVICE");
                if (command.action() == AcceptanceAction.DECLINE) {
                    if (!fresh) throw invalidTransition(card.status);
                    requireReason(command.reasonCode(), command.comment());
                    card.status = "NOT_ACCEPTED";
                    addTimeline(card, "DECLINE", command.reasonCode(), command.comment(), actor);
                } else {
                    // Из «Не принята» единственный доступный переход — «Принята»:
                    // диспетчер обязан исправить ошибочный отказ. См. памятку ДДС, «Что делать если…».
                    if (!fresh && !card.status.equals("NOT_ACCEPTED")) throw invalidTransition(card.status);
                    card.status = "ACCEPTED";
                    card.acceptedAt = Instant.now();
                    card.processingDeadlineAt = card.acceptedAt.plusSeconds(
                            settings.integer(SettingsService.PROCESSING_SECONDS, 180));
                    addTimeline(card, "ACCEPT", command.reasonCode(), command.comment(), actor);
                }
                publishCard(state, card, "card.updated");
                afterCardChange(state, card);
                return toView(state, card);
            }
        });
    }

    public IncidentCard reaction(UUID cardId, ReactionCommand command, String idempotencyKey, CurrentUser actor) {
        SessionState state = sessionOfCard(cardId, actor);
        return idempotent(state, "reaction:" + cardId, idempotencyKey, command.toString(), () -> {
            MutableCard card = state.cards.get(cardId);
            synchronized (card) {
                requireActive(state);
                switch (command.action()) {
                    // «Выбрать статусы реагирования можно только последовательно» — памятка ДДС.
                    case START_RESPONSE -> { requireState(card, "ACCEPTED"); card.status = "RESPONSE_STARTED"; }
                    case ARRIVE -> { requireState(card, "RESPONSE_STARTED"); card.status = "ARRIVED"; }
                    case START_WORK -> { requireState(card, "ARRIVED"); card.status = "WORK_IN_PROGRESS"; }
                    // Отказ от выполнения работ доступен на любом этапе после приёма.
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
                        if (card.requirements.outboundCallRequired() && !requiredCallCompleted(state, card)) {
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "VALIDATION_ERROR", "Не выполнен обязательный исходящий звонок");
                        }
                        card.status = "COMPLETED";
                    }
                }
                addTimeline(card, command.action().name(), command.reasonCode(), command.comment(), actor);
                publishCard(state, card, "card.updated");
                afterCardChange(state, card);
                return toView(state, card);
            }
        });
    }

    public OutboundCall startCall(UUID cardId, StartCallRequest request, String idempotencyKey, CurrentUser actor) {
        SessionState state = sessionOfCard(cardId, actor);
        return idempotent(state, "start-call:" + cardId, idempotencyKey, request.toString(), () -> {
            MutableCard card = state.cards.get(cardId);
            synchronized (card) {
                requireActive(state);
                requireOneOf(card, "ACCEPTED", "RESPONSE_STARTED", "ARRIVED", "WORK_IN_PROGRESS");
                CallTarget target = card.callTargets.stream()
                        .filter(item -> item.shortNumber().equals(request.shortNumber()))
                        .findFirst()
                        .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "CALL_TARGET_NOT_ALLOWED", "Короткий номер отсутствует в сценарии"));
                boolean hasActive = card.callIds.stream().map(state.calls::get)
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
                state.calls.put(call.id, call);
                callIndex.put(call.id, state.id);
                card.callIds.add(call.id);
                publishCall(state, call);
                scheduleCallChain(state, call.id, "DIALING");
                return toView(call);
            }
        });
    }

    public OutboundCall call(UUID id, CurrentUser actor) {
        SessionState state = sessionOfCall(id, actor);
        return toView(state.calls.get(id));
    }

    public OutboundCall endCall(UUID callId, String idempotencyKey, CurrentUser actor) {
        SessionState state = sessionOfCall(callId, actor);
        return idempotent(state, "end-call:" + callId, idempotencyKey, "END", () -> {
            MutableCall call = state.calls.get(callId);
            synchronized (call) {
                if (isTerminalCall(call.state)) {
                    throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Звонок уже завершён");
                }
                call.state = "ENDED";
                call.endedAt = Instant.now();
                publishCall(state, call);
                return toView(call);
            }
        });
    }

    // ================================================================= submit / assessment

    public SubmitResponse submit(UUID sessionId, String idempotencyKey, CurrentUser actor) {
        SessionState state = requireAccessible(sessionId, actor);
        return idempotent(state, "submit:" + sessionId, idempotencyKey, "SUBMIT", () -> {
            synchronized (state) {
                requireActive(state);
                if (state.mode.equals("CARD_ACTIONS")) {
                    boolean unfinished = state.cards.values().stream().anyMatch(c -> !TERMINAL_CARD.contains(c.status));
                    if (unfinished || !state.pending.isEmpty()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                                "Не все карточки завершены");
                    }
                } else {
                    if (!fillFlowFinished(state)) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                                "Не все карточки сохранены");
                    }
                }
                Assessment assessment = complete(state);
                return new SubmitResponse(assessment.id(), "COMPLETED");
            }
        });
    }

    /** Оценка ИИ, запись в таблицу, закрытие сессии и — если все сессии занятия закрыты — занятия. */
    Assessment complete(SessionState state) {
        Assessment assessment = state.mode.equals("CARD_ACTIONS")
                ? actionsAssessor.assess(state.id, cardStates(state), callStates(state))
                : fillAssessor.assess(state.id, List.copyOf(state.drafts.values()),
                        scenarios.requireAll(state.drafts.values().stream().map(CardDraft::scenarioId).distinct().toList()),
                        state.missedCalls, state.lost.size());
        assessmentRepo.insert(assessment, state.id);
        state.state = "COMPLETED";
        state.completedAt = Instant.now();
        sessionRepo.setState(state.id, "COMPLETED", state.completedAt);
        persist(state);
        events.publish(state.id, "training.session_state_changed", state.id.toString(), Map.of("state", "COMPLETED"));
        events.publish(state.id, "assessment.completed", assessment.id().toString(),
                Map.of("assessmentId", assessment.id().toString()));
        boolean allDone = sessionRepo.findByLesson(state.lessonId).stream().allMatch(r -> r.state().equals("COMPLETED"));
        if (allDone) {
            lessonRepo.setState(state.lessonId, "COMPLETED", null, Instant.now());
        }
        return assessment;
    }

    public Assessment assessment(UUID id, CurrentUser actor) {
        AssessmentRow row = assessmentRepo.findById(id).orElseThrow(() -> notFound("Результат не найден"));
        SessionRow session = sessionRepo.findById(row.sessionId()).orElseThrow(() -> notFound("Занятие не найдено"));
        Lesson lesson = lessonRepo.findLesson(session.lessonId()).orElseThrow(() -> notFound("Занятие не найдено"));
        checkAccess(session.traineeId(), lesson.teacherId(), actor);
        requireVisible(lesson, actor);
        return AssessmentRepository.finalOf(row);
    }

    /** Правило видимости результата по виду занятия (решение №10). */
    public void requireVisible(Lesson lesson, CurrentUser actor) {
        if (actor.is(Role.TRAINEE) && lesson.kind().equals("EXAM") && lesson.resultsPublishedAt() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RESULTS_NOT_PUBLISHED",
                    "Результаты зачёта появятся после проверки преподавателем");
        }
    }

    public EventPage events(UUID sessionId, long afterSequence, CurrentUser actor) {
        requireAccessibleRow(sessionId, actor);
        return new EventPage(events.after(sessionId, afterSequence));
    }

    public ReferenceBundle references() {
        return new ReferenceBundle(ReferenceDataService.REFERENCE_VERSION, "sha256:classifier-046-24",
                references.allServices(),
                List.of(
                        new DictionaryItem("reason.wrong_recipient", "WRONG_RECIPIENT", "Карточка направлена ошибочно"),
                        new DictionaryItem("reason.not_competence", "NOT_COMPETENCE", "Не в компетенции службы"),
                        new DictionaryItem("reason.duplicate", "DUPLICATE", "Дубль карточки"),
                        new DictionaryItem("reason.no_resources", "NO_RESOURCES", "Нет доступных сил и средств")));
    }

    // ================================================================= teacher helpers

    public MonitorRow monitorRow(SessionRow row, String traineeName) {
        SessionState state = sessions.get(row.id());
        Double finalTotal = assessmentRepo.findBySession(row.id()).map(AssessmentRow::finalTotal).orElse(null);
        if (state == null) {
            return new MonitorRow(row.id(), row.traineeId(), traineeName, row.workstationNumber(), row.state(),
                    null, false, false, row.startedAt(), row.completedAt(), 0, 0, finalTotal);
        }
        int total, done;
        String current;
        boolean accOverdue = false, procOverdue = false;
        if (state.mode.equals("CARD_ACTIONS")) {
            total = state.cards.size() + state.pending.size();
            done = (int) state.cards.values().stream().filter(c -> TERMINAL_CARD.contains(c.status)).count();
            MutableCard latest = state.cards.values().stream().max(Comparator.comparing(c -> c.receivedAt)).orElse(null);
            current = latest == null ? null : latest.status;
            accOverdue = state.cards.values().stream().anyMatch(c -> c.acceptanceOverdue && !TERMINAL_CARD.contains(c.status));
            procOverdue = state.cards.values().stream().anyMatch(c -> c.processingOverdue && !TERMINAL_CARD.contains(c.status));
        } else {
            total = state.drafts.size() + state.pending.size() + state.callQueue.size() + (state.ringing == null ? 0 : 1);
            done = (int) state.drafts.values().stream().filter(d -> "SAVED".equals(d.state())).count();
            CardDraft latest = state.drafts.values().stream().max(Comparator.comparing(CardDraft::startedAt)).orElse(null);
            current = latest == null ? null : latest.state();
            procOverdue = latest != null && !"SAVED".equals(latest.state()) && latest.deadlineAt() != null
                    && Instant.now().isAfter(latest.deadlineAt());
        }
        return new MonitorRow(row.id(), row.traineeId(), traineeName, row.workstationNumber(), state.state,
                current, accOverdue, procOverdue, state.startedAt, state.completedAt, done, total, finalTotal);
    }

    public SessionDetail detail(UUID sessionId, CurrentUser actor) {
        SessionRow row = requireAccessibleRow(sessionId, actor);
        Lesson lesson = lessonRepo.findLesson(row.lessonId()).orElseThrow(() -> notFound("Занятие не найдено"));
        AppUser trainee = userRepo.findById(row.traineeId()).orElse(null);
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            Optional<TrainingSnapshot> snapshot = stateStore.load(sessionId);
            state = snapshot.map(s -> restore(s, lesson)).orElse(null);
        }
        List<IncidentCard> cards = new ArrayList<>();
        List<CardDraft> drafts = new ArrayList<>();
        if (state != null) {
            for (MutableCard card : state.cards.values()) cards.add(toView(state, card));
            drafts.addAll(state.drafts.values());
        }
        Assessment assessment = assessmentRepo.findBySession(sessionId).map(AssessmentRepository::finalOf).orElse(null);
        SessionSummary summary = new SessionSummary(row.id(), row.lessonId(), lesson.title(), lesson.kind(),
                lesson.mode(), row.state(), row.startedAt(), row.completedAt());
        return new SessionDetail(summary, trainee == null ? null : UserService.toUser(trainee), cards, drafts, assessment);
    }

    // ================================================================= CardFill support

    SessionState state(UUID sessionId, CurrentUser actor) {
        return requireAccessible(sessionId, actor);
    }

    SessionState sessionOfDraft(UUID draftId, CurrentUser actor) {
        UUID sessionId = draftIndex.get(draftId);
        if (sessionId == null) throw notFound("Черновик карточки не найден");
        return requireAccessible(sessionId, actor);
    }

    void registerDraft(SessionState state, CardDraft draft) {
        state.drafts.put(draft.id(), draft);
        draftIndex.put(draft.id(), state.id);
    }

    // ---------------------------------------------------------------- поток вызовов оператора 112

    /** Звонящий вызов принят: возвращает его и снимает со звонка; null — если сейчас никто не звонит. */
    IncomingCallState answerCall(SessionState state) {
        IncomingCallState call = state.ringing;
        if (call == null) return null;
        state.ringing = null;
        events.publish(state.id, "call.answered", call.id().toString(), Map.of("callId", call.id().toString()));
        return call;
    }

    /** Очередная вводная становится вызовом в очереди АСО. */
    private void arriveNextCall(SessionState state, Instant now) {
        String scenarioId = state.pending.pollFirst();
        if (scenarioId == null) return;
        Scenario scenario = scenarios.require(scenarioId);
        ScenarioCaller caller = scenario.caller();
        state.callQueue.add(new IncomingCallState(UUID.randomUUID(), scenarioId,
                caller == null ? null : formatPhone(caller.phone()), caller == null ? null : caller.fullName(),
                now, null, 0));
    }

    /** Оператор свободен и никто не звонит — первый дозвонившийся вызов из очереди начинает звонить. */
    void presentNextCall(SessionState state, Instant now) {
        if (state.ringing != null || state.callQueue.isEmpty()) return;
        if (state.drafts.values().stream().anyMatch(d -> "DRAFT".equals(d.state()))) return;
        IncomingCallState next = null;
        for (IncomingCallState call : state.callQueue) {
            if (!call.arrivedAt().isAfter(now)) { next = call; break; }
        }
        if (next == null) return;
        state.callQueue.remove(next);
        state.ringing = new IncomingCallState(next.id(), next.scenarioId(), next.phone(), next.callerName(),
                next.arrivedAt(), now, next.missedCount());
        events.publish(state.id, "call.incoming", next.id().toString(),
                Map.of("callId", next.id().toString(), "missedCount", next.missedCount()));
    }

    /** Вызов звонил дольше норматива: заявитель перезвонит позже, после MAX_MISSES — не дозвонился. */
    private void missRingingCall(SessionState state) {
        IncomingCallState call = state.ringing;
        if (call == null) return;
        state.ringing = null;
        state.missedCalls++;
        int misses = call.missedCount() + 1;
        if (misses >= MAX_MISSES) {
            state.lost.add(call.scenarioId());
            events.publish(state.id, "call.lost", call.id().toString(), Map.of("callId", call.id().toString()));
        } else {
            // заявитель перезвонит через REDIAL_SECONDS — до этого вызов в очереди не предъявляется
            state.callQueue.addLast(new IncomingCallState(call.id(), call.scenarioId(), call.phone(),
                    call.callerName(), Instant.now().plusSeconds(REDIAL_SECONDS), null, misses));
            events.publish(state.id, "call.missed", call.id().toString(),
                    Map.of("callId", call.id().toString(), "missedCount", misses));
        }
    }

    /** Все вводные отработаны: очередь пуста, никто не звонит, все черновики сохранены. */
    boolean fillFlowFinished(SessionState state) {
        return state.pending.isEmpty() && state.callQueue.isEmpty() && state.ringing == null
                && state.drafts.values().stream().allMatch(d -> "SAVED".equals(d.state()));
    }

    /** Журнал оператора 112: свои сохранённые карточки и фоновые карточки смены, новые сверху. */
    public JournalPage journal(UUID sessionId, CurrentUser actor) {
        SessionState state = requireAccessible(sessionId, actor);
        List<JournalRow> rows = new ArrayList<>(state.background);
        synchronized (state) {
            for (CardDraft d : state.drafts.values()) {
                if (!"SAVED".equals(d.state())) continue;
                rows.add(new JournalRow(d.id(), "OWN", d.number(), d.savedAt() == null ? d.startedAt() : d.savedAt(),
                        state.workstationNumber, typeLabels(d.incidentTypeIds()), ScenarioService.describe(d.address()),
                        d.description(), d.caller() == null ? null : d.caller().fullName(),
                        d.services().stream().map(DraftService::label).toList(), "Сохранена"));
            }
        }
        rows.sort(Comparator.comparing(JournalRow::receivedAt).reversed());
        return new JournalPage(rows);
    }

    private String typeLabels(List<String> ids) {
        List<String> labels = new ArrayList<>();
        for (String id : ids) {
            ReferenceDataService.IncidentType type = references.incidentType(id);
            labels.add(type == null ? id : type.label());
        }
        return String.join(", ", labels);
    }

    /** Фоновые карточки смены: чужие сохранённые карточки по сценариям не из занятия (подтверждённые — первыми). */
    private List<JournalRow> backgroundCards(SessionState state, List<Scenario> lessonScenarios) {
        int count = state.intensity.backgroundCards;
        if (count == 0) return List.of();
        Set<String> exclude = new HashSet<>();
        for (Scenario s : lessonScenarios) exclude.add(s.id());
        List<ScenarioListItem> pool = new ArrayList<>(scenarios.list(null, null, null).stream()
                .filter(s -> !exclude.contains(s.id()) && !"TRAINEE_MADE".equals(s.source())).toList());
        Collections.shuffle(pool, ThreadLocalRandom.current());
        // подтверждённые эталоны предпочтительнее, но при их нехватке подходят любые билеты
        pool.sort(Comparator.comparing((ScenarioListItem s) -> !s.referenceConfirmed()));
        List<JournalRow> rows = new ArrayList<>();
        for (ScenarioListItem item : pool.subList(0, Math.min(count, pool.size()))) {
            Scenario scenario = scenarios.require(item.id());
            UUID id = UUID.randomUUID();
            Instant receivedAt = state.startedAt.minusSeconds(60L * ThreadLocalRandom.current().nextInt(5, 181));
            String arm;
            do { arm = String.valueOf(ThreadLocalRandom.current().nextInt(1, 21)); } while (arm.equals(state.workstationNumber));
            String address = ScenarioService.describe(scenario.expectedAddress());
            rows.add(new JournalRow(id, "BACKGROUND",
                    "112-2026-" + String.format("%06d", (int) (Math.abs(id.getLeastSignificantBits()) % 1_000_000)),
                    receivedAt, arm, typeLabels(scenario.expectedIncidentTypes()),
                    address.isEmpty() ? scenario.rawAddress() : address,
                    scenario.callerText(), scenario.caller() == null ? null : scenario.caller().fullName(),
                    scenario.expectedServices().stream().map(c -> references.service(c).label()).toList(), "Сохранена"));
        }
        return rows;
    }

    void publishDraft(SessionState state, CardDraft draft, String type) {
        events.publish(state.id, type, draft.id().toString(),
                Map.of("draftId", draft.id().toString(), "state", draft.state()));
    }

    void persist(SessionState state) {
        synchronized (state) {
            stateStore.save(state.id, new TrainingSnapshot(state.id, state.lessonId, state.traineeId, state.mode,
                    state.lessonKind, state.workstationNumber, state.state, state.startedAt, state.completedAt,
                    List.copyOf(state.pending), cardStates(state), callStates(state), List.copyOf(state.drafts.values()),
                    state.ownServiceCode, state.intensity.name(), state.nextArrivalAt, List.copyOf(state.callQueue),
                    state.ringing, List.copyOf(state.lost), state.missedCalls, List.copyOf(state.background)));
        }
    }

    // ================================================================= scheduled

    @Scheduled(fixedRate = 1000)
    void detectOverdue() {
        Instant now = Instant.now();
        for (SessionState state : sessions.values()) {
            if (!state.state.equals("ACTIVE")) continue;
            boolean changed = tickFlow(state, now);
            for (MutableCard card : state.cards.values()) {
                synchronized (card) {
                    if (!card.acceptanceOverdue
                            && List.of("RECEIVED", "RECEIVED_BY_SERVICE").contains(card.status)
                            && now.isAfter(card.acceptanceDeadlineAt)) {
                        card.acceptanceOverdue = true;
                        publishCard(state, card, "card.acceptance_overdue");
                        changed = true;
                    }
                    if (!card.processingOverdue && card.processingDeadlineAt != null
                            && !TERMINAL_CARD.contains(card.status)
                            && now.isAfter(card.processingDeadlineAt)) {
                        card.processingOverdue = true;
                        publishCard(state, card, "card.processing_overdue");
                        changed = true;
                    }
                }
            }
            if (changed) persist(state);
        }
    }

    /**
     * Поток по интенсивности: приход очередной вводной (карточка в журнал ДДС или вызов в очередь 112),
     * звонок следующего вызова свободному оператору, пропуск вызова, который звонил дольше норматива.
     */
    private boolean tickFlow(SessionState state, Instant now) {
        synchronized (state) {
            boolean changed = false;
            if (state.nextArrivalAt != null && !now.isBefore(state.nextArrivalAt)) {
                if (!state.pending.isEmpty()) {
                    if (state.mode.equals("CARD_ACTIONS")) deliverNextCard(state);
                    else arriveNextCall(state, now);
                    changed = true;
                }
                state.nextArrivalAt = state.pending.isEmpty() ? null : state.intensity.nextArrival(now);
            }
            if (state.mode.equals("CARD_FILL")) {
                if (state.ringing != null && state.ringing.ringingSince() != null
                        && now.isAfter(state.ringing.ringingSince().plusSeconds(RING_SECONDS))) {
                    missRingingCall(state);
                    changed = true;
                }
                IncomingCallState before = state.ringing;
                presentNextCall(state, now);
                changed |= before != state.ringing;
            }
            return changed;
        }
    }

    /** Для тестов: очередная вводная (и перезвон пропущенного вызова) приходит немедленно. */
    public void forceArrival(UUID sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;
        synchronized (state) {
            Instant past = Instant.now().minusSeconds(1);
            state.nextArrivalAt = past;
            List<IncomingCallState> queued = new ArrayList<>(state.callQueue);
            state.callQueue.clear();
            for (IncomingCallState c : queued) {
                state.callQueue.add(new IncomingCallState(c.id(), c.scenarioId(), c.phone(), c.callerName(), past, null, c.missedCount()));
            }
        }
        detectOverdue();
    }

    /** Для тестов: звонящий вызов считается пропущенным сейчас. */
    public void forceRingTimeout(UUID sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null || state.ringing == null) return;
        synchronized (state) {
            IncomingCallState r = state.ringing;
            state.ringing = new IncomingCallState(r.id(), r.scenarioId(), r.phone(), r.callerName(), r.arrivedAt(),
                    Instant.now().minusSeconds(RING_SECONDS + 1), r.missedCount());
        }
        detectOverdue();
    }

    /** Точка отсчёта 30 секунд — появление карточки в журнале (решение №12). Для тестов. */
    public void forceAcceptanceDeadline(UUID cardId, Instant deadline) {
        UUID sessionId = cardIndex.get(cardId);
        if (sessionId == null) return;
        MutableCard card = sessions.get(sessionId).cards.get(cardId);
        synchronized (card) {
            card.acceptanceDeadlineAt = deadline;
        }
        detectOverdue();
    }

    // ================================================================= internals

    private void deliverNextCard(SessionState state) {
        String scenarioId = state.pending.pollFirst();
        if (scenarioId == null) return;
        Scenario scenario = scenarios.require(scenarioId);
        MutableCard card = cardFrom(scenario, state);
        state.cards.put(card.id, card);
        cardIndex.put(card.id, state.id);
        publishCard(state, card, "card.created");
    }

    private void afterCardChange(SessionState state, MutableCard card) {
        // Режим «по одной»: следующая карточка приходит, когда текущая доведена до конца.
        // При заданной интенсивности карточки приходят по таймеру независимо от текущей (tickFlow).
        if (state.intensity == Intensity.SEQUENTIAL && TERMINAL_CARD.contains(card.status) && !state.pending.isEmpty()
                && state.cards.values().stream().allMatch(c -> TERMINAL_CARD.contains(c.status))) {
            deliverNextCard(state);
        }
    }

    private MutableCard cardFrom(Scenario scenario, SessionState state) {
        Instant receivedAt = Instant.now();
        MutableCard card = new MutableCard();
        card.id = UUID.randomUUID();
        card.sessionId = state.id;
        card.scenarioId = scenario.id();
        card.scenarioTitle = scenario.title();
        card.number = "112-2026-" + String.format("%06d", (int) (Math.abs(card.id.getLeastSignificantBits()) % 1_000_000));
        card.receivedAt = receivedAt;
        card.source = "SYSTEM_112";
        card.senderLabel = "Оператор 112";
        card.status = "RECEIVED";
        card.acceptanceDeadlineAt = receivedAt.plusSeconds(settings.integer(SettingsService.ACCEPTANCE_SECONDS, 30));
        ScenarioCaller sc = scenario.caller();
        card.caller = new Caller(sc == null ? null : sc.fullName(), sc == null ? null : formatPhone(sc.phone()), "заявитель");
        FormalAddress ea = scenario.expectedAddress();
        card.address = new IncidentAddress(scenario.rawAddress(),
                ea == null ? "Москва" : (ea.locality() != null ? ea.locality() : ea.region()),
                ea == null ? null : ea.district(), ea == null ? null : ea.street(), ea == null ? null : ea.house(),
                ea == null ? null : ea.descriptive(), null, null);
        card.description = scenario.callerText();
        String typeId = scenario.expectedIncidentTypes().isEmpty() ? null : scenario.expectedIncidentTypes().get(0);
        ReferenceDataService.IncidentType type = typeId == null ? null : references.incidentType(typeId);
        card.incidentType = type == null
                ? new DictionaryItem("incident.other", "OTHER", "Происшествие")
                : new DictionaryItem("incident." + type.id(), type.id().toUpperCase().replace('.', '_'), type.label());
        card.features = List.of();
        String own = state.ownServiceCode;
        List<String> serviceCodes = scenario.expectedServices().isEmpty() ? List.of(own) : scenario.expectedServices();
        // Карточка пришла в эту ДДС — плитка своей службы есть всегда (первой), даже если тип непрофильный:
        // иначе диспетчеру нечем проставить «Не принята» с основанием (замечание 7).
        List<String> assigned = new ArrayList<>();
        assigned.add(own);
        List<String> territorial = references.territorialServices(ea);
        for (String code : serviceCodes) {
            // колонки «Терр. ОИВ» на карточке показываются как конкретные ДДС района и округа
            if (("OIV".equals(code) || "OIV_TINAO".equals(code)) && !territorial.isEmpty()) {
                for (String t : territorial) if (!assigned.contains(t)) assigned.add(t);
            } else if (!assigned.contains(code)) {
                assigned.add(code);
            }
        }
        card.assignedServices = assigned.stream().map(references::service).toList();
        card.expectedServices = List.copyOf(serviceCodes);
        card.expectedDecision = scenario.expectedDecision() != null ? scenario.expectedDecision()
                : (serviceCodes.contains(own) ? "ACCEPT" : "DECLINE");
        card.requirements = new ScenarioRequirements(scenario.outboundCallRequired(),
                List.of(SHIFT_SUPERVISOR.id()), true);
        card.callTargets = List.of(SHIFT_SUPERVISOR);
        card.timeline.add(new CardTimelineEntry(UUID.randomUUID(), "DELIVERED", "RECEIVED",
                null, null, receivedAt, "Система", "SYSTEM"));
        return card;
    }

    private static String formatPhone(String digits) {
        if (digits == null || digits.length() != 11) return digits;
        return "+" + digits.charAt(0) + " " + digits.substring(1, 4) + " " + digits.substring(4, 7)
                + "-" + digits.substring(7, 9) + "-" + digits.substring(9);
    }

    private void scheduleCallChain(SessionState state, UUID callId, String from) {
        int ringing = settings.integer(SettingsService.RINGING_MS, 300);
        int connect = settings.integer(SettingsService.CONNECT_MS, 700);
        int ack = settings.integer(SettingsService.ACKNOWLEDGE_MS, 1200);
        switch (from) {
            case "DIALING" -> {
                scheduleCallState(state, callId, "RINGING", ringing);
                scheduleCallState(state, callId, "CONNECTED", connect);
                scheduleCallState(state, callId, "ACKNOWLEDGED", ack);
            }
            case "RINGING" -> {
                scheduleCallState(state, callId, "CONNECTED", Math.max(100, connect - ringing));
                scheduleCallState(state, callId, "ACKNOWLEDGED", Math.max(200, ack - ringing));
            }
            case "CONNECTED" -> scheduleCallState(state, callId, "ACKNOWLEDGED", Math.max(100, ack - connect));
            default -> { }
        }
    }

    private void scheduleCallState(SessionState state, UUID callId, String next, long delayMs) {
        callScheduler.schedule(() -> {
            MutableCall call = state.calls.get(callId);
            if (call == null) return;
            synchronized (call) {
                if (isTerminalCall(call.state)) return;
                call.state = next;
                if (next.equals("CONNECTED")) call.connectedAt = Instant.now();
                publishCall(state, call);
            }
            persist(state);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void resumeActiveCalls() {
        for (SessionState state : sessions.values()) {
            for (MutableCall call : state.calls.values()) {
                callIndex.put(call.id, state.id);
                if (!isTerminalCall(call.state) && !call.state.equals("ACKNOWLEDGED")) {
                    scheduleCallChain(state, call.id, call.state);
                }
            }
        }
    }

    private void publishCard(SessionState state, MutableCard card, String type) {
        events.publish(state.id, type, card.id.toString(),
                Map.of("cardId", card.id.toString(), "status", card.status));
    }

    private void publishCall(SessionState state, MutableCall call) {
        events.publish(state.id, "outbound_call.updated", call.id.toString(),
                Map.of("callId", call.id.toString(), "cardId", call.cardId.toString(), "state", call.state));
    }

    private void addTimeline(MutableCard card, String action, String reasonCode, String comment, CurrentUser actor) {
        // «Добавлена» и «Получена службой» — технические статусы, их проставляет система.
        boolean technical = action.equals("DELIVERED") || action.equals("RECEIVE") || actor == null;
        String label;
        String role;
        if (technical) {
            label = "Система";
            role = "SYSTEM";
        } else if (actor.is(Role.TEACHER)) {
            // «Делай, как я»: действия преподавателя видны в ленте и не попадают в оценку обучающегося.
            label = "Преподаватель";
            role = "TEACHER";
        } else {
            label = "Обучающийся";
            role = "TRAINEE";
        }
        card.timeline.add(new CardTimelineEntry(UUID.randomUUID(), action, card.status,
                reasonCode, comment, Instant.now(), label, role));
    }

    private boolean requiredCallCompleted(SessionState state, MutableCard card) {
        return card.callIds.stream().map(state.calls::get).filter(call -> call != null)
                .anyMatch(call -> card.requirements.requiredTargetIds().contains(call.target.id())
                        && List.of("ACKNOWLEDGED", "ENDED").contains(call.state));
    }

    private CardListItem toListItem(MutableCard card) {
        return new CardListItem(card.id, card.number, card.receivedAt,
                card.incidentType.label(), card.address.raw(), card.description,
                card.senderLabel, card.status, allowedActions(card), sla(card));
    }

    private IncidentCard toView(SessionState state, MutableCard card) {
        synchronized (card) {
            return new IncidentCard(card.id, card.sessionId, card.number, card.receivedAt,
                    card.source, card.senderLabel, card.status, allowedActions(card), sla(card),
                    card.caller, card.address, card.description, card.incidentType, card.features,
                    card.assignedServices, card.requirements, card.callTargets,
                    List.copyOf(card.timeline), card.callIds.stream().map(state.calls::get)
                    .filter(call -> call != null).map(this::toView).toList(),
                    hints(state, card), state.ownServiceCode, card.scenarioTitle);
        }
    }

    /** Подсказки в режиме тренировки (решение №10): что сейчас было бы ошибкой. */
    private List<Hint> hints(SessionState state, MutableCard card) {
        if (!"TRAINING".equals(state.lessonKind)) return List.of();
        List<Hint> hints = new ArrayList<>();
        boolean fresh = card.status.equals("RECEIVED") || card.status.equals("RECEIVED_BY_SERVICE");
        if (fresh && "ACCEPT".equals(card.expectedDecision)) {
            hints.add(new Hint("acceptance", "Происшествие профильное для службы " + state.ownServiceCode + " — карточку нужно принять"));
        }
        if (fresh && "DECLINE".equals(card.expectedDecision)) {
            hints.add(new Hint("acceptance", "Служба " + state.ownServiceCode + " не оповещается по этому типу — уместен статус «Не принята» с основанием"));
        }
        if (card.status.equals("NOT_ACCEPTED") && "ACCEPT".equals(card.expectedDecision)) {
            hints.add(new Hint("acceptance", "Ошибочный отказ исправляется статусом «Принята»"));
        }
        if (card.status.equals("WORK_IN_PROGRESS") && card.requirements.outboundCallRequired()
                && !requiredCallCompleted(state, card)) {
            hints.add(new Hint("call", "Перед завершением работ доложите руководителю по короткому номеру 1102"));
        }
        if (fresh && card.acceptanceOverdue) {
            hints.add(new Hint("timer", "Норматив 30 секунд на принятие уже превышен"));
        }
        return hints;
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

    private TrainingSession toView(SessionState state) {
        IncomingCallState ringing = state.ringing;
        return new TrainingSession(state.id, state.mode, state.state, state.title, state.intensity.difficulty,
                ReferenceDataService.REFERENCE_VERSION, Instant.now(), state.startedAt, state.completedAt,
                state.cards.values().stream().sorted(Comparator.comparing(c -> c.receivedAt)).map(c -> c.id).toList(),
                state.drafts.values().stream().sorted(Comparator.comparing(CardDraft::startedAt)).map(CardDraft::id).toList(),
                state.lessonId, state.lessonKind, state.pending.size(), state.ownServiceCode, state.intensity.name(),
                ringing == null ? null : new IncomingCall(ringing.id(), ringing.phone(), ringing.callerName(),
                        ringing.ringingSince(), ringing.missedCount()),
                state.callQueue.size());
    }

    // ---------------------------------------------------------------- access

    private SessionState requireAccessible(UUID sessionId, CurrentUser actor) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            // завершённая сессия: поднимаем снимок, чтобы преподаватель мог смотреть карточки
            SessionRow row = requireAccessibleRow(sessionId, actor);
            Lesson lesson = lessonRepo.findLesson(row.lessonId()).orElseThrow(() -> notFound("Занятие не найдено"));
            state = stateStore.load(sessionId).map(s -> restore(s, lesson)).orElseThrow(() -> notFound("Занятие не найдено"));
            sessions.put(state.id, state);
            state.cards.keySet().forEach(id -> cardIndex.put(id, sessionId));
            state.calls.keySet().forEach(id -> callIndex.put(id, sessionId));
            state.drafts.keySet().forEach(id -> draftIndex.put(id, sessionId));
            return state;
        }
        checkAccess(state.traineeId, state.teacherId, actor);
        return state;
    }

    private SessionRow requireAccessibleRow(UUID sessionId, CurrentUser actor) {
        SessionRow row = sessionRepo.findById(sessionId).orElseThrow(() -> notFound("Занятие не найдено"));
        Lesson lesson = lessonRepo.findLesson(row.lessonId()).orElseThrow(() -> notFound("Занятие не найдено"));
        checkAccess(row.traineeId(), lesson.teacherId(), actor);
        return row;
    }

    private void checkAccess(UUID traineeId, UUID teacherId, CurrentUser actor) {
        boolean allowed = (actor.is(Role.TRAINEE) && traineeId.equals(actor.id()))
                || (actor.is(Role.TEACHER) && teacherId.equals(actor.id()));
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Нет доступа к этому занятию");
        }
    }

    private SessionState sessionOfCard(UUID cardId, CurrentUser actor) {
        UUID sessionId = cardIndex.get(cardId);
        if (sessionId == null) throw notFound("Карточка не найдена");
        return requireAccessible(sessionId, actor);
    }

    private SessionState sessionOfCall(UUID callId, CurrentUser actor) {
        UUID sessionId = callIndex.get(callId);
        if (sessionId == null) throw notFound("Звонок не найден");
        return requireAccessible(sessionId, actor);
    }

    private void requireActive(SessionState state) {
        if (!state.state.equals("ACTIVE")) {
            throw new ApiException(HttpStatus.CONFLICT, "SESSION_NOT_ACTIVE", "Занятие не активно");
        }
    }

    private void requireState(MutableCard card, String expected) {
        if (!card.status.equals(expected)) throw invalidTransition(card.status);
    }

    private void requireOneOf(MutableCard card, String... allowed) {
        for (String s : allowed) if (card.status.equals(s)) return;
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

    public static ApiException validation(String path, String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                "Проверьте переданные поля", List.of(new FieldError(path, code, message)), Map.of());
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isTerminalCall(String state) {
        return List.of("ENDED", "NO_ANSWER", "FAILED", "CANCELLED").contains(state);
    }

    @SuppressWarnings("unchecked")
    <T> T idempotent(SessionState state, String operation, String key, String signature, Supplier<T> action) {
        try {
            UUID.fromString(key);
        } catch (Exception ex) {
            throw validation("Idempotency-Key", "INVALID", "Ожидается UUID");
        }
        String storageKey = operation + ":" + key;
        IdempotentResult existing = idempotency.get(storageKey);
        if (existing != null) {
            if (!existing.signature.equals(signature)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Ключ уже использован с другим запросом");
            }
            return (T) existing.value;
        }
        synchronized (idempotency) {
            existing = idempotency.get(storageKey);
            if (existing != null) return (T) existing.value;
            T value = action.get();
            idempotency.put(storageKey, new IdempotentResult(signature, value));
            persist(state);
            return value;
        }
    }

    private List<CardState> cardStates(SessionState state) {
        return state.cards.values().stream().map(card -> {
            synchronized (card) {
                return new CardState(card.id, card.sessionId, card.scenarioId, card.number, card.receivedAt,
                        card.source, card.senderLabel, card.status, card.acceptanceDeadlineAt,
                        card.processingDeadlineAt, card.acceptedAt, card.acceptanceOverdue,
                        card.processingOverdue, card.caller, card.address, card.description,
                        card.incidentType, card.features, card.assignedServices, card.requirements,
                        card.callTargets, List.copyOf(card.timeline), List.copyOf(card.callIds),
                        card.expectedServices, card.expectedDecision, card.scenarioTitle);
            }
        }).toList();
    }

    private List<CallState> callStates(SessionState state) {
        return state.calls.values().stream().map(call -> {
            synchronized (call) {
                return new CallState(call.id, call.cardId, call.target, call.state,
                        call.startedAt, call.connectedAt, call.endedAt);
            }
        }).toList();
    }

    private SessionState restore(TrainingSnapshot snapshot, Lesson lesson) {
        SessionState state = new SessionState(snapshot.sessionId(), snapshot.lessonId(), snapshot.traineeId(),
                lesson.teacherId(), snapshot.mode(), snapshot.lessonKind(), snapshot.workstationNumber(), lesson.title());
        state.state = snapshot.sessionState();
        state.startedAt = snapshot.sessionStartedAt();
        state.completedAt = snapshot.sessionCompletedAt();
        if (snapshot.pendingScenarioIds() != null) state.pending.addAll(snapshot.pendingScenarioIds());
        state.ownServiceCode = snapshot.ownServiceCode() == null ? OWN_SERVICE_CODE : snapshot.ownServiceCode();
        state.intensity = Intensity.of(snapshot.intensity());
        state.nextArrivalAt = snapshot.nextArrivalAt();
        if (snapshot.callQueue() != null) state.callQueue.addAll(snapshot.callQueue());
        state.ringing = snapshot.ringing();
        if (snapshot.lost() != null) state.lost.addAll(snapshot.lost());
        state.missedCalls = snapshot.missedCalls() == null ? 0 : snapshot.missedCalls();
        if (snapshot.background() != null) state.background.addAll(snapshot.background());
        for (CardState cs : snapshot.cards()) {
            MutableCard card = new MutableCard();
            card.id = cs.id(); card.sessionId = cs.sessionId(); card.scenarioId = cs.scenarioId();
            card.number = cs.number(); card.receivedAt = cs.receivedAt(); card.source = cs.source();
            card.senderLabel = cs.senderLabel(); card.status = cs.status();
            card.acceptanceDeadlineAt = cs.acceptanceDeadlineAt(); card.processingDeadlineAt = cs.processingDeadlineAt();
            card.acceptedAt = cs.acceptedAt(); card.acceptanceOverdue = cs.acceptanceOverdue();
            card.processingOverdue = cs.processingOverdue(); card.caller = cs.caller(); card.address = cs.address();
            card.description = cs.description(); card.incidentType = cs.incidentType();
            card.features = cs.features() == null ? List.of() : List.copyOf(cs.features());
            card.assignedServices = cs.assignedServices() == null ? List.of() : List.copyOf(cs.assignedServices());
            card.requirements = cs.requirements(); card.callTargets = List.copyOf(cs.callTargets());
            card.timeline = new ArrayList<>(cs.timeline()); card.callIds = new ArrayList<>(cs.callIds());
            card.expectedServices = cs.expectedServices() == null ? List.of() : cs.expectedServices();
            card.expectedDecision = cs.expectedDecision();
            card.scenarioTitle = cs.scenarioTitle();
            state.cards.put(card.id, card);
            cardIndex.put(card.id, state.id);
        }
        for (CallState cs : snapshot.calls()) {
            MutableCall call = new MutableCall();
            call.id = cs.id(); call.cardId = cs.cardId(); call.target = cs.target(); call.state = cs.state();
            call.startedAt = cs.startedAt(); call.connectedAt = cs.connectedAt(); call.endedAt = cs.endedAt();
            state.calls.put(call.id, call);
            callIndex.put(call.id, state.id);
        }
        if (snapshot.drafts() != null) {
            for (CardDraft draft : snapshot.drafts()) {
                state.drafts.put(draft.id(), draft);
                draftIndex.put(draft.id(), state.id);
            }
        }
        return state;
    }

    // ================================================================= state classes

    static final class SessionState {
        final UUID id;
        final UUID lessonId;
        final UUID traineeId;
        final UUID teacherId;
        final String mode;
        final String lessonKind;
        final String workstationNumber;
        final String title;
        volatile String state = "ACTIVE";
        volatile Instant startedAt;
        volatile Instant completedAt;
        final Deque<String> pending = new ArrayDeque<>();
        final Map<UUID, MutableCard> cards = new ConcurrentHashMap<>();
        final Map<UUID, MutableCall> calls = new ConcurrentHashMap<>();
        final Map<UUID, CardDraft> drafts = new LinkedHashMap<>();
        volatile String ownServiceCode = OWN_SERVICE_CODE;
        volatile Intensity intensity = Intensity.SEQUENTIAL;
        /** Когда придёт следующая вводная; null — очередь пуста или режим «по одной». */
        volatile Instant nextArrivalAt;
        /** Вызовы оператору 112, ожидающие в очереди АСО. */
        final Deque<IncomingCallState> callQueue = new ArrayDeque<>();
        /** Вызов, который сейчас звонит оператору 112. */
        volatile IncomingCallState ringing;
        /** Сценарии, до которых заявитель так и не дозвонился. */
        final List<String> lost = new ArrayList<>();
        int missedCalls;
        /** Фоновые карточки смены в журнале оператора 112. */
        final List<JournalRow> background = new ArrayList<>();

        SessionState(UUID id, UUID lessonId, UUID traineeId, UUID teacherId, String mode, String lessonKind,
                     String workstationNumber, String title) {
            this.id = id; this.lessonId = lessonId; this.traineeId = traineeId; this.teacherId = teacherId;
            this.mode = mode; this.lessonKind = lessonKind; this.workstationNumber = workstationNumber; this.title = title;
        }
    }

    static final class MutableCard {
        UUID id;
        UUID sessionId;
        String scenarioId;
        String scenarioTitle;
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
        List<String> expectedServices = List.of();
        String expectedDecision;
    }

    static final class MutableCall {
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
