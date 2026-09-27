package ru.lct.arm112.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.*;
import ru.lct.arm112.security.CurrentUser;
import ru.lct.arm112.persistence.TrainingStateStore.IncomingCallState;
import ru.lct.arm112.service.TrainingEngine.SessionState;
import ru.lct.arm112.service.assessment.CardFillAssessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Режим заполнения карточки (экран оператора 112). Черновик создаётся из очереди сценариев сессии,
 * поля автосохраняются, «сохранить» фиксирует время и делает карточку сценарием «сформировано обучающимся».
 */
@Service
public class CardFillService {
    private final TrainingEngine engine;
    private final ScenarioService scenarios;
    private final ReferenceDataService references;
    private final SettingsService settings;
    private final CardFillAssessor assessor;

    public CardFillService(TrainingEngine engine, ScenarioService scenarios, ReferenceDataService references,
                           SettingsService settings, CardFillAssessor assessor) {
        this.engine = engine;
        this.scenarios = scenarios;
        this.references = references;
        this.settings = settings;
        this.assessor = assessor;
    }

    public List<CardDraft> drafts(UUID sessionId, CurrentUser actor) {
        SessionState state = engine.state(sessionId, actor);
        synchronized (state) {
            return state.drafts.values().stream()
                    .sorted(Comparator.comparing(CardDraft::startedAt))
                    .map(d -> withHints(state, d)).toList();
        }
    }

    public CardDraft draft(UUID draftId, CurrentUser actor) {
        SessionState state = engine.sessionOfDraft(draftId, actor);
        synchronized (state) {
            return withHints(state, state.drafts.get(draftId));
        }
    }

    /**
     * Принятие входящего вызова: черновик по сценарию звонящего вызова, таймер 3 минуты.
     * Незакрытый черновик возвращается как есть; без звонящего вызова — 409.
     */
    public CardDraft start(UUID sessionId, CurrentUser actor) {
        SessionState state = engine.state(sessionId, actor);
        synchronized (state) {
            requireActive(state);
            if (!state.mode.equals("CARD_FILL")) {
                throw new ApiException(HttpStatus.CONFLICT, "WRONG_MODE", "Занятие идёт в режиме действий с карточкой");
            }
            CardDraft open = state.drafts.values().stream().filter(d -> "DRAFT".equals(d.state())).findFirst().orElse(null);
            if (open != null) return withHints(state, open);
            IncomingCallState call = engine.answerCall(state);
            if (call == null) {
                if (state.pending.isEmpty() && state.callQueue.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "NO_MORE_SCENARIOS", "Все вводные занятия отработаны");
                }
                throw new ApiException(HttpStatus.CONFLICT, "NO_INCOMING_CALL", "Входящего вызова сейчас нет — дождитесь звонка");
            }
            Scenario scenario = scenarios.require(call.scenarioId());
            Instant now = Instant.now();
            UUID id = UUID.randomUUID();
            ScenarioCaller caller = scenario.caller();
            CardDraft draft = new CardDraft(id, state.id, scenario.id(),
                    "112-2026-" + String.format("%06d", (int) (Math.abs(id.getLeastSignificantBits()) % 1_000_000)),
                    now, null, now.plusSeconds(settings.integer(SettingsService.PROCESSING_SECONDS, 180)), "DRAFT",
                    scenario.callerText(),
                    new DraftPhones(call.phone(), null, null),
                    new DraftCaller(caller == null ? null : caller.fullName(), "заявитель"),
                    new FormalAddress("Россия", null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                    new DraftFlags(false, null, false, false, false, false),
                    List.of(), List.of(), "", List.of(), List.of(), scenario.title(), scenario.rawAddress(), null);
            engine.registerDraft(state, draft);
            engine.publishDraft(state, draft, "draft.created");
            engine.persist(state);
            return withHints(state, draft);
        }
    }

    public CardDraft patch(UUID draftId, CardDraftPatch patch, CurrentUser actor) {
        SessionState state = engine.sessionOfDraft(draftId, actor);
        synchronized (state) {
            requireActive(state);
            CardDraft current = state.drafts.get(draftId);
            if (!"DRAFT".equals(current.state())) {
                throw new ApiException(HttpStatus.CONFLICT, "DRAFT_SAVED", "Карточка уже сохранена");
            }
            String topTypeId = patch.topTypeId() != null ? patch.topTypeId() : current.topTypeId();
            if (patch.topTypeId() != null && references.surveyTree(patch.topTypeId()) == null) {
                throw TrainingEngine.validation("topTypeId", "UNKNOWN", "Неизвестный тип происшествия: " + patch.topTypeId());
            }
            List<SurveyAnswer> answers = patch.surveyAnswers() != null ? patch.surveyAnswers() : current.surveyAnswers();
            List<String> types;
            if (patch.incidentTypeIds() != null) {
                types = patch.incidentTypeIds();
            } else if (topTypeId != null) {
                // тип ЕКП выводится из ответов опросной карты, как в ПОВ-112
                String resolved = references.resolveIncidentType(topTypeId, answerMap(answers));
                types = resolved == null ? current.incidentTypeIds() : List.of(resolved);
            } else {
                types = current.incidentTypeIds();
            }
            for (String typeId : types) {
                if (references.incidentType(typeId) == null) {
                    throw TrainingEngine.validation("incidentTypeIds", "UNKNOWN", "Неизвестный тип происшествия: " + typeId);
                }
            }
            FormalAddress address = patch.address() != null ? patch.address() : current.address();
            List<DraftService> services = recomputeServices(current.services(), types, patch.extraServiceCodes(), address);
            CardDraft updated = new CardDraft(current.id(), current.sessionId(), current.scenarioId(), current.number(),
                    current.startedAt(), null, current.deadlineAt(), "DRAFT", current.callerText(),
                    patch.phones() != null ? patch.phones() : current.phones(),
                    patch.caller() != null ? patch.caller() : current.caller(),
                    patch.address() != null ? patch.address() : current.address(),
                    patch.flags() != null ? patch.flags() : current.flags(),
                    List.copyOf(types), answers,
                    patch.description() != null ? patch.description() : current.description(),
                    services, List.of(), current.scenarioTitle(), current.callerAddress(), topTypeId);
            state.drafts.put(draftId, updated);
            engine.trackDraftChange(state, current, updated, changedFields(current, updated));
            engine.persist(state);
            return withHints(state, updated);
        }
    }

    /**
     * Какие поля карточки изменила эта правка.
     *
     * <p>Имя поля уходит в событие {@code draft.updated}: по нему восстанавливается
     * порядок заполнения — сначала адрес и тип или сразу описание.
     */
    private static List<String> changedFields(CardDraft before, CardDraft after) {
        List<String> changed = new ArrayList<>();
        if (!java.util.Objects.equals(before.address(), after.address())) changed.add("address");
        if (!before.incidentTypeIds().equals(after.incidentTypeIds())) changed.add("incidentTypeIds");
        if (!java.util.Objects.equals(before.description(), after.description())) changed.add("description");
        if (!java.util.Objects.equals(before.phones(), after.phones())) changed.add("phones");
        if (!java.util.Objects.equals(before.caller(), after.caller())) changed.add("caller");
        if (!java.util.Objects.equals(before.flags(), after.flags())) changed.add("flags");
        if (!before.surveyAnswers().equals(after.surveyAnswers())) changed.add("surveyAnswers");
        if (!before.services().equals(after.services())) changed.add("services");
        return changed;
    }

    public CardDraft save(UUID draftId, String idempotencyKey, CurrentUser actor) {
        SessionState state = engine.sessionOfDraft(draftId, actor);
        return engine.idempotent(state, "save-draft:" + draftId, idempotencyKey, "SAVE", CardDraft.class, () -> {
            synchronized (state) {
                requireActive(state);
                CardDraft current = state.drafts.get(draftId);
                if (!"DRAFT".equals(current.state())) return withHints(state, current);
                if (current.address() == null || isBlank(current.address().street()) && isBlank(current.address().descriptive())) {
                    throw TrainingEngine.validation("address", "REQUIRED", "Укажите адрес происшествия");
                }
                if (current.incidentTypeIds().isEmpty()) {
                    throw TrainingEngine.validation("incidentTypeIds", "REQUIRED", "Выберите тип происшествия");
                }
                CardDraft saved = new CardDraft(current.id(), current.sessionId(), current.scenarioId(), current.number(),
                        current.startedAt(), Instant.now(), current.deadlineAt(), "SAVED", current.callerText(),
                        current.phones(), current.caller(), current.address(), current.flags(), current.incidentTypeIds(),
                        current.surveyAnswers(), current.description(), current.services(), List.of(), current.scenarioTitle(),
                        current.callerAddress(), current.topTypeId());
                state.drafts.put(draftId, saved);
                // Сохранённая карточка становится сценарием для режима действий (ТЗ, сценарий 3).
                Scenario base = scenarios.require(saved.scenarioId());
                scenarios.saveTraineeMade(base, saved.description(), saved.address(), saved.incidentTypeIds(),
                        saved.services().stream().map(DraftService::code).toList(), actor.id());
                engine.publishDraft(state, saved, "draft.saved");
                // Последняя вводная сохранена — занятие завершается само, без лишней кнопки (решение №11).
                if (engine.fillFlowFinished(state)) {
                    engine.complete(state);
                } else {
                    // оператор освободился — следующая вводная приходит и дозванивается сразу
                    engine.afterDraftSaved(state, Instant.now());
                    engine.persist(state);
                }
                return saved;
            }
        });
    }

    /**
     * Службы подбираются автоматически по ЕКП для выбранных типов; добавленные вручную остаются,
     * удалить автоматическую нельзя — как в оригинале.
     */
    /** Ответы опросной карты в вид «вопрос → выбранные варианты» для правил вывода типа. */
    private static java.util.Map<String, List<String>> answerMap(List<SurveyAnswer> answers) {
        java.util.Map<String, List<String>> map = new java.util.LinkedHashMap<>();
        for (SurveyAnswer a : answers) {
            if (a.optionId() == null) continue;
            map.computeIfAbsent(a.questionId(), k -> new ArrayList<>()).add(a.optionId());
        }
        return map;
    }

    private List<DraftService> recomputeServices(List<DraftService> current, List<String> types, List<String> extra,
                                                 FormalAddress address) {
        Set<String> auto = new LinkedHashSet<>(references.servicesFor(types, address));
        Set<String> manual = new LinkedHashSet<>();
        for (DraftService s : current) if (!s.auto()) manual.add(s.code());
        if (extra != null) manual.addAll(extra);
        List<DraftService> result = new ArrayList<>();
        for (DictionaryItem service : references.allServices()) {
            if (auto.contains(service.code())) {
                result.add(new DraftService(service.code(), service.label(), true));
            } else if (manual.contains(service.code())) {
                result.add(new DraftService(service.code(), service.label(), false));
            }
        }
        return result;
    }

    private CardDraft withHints(SessionState state, CardDraft draft) {
        if (draft == null) throw TrainingEngine.notFound("Черновик карточки не найден");
        if (!"TRAINING".equals(state.lessonKind) || !"DRAFT".equals(draft.state())) return draft;
        Scenario scenario = scenarios.require(draft.scenarioId());
        return new CardDraft(draft.id(), draft.sessionId(), draft.scenarioId(), draft.number(), draft.startedAt(),
                draft.savedAt(), draft.deadlineAt(), draft.state(), draft.callerText(), draft.phones(), draft.caller(),
                draft.address(), draft.flags(), draft.incidentTypeIds(), draft.surveyAnswers(), draft.description(),
                draft.services(), assessor.hints(draft, scenario), draft.scenarioTitle(), draft.callerAddress(),
                draft.topTypeId());
    }

    private static void requireActive(SessionState state) {
        if (!state.state.equals("ACTIVE")) {
            throw new ApiException(HttpStatus.CONFLICT, "SESSION_NOT_ACTIVE", "Занятие не активно");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
