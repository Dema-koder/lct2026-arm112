package ru.lct.arm112.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.FormalAddress;
import ru.lct.arm112.api.ApiModels.GenerateRequest;
import ru.lct.arm112.api.ApiModels.Scenario;
import ru.lct.arm112.api.ApiModels.ScenarioCaller;
import ru.lct.arm112.api.ApiModels.ScenarioListItem;
import ru.lct.arm112.api.ApiModels.ScenarioUpsert;
import ru.lct.arm112.persistence.ScenarioRepository;
import ru.lct.arm112.service.assessment.LanguageChecker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Библиотека сценариев: сид из билетов, правки преподавателя, шаблонная генерация. */
@Service
public class ScenarioService {
    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final ScenarioRepository repository;
    private final ObjectMapper objectMapper;
    private final ReferenceDataService references;
    private final LanguageChecker language;
    private final AddressReferenceService addresses;
    private final ru.lct.arm112.service.analytics.IncidentTypeClassifier classifier;

    public ScenarioService(ScenarioRepository repository, ObjectMapper objectMapper, ReferenceDataService references,
                           LanguageChecker language, AddressReferenceService addresses,
                           ru.lct.arm112.service.analytics.IncidentTypeClassifier classifier) {
        this.addresses = addresses;
        this.classifier = classifier;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.references = references;
        this.language = language;
    }

    @PostConstruct
    void seed() {
        if (repository.count() > 0) {
            backfillTitles();
            learnPlaces();
            return;
        }
        int total = load("seed/scenarios.json") + load("seed/scenarios-generated.json");
        log.info("Загружено сценариев в библиотеку: {}", total);
        learnPlaces();
    }

    /**
     * Загрузка набора сценариев из ресурса.
     *
     * <p>Наборов два: билеты заказчика и банк, сгенерированный заранее вне контура.
     * Второй поставляется неподтверждённым — заказчик требовал, чтобы сгенерированное
     * всегда проходило через преподавателя (q-and-a.md §3). Отсутствие файла не ошибка:
     * банк необязателен, приложение работает и на одних билетах.
     */
    private int load(String resource) {
        ClassPathResource file = new ClassPathResource(resource);
        if (!file.exists()) return 0;
        try (InputStream stream = file.getInputStream()) {
            JsonNode root = objectMapper.readTree(stream);
            int count = 0;
            for (JsonNode node : root) {
                List<String> types = toList(node.get("expectedIncidentTypes"));
                FormalAddress expected = objectMapper.treeToValue(node.get("expectedAddress"), FormalAddress.class);
                String title = node.hasNonNull("title") ? node.get("title").asText()
                        : autoTitle(types, expected, node.get("callerText").asText());
                Scenario scenario = new Scenario(node.get("id").asText(), title, node.get("source").asText(),
                        node.get("category").asText(), node.get("difficulty").asInt(5),
                        node.get("callerText").asText(), objectMapper.treeToValue(node.get("caller"), ScenarioCaller.class),
                        node.get("rawAddress").asText(), expected,
                        types, toList(node.get("expectedServices")),
                        node.path("addressClarified").asBoolean(false), null, null, true,
                        false, null, null, Instant.now());
                repository.insert(scenario);
                count++;
            }
            return count;
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось загрузить " + resource, exception);
        }
    }

    /** Сценарии, засеянные до появления названий (V7): проставить название по умолчанию один раз. */
    private void backfillTitles() {
        int count = 0;
        for (Scenario scenario : repository.find(null, null, null, 5000)) {
            if (scenario.title() != null && !scenario.title().isBlank()) continue;
            String title = autoTitle(scenario.expectedIncidentTypes(), scenario.expectedAddress(), scenario.callerText());
            if ("TRAINEE_MADE".equals(scenario.source())) title = "Сформировано: " + title;
            if ("GENERATED".equals(scenario.source())) title = "Сгенерировано: " + title;
            repository.update(new Scenario(scenario.id(), title, scenario.source(), scenario.category(), scenario.difficulty(),
                    scenario.callerText(), scenario.caller(), scenario.rawAddress(), scenario.expectedAddress(),
                    scenario.expectedIncidentTypes(), scenario.expectedServices(), scenario.addressClarified(),
                    scenario.expectedDecision(), scenario.expectedDecisionReason(), scenario.outboundCallRequired(),
                    scenario.referenceConfirmed(), scenario.referenceConfirmedAt(), scenario.createdBy(), scenario.createdAt()));
            count++;
        }
        if (count > 0) log.info("Проставлены названия для {} сценариев без названия", count);
    }

    /** Названия улиц и населённых пунктов из всех сценариев — словарь для ловли опечаток в адресах. */
    private void learnPlaces() {
        List<String> names = new ArrayList<>();
        for (Scenario scenario : repository.find(null, null, null, 5000)) {
            FormalAddress a = scenario.expectedAddress();
            if (a == null) continue;
            names.add(a.street());
            names.add(a.locality());
        }
        language.learnPlaces(names);
        // тот же список названий — справочник существующих улиц для сверки адреса
        addresses.learn(names);
    }

    private static List<String> toList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null) node.forEach(item -> result.add(item.asText()));
        return result;
    }

    public Scenario require(String id) {
        return repository.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Сценарий не найден: " + id));
    }

    public List<Scenario> requireAll(List<String> ids) {
        List<Scenario> found = repository.findByIds(ids);
        if (found.size() != ids.size()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Часть сценариев не найдена");
        }
        return found;
    }

    public List<ScenarioListItem> list(String category, String source, Boolean confirmed) {
        return repository.find(category, source, confirmed, 500).stream().map(ScenarioService::toListItem).toList();
    }

    public List<Scenario> pick(String source, int count, Random random) {
        List<Scenario> pool = new ArrayList<>(switch (source) {
            case "TRAINEE_MADE" -> repository.find(null, "TRAINEE_MADE", null, 500);
            case "MIXED" -> repository.find(null, null, null, 1000);
            default -> {
                List<Scenario> all = new ArrayList<>(repository.find(null, "TICKET", null, 500));
                all.addAll(repository.find(null, "GENERATED", null, 500));
                yield all;
            }
        });
        java.util.Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(count, pool.size()));
    }

    public Scenario create(ScenarioUpsert request, UUID actor) {
        String id = request.id() == null || request.id().isBlank()
                ? "custom-" + UUID.randomUUID().toString().substring(0, 8) : request.id().trim();
        if (repository.findById(id).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "SCENARIO_EXISTS", "Сценарий с таким идентификатором уже есть");
        }
        Scenario scenario = fromUpsert(id, "GENERATED", request, actor, Instant.now());
        repository.insert(scenario);
        return require(id);
    }

    public Scenario update(String id, ScenarioUpsert request, UUID actor) {
        Scenario existing = require(id);
        Scenario updated = fromUpsert(id, existing.source(), request, existing.createdBy(), existing.createdAt());
        repository.update(updated);
        return require(id);
    }

    public Scenario confirm(String id, UUID actor) {
        Scenario scenario = require(id);
        repository.confirmReference(id, actor);
        // Подтверждённый преподавателем эталон — надёжная разметка: она доучивает подбор типа.
        // Так правки преподавателя влияют на систему, как и просил заказчик (q-and-a.md §8).
        classifier.learn(scenario.callerText(), scenario.expectedIncidentTypes());
        return require(id);
    }

    /** Сценарий, сформированный обучающимся в режиме заполнения карточки (источник TRAINEE_MADE). */
    public Scenario saveTraineeMade(Scenario base, String callerText, FormalAddress address, List<String> types,
                                    List<String> services, UUID author) {
        String id = "trainee-" + UUID.randomUUID().toString().substring(0, 8);
        Scenario scenario = new Scenario(id, "Сформировано: " + base.title(), "TRAINEE_MADE", base.category(), base.difficulty(),
                callerText == null || callerText.isBlank() ? base.callerText() : callerText, base.caller(),
                describe(address), base.expectedAddress(), base.expectedIncidentTypes().isEmpty() ? types : base.expectedIncidentTypes(),
                base.expectedServices().isEmpty() ? services : base.expectedServices(), base.addressClarified(),
                "ACCEPT", null, true, false, null, author, Instant.now());
        repository.insert(scenario);
        return scenario;
    }

    /**
     * Шаблонная генерация без модели: ситуация одного билета категории + адрес другого.
     * Эталон — от билета-источника ситуации, адрес — от билета-источника адреса; преподаватель подтверждает.
     */
    public List<Scenario> generate(GenerateRequest request, UUID actor) {
        List<Scenario> pool = repository.find(request.category(), "TICKET", null, 500);
        if (pool.size() < 2) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Для категории недостаточно билетов-основ для генерации");
        }
        Random random = new Random();
        List<Scenario> result = new ArrayList<>();
        for (int i = 0; i < request.count(); i++) {
            Scenario situation = pool.get(random.nextInt(pool.size()));
            Scenario address = pool.get(random.nextInt(pool.size()));
            String id = "gen-" + UUID.randomUUID().toString().substring(0, 8);
            Scenario scenario = new Scenario(id,
                    "Сгенерировано: " + autoTitle(situation.expectedIncidentTypes(), address.expectedAddress(), situation.callerText()),
                    "GENERATED", request.category(), request.difficulty(),
                    situation.callerText(), situation.caller(), address.rawAddress(), address.expectedAddress(),
                    situation.expectedIncidentTypes(), situation.expectedServices(), address.addressClarified(),
                    "ACCEPT", null, true, false, null, actor, Instant.now());
            repository.insert(scenario);
            result.add(require(id));
        }
        return result;
    }

    private Scenario fromUpsert(String id, String source, ScenarioUpsert request, UUID actor, Instant createdAt) {
        List<String> types = request.expectedIncidentTypes() == null ? List.of() : request.expectedIncidentTypes();
        List<String> services = request.expectedServices() == null || request.expectedServices().isEmpty()
                ? references.servicesFor(types) : request.expectedServices();
        return new Scenario(id, request.title().trim(), source, request.category(), request.difficulty(), request.callerText(),
                request.caller(), request.rawAddress(), request.expectedAddress(), types, services,
                request.expectedAddress() != null, blank(request.expectedDecision()) ? null : request.expectedDecision(),
                request.expectedDecisionReason(), request.outboundCallRequired(), false, null, actor, createdAt);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static String describe(FormalAddress a) {
        if (a == null) return "";
        List<String> parts = new ArrayList<>();
        if (a.locality() != null) parts.add(a.locality());
        if (a.street() != null) parts.add(a.street());
        if (a.house() != null) parts.add("д. " + a.house());
        if (a.building() != null) parts.add("корп. " + a.building());
        if (a.structure() != null) parts.add("стр. " + a.structure());
        if (a.apartment() != null) parts.add("кв. " + a.apartment());
        if (a.descriptive() != null && parts.isEmpty()) parts.add(a.descriptive());
        return String.join(", ", parts);
    }

    public static ScenarioListItem toListItem(Scenario s) {
        return new ScenarioListItem(s.id(), s.title(), s.source(), s.category(), s.difficulty(), s.callerText(),
                s.rawAddress(), s.expectedAddress(), s.expectedIncidentTypes(), s.expectedServices(),
                s.referenceConfirmed(), s.createdAt());
    }

    /** Название по умолчанию: подпись типа + улица или ориентир; без типа — начало вводной. */
    private String autoTitle(List<String> types, FormalAddress address, String callerText) {
        String place = null;
        if (address != null) {
            place = address.street() != null ? address.street()
                    : address.locality() != null && !"Москва".equals(address.locality()) ? address.locality()
                    : address.descriptive();
        }
        String base;
        if (types != null && !types.isEmpty() && references.incidentType(types.get(0)) != null) {
            base = references.incidentType(types.get(0)).label();
        } else {
            String text = callerText == null ? "" : callerText.trim();
            base = text.length() > 60 ? text.substring(0, 57).trim() + "…" : text;
        }
        String title = place == null || place.isBlank() ? base : base + " · " + (place.length() > 60 ? place.substring(0, 57) + "…" : place);
        return title.length() > 200 ? title.substring(0, 200) : title;
    }
}
