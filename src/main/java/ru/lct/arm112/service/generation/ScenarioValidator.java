package ru.lct.arm112.service.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiModels.FormalAddress;
import ru.lct.arm112.api.ApiModels.ScenarioListItem;
import ru.lct.arm112.api.ApiModels.ScenarioUpsert;
import ru.lct.arm112.service.AddressReferenceService;
import ru.lct.arm112.service.ReferenceDataService;
import ru.lct.arm112.service.ScenarioService;
import ru.lct.arm112.service.analytics.IncidentTypeClassifier;
import ru.lct.arm112.service.analytics.IncidentTypeClassifier.Suggestion;
import ru.lct.arm112.service.assessment.TextUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Проверка сгенерированного сценария перед попаданием в библиотеку.
 *
 * <h2>Зачем</h2>
 * Заказчик не ставил жёстких требований к галлюцинациям, но просил контроль преподавателя
 * и постепенное улучшение ([q-and-a.md](../../../../../../docs/materials/q-and-a.md), §3).
 * Проверка преподавателем — последний рубеж, а не первый: разбирать заведомо негодное
 * вручную значит тратить его время впустую.
 *
 * <p><b>Защита строится не на доверии модели, а на сверке с тем, что известно точно:</b>
 * справочником улиц, классификатором происшествий и матрицей ЕКП. Модель может выдумать
 * что угодно — в библиотеку попадёт только то, что сошлось со справочниками.
 *
 * <p>Поэтому валидатор существует и проверяется <b>без всякой модели</b>: на вход подаются
 * намеренно испорченные сценарии, и меряется доля пойманного.
 *
 * <h2>Что проверяется</h2>
 * <ol>
 *   <li>обязательные поля и пределы длины;</li>
 *   <li>адрес: улица существует в справочнике, дом указан;</li>
 *   <li>тип: существует в классификаторе и не противоречит тексту заявителя;</li>
 *   <li>службы: соответствуют типу по ЕКП;</li>
 *   <li>утечка эталона: формализованный адрес или подпись типа не пересказаны в тексте
 *       заявителя дословно — иначе задание решается копированием;</li>
 *   <li>дубль: такого же происшествия по тому же адресу в библиотеке ещё нет.</li>
 * </ol>
 */
@Service
public class ScenarioValidator {
    private static final Logger log = LoggerFactory.getLogger(ScenarioValidator.class);

    /**
     * Тип считается непротиворечащим тексту, если попал в первые N кандидатов.
     *
     * <p>Требовать первое место нельзя: классификатор угадывает его лишь в двух случаях
     * из трёх (см. {@link IncidentTypeClassifier}), и строгое правило отсеивало бы годные
     * сценарии. Задача проверки — поймать грубое расхождение, когда текст про пожар
     * снабжён типом «пропал человек», а не придираться к оттенкам внутри категории.
     */
    private static final int TYPE_CANDIDATES = 5;
    private static final int MIN_CALLER_TEXT = 20;
    /** Подпись короче этого в тексте — совпадение с обычной речью, а не утечка. */
    private static final int MIN_LEAKED_LABEL = 8;
    /** Слова, по которым видно, что происшествие в жилом доме. */
    private static final List<String> RESIDENTIAL_MARKERS = List.of(
            "подъезд", "этаж", "квартир", "лифт", "мусоропровод", "балкон", "лестничн", "домофон");

    private final AddressReferenceService addresses;
    private final ReferenceDataService references;
    private final IncidentTypeClassifier classifier;
    private final ScenarioService scenarios;

    public ScenarioValidator(AddressReferenceService addresses, ReferenceDataService references,
                             IncidentTypeClassifier classifier, ScenarioService scenarios) {
        this.addresses = addresses;
        this.references = references;
        this.classifier = classifier;
        this.scenarios = scenarios;
    }

    /** Нарушение с машинным кодом и объяснением для преподавателя. */
    public record Violation(String code, String message, String field) {}

    /**
     * Итог проверки.
     *
     * @param fatal нарушения, при которых сценарий не попадает в библиотеку
     * @param warnings то, на что стоит взглянуть, но что не мешает сохранить
     */
    public record Verdict(boolean valid, List<Violation> fatal, List<Violation> warnings) {
        public List<Violation> all() {
            List<Violation> result = new ArrayList<>(fatal);
            result.addAll(warnings);
            return result;
        }
    }

    public Verdict validate(ScenarioUpsert scenario) {
        List<Violation> fatal = new ArrayList<>();
        List<Violation> warnings = new ArrayList<>();

        checkRequired(scenario, fatal);
        checkAddress(scenario, fatal, warnings);
        checkTypes(scenario, fatal, warnings);
        checkServices(scenario, warnings);
        checkLeak(scenario, fatal);
        checkContext(scenario, warnings);
        checkDuplicate(scenario, warnings);

        boolean valid = fatal.isEmpty();
        if (!valid) {
            log.debug("Сценарий отклонён: {}", fatal.stream().map(Violation::code).toList());
        }
        return new Verdict(valid, fatal, warnings);
    }

    // ------------------------------------------------------------------ проверки

    private void checkRequired(ScenarioUpsert s, List<Violation> fatal) {
        if (isBlank(s.title())) {
            fatal.add(new Violation("TITLE_MISSING", "Не задано название сценария", "title"));
        }
        if (isBlank(s.callerText())) {
            fatal.add(new Violation("CALLER_TEXT_MISSING", "Нет текста заявителя", "callerText"));
        } else if (s.callerText().trim().length() < MIN_CALLER_TEXT) {
            fatal.add(new Violation("CALLER_TEXT_TOO_SHORT",
                    "Текст заявителя короче " + MIN_CALLER_TEXT + " символов: вводная не получится",
                    "callerText"));
        }
        if (isBlank(s.category())) {
            fatal.add(new Violation("CATEGORY_MISSING", "Не задана категория", "category"));
        }
    }

    private void checkAddress(ScenarioUpsert s, List<Violation> fatal, List<Violation> warnings) {
        FormalAddress address = s.expectedAddress();
        if (address == null) {
            fatal.add(new Violation("ADDRESS_MISSING",
                    "Нет формализованного адреса: не с чем сверять ответ обучающегося", "expectedAddress"));
            return;
        }
        String street = address.street();
        if (isBlank(street) && isBlank(address.descriptive())) {
            fatal.add(new Violation("STREET_MISSING",
                    "Не указаны ни улица, ни ориентир", "expectedAddress.street"));
            return;
        }
        if (!isBlank(street) && !addresses.exists(TextUtil.normalize(street))) {
            // Выдуманная улица — худшее, что может сделать генератор: обучающийся
            // будет наказан за верно набранный адрес, которого не существует.
            fatal.add(new Violation("STREET_UNKNOWN",
                    "Улицы «" + street + "» нет в справочнике", "expectedAddress.street"));
        }
        if (!isBlank(street) && isBlank(address.house())) {
            warnings.add(new Violation("HOUSE_MISSING",
                    "Улица указана без дома: оценка адреса будет неполной", "expectedAddress.house"));
        }
    }

    private void checkTypes(ScenarioUpsert s, List<Violation> fatal, List<Violation> warnings) {
        List<String> types = s.expectedIncidentTypes();
        if (types == null || types.isEmpty()) {
            fatal.add(new Violation("TYPE_MISSING", "Не задан тип происшествия", "expectedIncidentTypes"));
            return;
        }
        for (String id : types) {
            if (references.incidentType(id) == null) {
                fatal.add(new Violation("TYPE_UNKNOWN",
                        "Типа «" + id + "» нет в классификаторе", "expectedIncidentTypes"));
            }
        }
        if (isBlank(s.callerText())) return;

        List<String> candidates = classifier.rank(s.callerText(), TYPE_CANDIDATES).stream()
                .map(Suggestion::typeId).toList();
        if (candidates.isEmpty()) {
            warnings.add(new Violation("TYPE_UNVERIFIABLE",
                    "По тексту заявителя тип не определяется: проверьте вручную", "callerText"));
            return;
        }
        if (types.stream().noneMatch(candidates::contains)) {
            fatal.add(new Violation("TYPE_CONTRADICTS_TEXT",
                    "Тип не следует из текста заявителя: по описанию похоже на «"
                            + labelOf(candidates.get(0)) + "»", "expectedIncidentTypes"));
        }
    }

    private void checkServices(ScenarioUpsert s, List<Violation> warnings) {
        List<String> declared = s.expectedServices();
        if (declared == null || declared.isEmpty()) return; // подставятся по ЕКП при сохранении
        List<String> types = s.expectedIncidentTypes() == null ? List.of() : s.expectedIncidentTypes();
        Set<String> expected = new LinkedHashSet<>(references.servicesFor(types));
        if (expected.isEmpty()) return;

        List<String> extra = declared.stream().filter(code -> !expected.contains(code)).toList();
        List<String> missing = expected.stream().filter(code -> !declared.contains(code)).toList();
        if (!extra.isEmpty() || !missing.isEmpty()) {
            warnings.add(new Violation("SERVICES_OFF_CLASSIFIER",
                    "Состав служб расходится с ЕКП: лишние " + extra + ", отсутствуют " + missing,
                    "expectedServices"));
        }
    }

    /**
     * Утечка эталона в текст заявителя.
     *
     * <p>Заявитель не говорит формализованным адресом и не называет тип по классификатору.
     * Если генератор пересказал эталон в тексте, задание решается копированием и ничего
     * не проверяет.
     */
    private void checkLeak(ScenarioUpsert s, List<Violation> fatal) {
        if (isBlank(s.callerText())) return;
        String text = s.callerText().toLowerCase(Locale.ROOT).replace('ё', 'е');
        String normalizedText = text.replaceAll("[^а-я0-9]+", " ").replaceAll("\s+", " ").trim();

        FormalAddress address = s.expectedAddress();
        if (address != null && !isBlank(address.street()) && !isBlank(address.house())) {
            String formal = (TextUtil.normalize(address.street()) + " " + address.house().toLowerCase(Locale.ROOT))
                    .trim();
            if (text.contains(formal)) {
                fatal.add(new Violation("ADDRESS_LEAKED",
                        "Формализованный адрес пересказан в тексте заявителя: задание решается копированием",
                        "callerText"));
            }
        }
        List<String> types = s.expectedIncidentTypes() == null ? List.of() : s.expectedIncidentTypes();
        for (String id : types) {
            ReferenceDataService.IncidentType type = references.incidentType(id);
            if (type == null || type.label() == null) continue;
            // Сверяется подпись целиком, а не первое слово. «Пожар» заявитель говорит
            // сам — это нормальная речь, а не утечка; утечка — когда в текст попала
            // формулировка классификатора: «пожар мусор», «внезапное заболевание».
            String label = type.label().toLowerCase(Locale.ROOT).replace('ё', 'е')
                    .replace(":", " ").replaceAll("\s+", " ").trim();
            if (label.length() >= MIN_LEAKED_LABEL && normalizedText.contains(label)) {
                fatal.add(new Violation("TYPE_LEAKED",
                        "Подпись типа «" + type.label() + "» встречается в тексте заявителя", "callerText"));
                return;
            }
        }
    }

    /**
     * Обстановка в тексте против вида адреса.
     *
     * <p>Слабое место перестановочного генератора: ситуация берётся из одного билета,
     * адрес из другого, и получается «горит на седьмом этаже» по адресу железнодорожного
     * переезда. Структурно всё верно — улица настоящая, тип свой, службы по ЕКП, — и
     * остальные проверки такое пропускают.
     *
     * <p>Полноценно это требует понимания текста. Здесь ловится очевидное: заявитель
     * говорит о жилом доме (подъезд, этаж, квартира, лифт), а в адресе нет номера дома,
     * то есть это перегон, трасса или ориентир. Замечание, а не отказ: бывают адреса
     * без номера и в жилой застройке.
     */
    private void checkContext(ScenarioUpsert s, List<Violation> warnings) {
        if (isBlank(s.callerText())) return;
        FormalAddress address = s.expectedAddress();
        if (address == null || !isBlank(address.house())) return;

        String text = s.callerText().toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String marker : RESIDENTIAL_MARKERS) {
            if (text.contains(marker)) {
                warnings.add(new Violation("CONTEXT_MISMATCH",
                        "Заявитель говорит о жилом доме («" + marker + "»), а в адресе нет номера дома",
                        "callerText"));
                return;
            }
        }
    }

    private void checkDuplicate(ScenarioUpsert s, List<Violation> warnings) {
        FormalAddress address = s.expectedAddress();
        if (address == null || isBlank(address.street())) return;
        String street = TextUtil.normalize(address.street());
        List<String> types = s.expectedIncidentTypes() == null ? List.of() : s.expectedIncidentTypes();

        for (ScenarioListItem existing : scenarios.list(null, null, null)) {
            FormalAddress other = existing.expectedAddress();
            if (other == null || isBlank(other.street())) continue;
            if (!TextUtil.normalize(other.street()).equals(street)) continue;
            if (existing.expectedIncidentTypes().stream().noneMatch(types::contains)) continue;
            warnings.add(new Violation("POSSIBLE_DUPLICATE",
                    "Похоже на дубль сценария «" + existing.title() + "»: тот же адрес и тип",
                    "expectedAddress"));
            return;
        }
    }

    private String labelOf(String typeId) {
        ReferenceDataService.IncidentType type = references.incidentType(typeId);
        return type == null ? typeId : type.label();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
