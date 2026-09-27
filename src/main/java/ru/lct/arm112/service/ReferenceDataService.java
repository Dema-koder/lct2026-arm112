package ru.lct.arm112.service;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiModels.DictionaryItem;
import ru.lct.arm112.api.ApiModels.IncidentTypeItem;
import ru.lct.arm112.api.ApiModels.FormalAddress;
import ru.lct.arm112.api.ApiModels.ServiceItem;
import ru.lct.arm112.api.ApiModels.SurveyCard;
import ru.lct.arm112.api.ApiModels.SurveyQuestion;
import ru.lct.arm112.api.ApiModels.SurveyTree;
import ru.lct.arm112.api.ApiModels.TopTypeItem;
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
    private final Map<String, DictionaryItem> services = new LinkedHashMap<>();
    private final Map<String, ServiceItem> serviceDetails = new LinkedHashMap<>();
    private final Map<String, List<String>> serviceMatrix = new LinkedHashMap<>();
    /** Типы верхнего уровня списка «что случилось?» (КАРТОЧКА 112.docx). */
    private final Map<String, TopTypeItem> topTypes = new LinkedHashMap<>();
    /** Опросные карты: по одной на тип верхнего уровня, вопросы ветвятся по ответам. */
    private final Map<String, SurveyTree> surveyTrees = new LinkedHashMap<>();
    /** Правила вывода типа происшествия ЕКП из ответов опросной карты. */
    private final Map<String, List<TypeRule>> typeRules = new LinkedHashMap<>();

    public ReferenceDataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() {
        for (JsonNode node : read("seed/services.json")) {
            String code = node.get("code").asText();
            services.put(code, new DictionaryItem("service." + code.toLowerCase(Locale.ROOT), code, node.get("label").asText()));
            serviceDetails.put(code, new ServiceItem(code, node.get("label").asText(),
                    node.path("fullName").asText(node.get("label").asText()), node.path("kind").asText("CITY"),
                    node.path("okrug").asText(null), node.path("district").asText(null),
                    node.path("settlement").asText(null)));
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
        for (JsonNode node : read("seed/card-types.json")) {
            topTypes.put(node.get("id").asText(), new TopTypeItem(node.get("id").asText(),
                    node.get("label").asText(), node.path("frequent").asBoolean(false)));
        }
        for (JsonNode node : read("seed/survey-trees.json")) {
            String topTypeId = node.get("topTypeId").asText();
            List<SurveyQuestion> questions = new ArrayList<>();
            for (JsonNode q : node.path("questions")) {
                List<DictionaryItem> options = new ArrayList<>();
                for (JsonNode o : q.path("options")) {
                    options.add(new DictionaryItem(o.get("id").asText(), o.get("id").asText(), o.get("label").asText()));
                }
                List<Map<String, List<String>>> showWhen = new ArrayList<>();
                for (JsonNode cond : q.path("showWhen")) showWhen.add(conditions(cond));
                questions.add(new SurveyQuestion(q.get("id").asText(), q.get("text").asText(),
                        q.path("kind").asText("CHOICE"), options, showWhen, q.path("synthetic").asBoolean(false)));
            }
            String defaultType = node.path("defaultType").asText(null);
            surveyTrees.put(topTypeId, new SurveyTree(topTypeId, topTypes.containsKey(topTypeId)
                    ? topTypes.get(topTypeId).label() : topTypeId, questions, defaultType));
            List<TypeRule> rules = new ArrayList<>();
            for (JsonNode rule : node.path("rules")) {
                rules.add(new TypeRule(conditions(rule.get("when")), rule.get("type").asText()));
            }
            typeRules.put(topTypeId, rules);
        }
    }

    private Map<String, List<String>> conditions(JsonNode node) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (node == null) return result;
        node.propertyNames().forEach(name -> {
            List<String> values = new ArrayList<>();
            node.get(name).forEach(v -> values.add(v.asText()));
            result.put(name, values);
        });
        return result;
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

    /** Все типы с синонимами и категориями — вход для классификатора по тексту вводной. */
    public List<IncidentType> allIncidentTypes() {
        return List.copyOf(incidentTypes.values());
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

    /** Список «что случилось?» — 49 позиций верхнего уровня как в ПОВ-112 плюс справки. */
    public List<TopTypeItem> topTypes() {
        return List.copyOf(topTypes.values());
    }

    public List<TopTypeItem> searchTopTypes(String query) {
        if (query == null || query.isBlank()) return topTypes();
        String needle = normalize(query);
        return topTypes.values().stream().filter(t -> normalize(t.label()).contains(needle)).toList();
    }

    /** Опросная карта типа верхнего уровня; null — если карты нет. */
    public SurveyTree surveyTree(String topTypeId) {
        return surveyTrees.get(topTypeId);
    }

    /**
     * Тип происшествия ЕКП по ответам опросной карты: первое подошедшее правило, иначе тип по умолчанию.
     * Ответ в вопросе с несколькими выбранными значениями учитывается по любому из них.
     */
    public String resolveIncidentType(String topTypeId, Map<String, List<String>> answers) {
        for (TypeRule rule : typeRules.getOrDefault(topTypeId, List.of())) {
            if (matches(rule.when(), answers)) return rule.type();
        }
        SurveyTree tree = surveyTrees.get(topTypeId);
        return tree == null ? null : tree.defaultType();
    }

    private boolean matches(Map<String, List<String>> when, Map<String, List<String>> answers) {
        for (Map.Entry<String, List<String>> entry : when.entrySet()) {
            List<String> given = answers.getOrDefault(entry.getKey(), List.of());
            if (given.stream().noneMatch(entry.getValue()::contains)) return false;
        }
        return true;
    }

    /** Старый формат опросной карты по типу ЕКП — для совместимости клиентов контракта 0.3. */
    public SurveyCard surveyCard(String incidentTypeId) {
        for (Map.Entry<String, List<TypeRule>> entry : typeRules.entrySet()) {
            boolean own = entry.getValue().stream().anyMatch(r -> r.type().equals(incidentTypeId));
            SurveyTree tree = surveyTrees.get(entry.getKey());
            if (tree == null) continue;
            if (own || incidentTypeId.equals(tree.defaultType())) {
                return new SurveyCard(entry.getKey(), incidentTypeId, tree.questions());
            }
        }
        return new SurveyCard("_default", incidentTypeId, List.of());
    }

    public List<DictionaryItem> allServices() {
        return List.copyOf(services.values());
    }

    /** Полный справочник служб ПОВ-112 с видом и территорией (СЛУЖБЫ 112.docx). */
    public List<ServiceItem> serviceCatalog() {
        return List.copyOf(serviceDetails.values());
    }

    public ServiceItem serviceDetails(String code) {
        return serviceDetails.get(code);
    }

    public DictionaryItem service(String code) {
        DictionaryItem item = services.get(code);
        return item != null ? item : new DictionaryItem("service." + code.toLowerCase(Locale.ROOT), code, code);
    }

    /** Службы по ЕКП для набора типов — объединение без дублей, порядок как в справочнике служб. */
    public List<String> servicesFor(Collection<String> incidentTypeIds) {
        return servicesFor(incidentTypeIds, null);
    }

    /**
     * Службы по ЕКП плюс территориальные ДДС по адресу (ответ заказчика 21.09: подтягиваются
     * по району обслуживания и подчинённости). Колонки ЕКП «Территориальные ОИВ» и «ОИВ ТиНАО»
     * раскрываются в конкретные ДДС района, поселения и префектуры округа.
     */
    public List<String> servicesFor(Collection<String> incidentTypeIds, FormalAddress address) {
        Set<String> codes = new LinkedHashSet<>();
        for (String id : incidentTypeIds) {
            codes.addAll(serviceMatrix.getOrDefault(id, List.of()));
        }
        if (codes.contains("OIV") || codes.contains("OIV_TINAO")) {
            List<String> territorial = territorialServices(address);
            if (!territorial.isEmpty()) {
                // колонка ЕКП заменяется конкретными ДДС района, поселения и префектуры округа
                codes.remove("OIV");
                codes.remove("OIV_TINAO");
                codes.addAll(territorial);
            }
        }
        List<String> ordered = new ArrayList<>();
        for (String code : services.keySet()) {
            if (codes.contains(code)) ordered.add(code);
        }
        return ordered;
    }

    /**
     * Колонка ЕКП, которой соответствует служба: территориальные ДДС сворачиваются в «Терр. ОИВ»
     * (поселения ТиНАО — в «ОИВ ТиНАО»), чтобы эталон сценария и заполненная карточка сравнивались одинаково.
     */
    public String matrixGroupOf(String code) {
        ServiceItem item = serviceDetails.get(code);
        if (item == null) return code;
        return switch (item.kind()) {
            case "DISTRICT", "OKRUG" -> "ТиНАО".equals(item.okrug()) ? "OIV_TINAO" : "OIV";
            case "SETTLEMENT" -> "OIV_TINAO";
            default -> code;
        };
    }

    /** ДДС района (или поселения) и префектуры округа по формализованному адресу. */
    public List<String> territorialServices(FormalAddress address) {
        if (address == null) return List.of();
        List<String> result = new ArrayList<>();
        String district = normalize(address.district());
        String okrug = normalize(address.okrug());
        if (!district.isEmpty()) {
            for (ServiceItem item : serviceDetails.values()) {
                String own = normalize(item.district() != null ? item.district() : item.settlement());
                if (!own.isEmpty() && (own.equals(district) || district.contains(own))) {
                    result.add(item.code());
                    if (okrug.isEmpty() && item.okrug() != null) okrug = normalize(item.okrug());
                    break;
                }
            }
        }
        if (!okrug.isEmpty()) {
            for (ServiceItem item : serviceDetails.values()) {
                if ("OKRUG".equals(item.kind()) && normalize(item.okrug()).equals(okrug)) {
                    result.add(item.code());
                    break;
                }
            }
        }
        return result;
    }

    private record TypeRule(Map<String, List<String>> when, String type) {}

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
