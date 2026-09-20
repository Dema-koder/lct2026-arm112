package ru.lct.arm112.service;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiModels.DictionaryItem;
import ru.lct.arm112.api.ApiModels.IncidentTypeItem;
import ru.lct.arm112.api.ApiModels.SurveyCard;
import ru.lct.arm112.api.ApiModels.SurveyQuestion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Справочники из seed/: типы происшествий, опросные карты, службы и матрица оповещения по ЕКП.
 * Читаются один раз при старте; правятся через tools/build_seed.py.
 */
@Service
public class ReferenceDataService {
    public static final String REFERENCE_VERSION = "classifier-046-24";

    private final ObjectMapper objectMapper;
    private final Map<String, IncidentType> incidentTypes = new LinkedHashMap<>();
    private final Map<String, SurveyCard> surveyCards = new LinkedHashMap<>();
    private final Map<String, DictionaryItem> services = new LinkedHashMap<>();
    private final Map<String, List<String>> serviceMatrix = new LinkedHashMap<>();

    public ReferenceDataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() {
        for (JsonNode node : read("seed/services.json")) {
            String code = node.get("code").asText();
            services.put(code, new DictionaryItem("service." + code.toLowerCase(Locale.ROOT), code, node.get("label").asText()));
        }
        for (JsonNode node : read("seed/incident-types.json")) {
            List<String> synonyms = new ArrayList<>();
            node.path("synonyms").forEach(s -> synonyms.add(s.asText()));
            incidentTypes.put(node.get("id").asText(), new IncidentType(node.get("id").asText(),
                    node.get("label").asText(), node.path("category").asText("OTHER"),
                    node.path("frequent").asBoolean(false), node.path("significant").asBoolean(false), synonyms,
                    node.path("surveyCardId").asText(null)));
        }
        JsonNode matrix = read("seed/service-matrix.json");
        matrix.propertyNames().forEach(name -> {
            List<String> codes = new ArrayList<>();
            matrix.get(name).forEach(c -> codes.add(c.asText()));
            serviceMatrix.put(name, codes);
        });
        for (JsonNode node : read("seed/survey-cards.json")) {
            List<SurveyQuestion> questions = new ArrayList<>();
            for (JsonNode q : node.path("questions")) {
                List<DictionaryItem> options = new ArrayList<>();
                for (JsonNode o : q.path("options")) {
                    options.add(new DictionaryItem(o.get("id").asText(), o.get("id").asText(), o.get("label").asText()));
                }
                questions.add(new SurveyQuestion(q.get("id").asText(), q.get("text").asText(),
                        q.path("kind").asText("CHOICE"), options));
            }
            SurveyCard card = new SurveyCard(node.get("id").asText(), node.get("incidentTypeId").asText(), questions);
            surveyCards.put(card.incidentTypeId(), card);
        }
    }

    private JsonNode read(String path) {
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось прочитать справочник " + path, exception);
        }
    }

    public List<IncidentTypeItem> incidentTypes() {
        return incidentTypes.values().stream().map(IncidentType::toItem).toList();
    }

    public IncidentType incidentType(String id) {
        return incidentTypes.get(id);
    }

    /** Поиск как в оригинале: по неизменяемой части слова и по синонимам. */
    public List<IncidentTypeItem> search(String query) {
        if (query == null || query.isBlank()) return incidentTypes();
        String needle = normalize(query);
        return incidentTypes.values().stream()
                .filter(type -> normalize(type.label()).contains(needle)
                        || type.synonyms().stream().anyMatch(s -> normalize(s).contains(needle) || needle.contains(normalize(s))))
                .map(IncidentType::toItem)
                .toList();
    }

    public SurveyCard surveyCard(String incidentTypeId) {
        SurveyCard card = surveyCards.get(incidentTypeId);
        if (card != null) return card;
        // у типа без своей карты — базовые вопросы, чтобы экран не был пустым
        return surveyCards.getOrDefault("_default", new SurveyCard("_default", incidentTypeId, List.of()));
    }

    public List<DictionaryItem> allServices() {
        return List.copyOf(services.values());
    }

    public DictionaryItem service(String code) {
        DictionaryItem item = services.get(code);
        return item != null ? item : new DictionaryItem("service." + code.toLowerCase(Locale.ROOT), code, code);
    }

    /** Службы по ЕКП для набора типов — объединение без дублей, порядок как в справочнике служб. */
    public List<String> servicesFor(Collection<String> incidentTypeIds) {
        Set<String> codes = new LinkedHashSet<>();
        for (String id : incidentTypeIds) {
            codes.addAll(serviceMatrix.getOrDefault(id, List.of()));
        }
        List<String> ordered = new ArrayList<>();
        for (String code : services.keySet()) {
            if (codes.contains(code)) ordered.add(code);
        }
        return ordered;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }

    public record IncidentType(String id, String label, String category, boolean frequent, boolean significant,
                               List<String> synonyms, String surveyCardId) {
        IncidentTypeItem toItem() {
            return new IncidentTypeItem(id, label, category, frequent, significant);
        }
    }
}
