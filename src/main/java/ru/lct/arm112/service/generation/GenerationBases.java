package ru.lct.arm112.service.generation;

import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.FormalAddress;
import ru.lct.arm112.api.ApiModels.ScenarioListItem;
import ru.lct.arm112.service.AddressReferenceService;
import ru.lct.arm112.service.assessment.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Какие сценарии библиотеки годятся в основу генерации и что сказать модели о месте.
 *
 * <p>Генератор берёт у основы адрес и тип, поэтому основа обязана быть чистой: тип задан,
 * адрес — московская улица из справочника с номером дома. Билеты, где в поле «улица» лежит
 * город или ориентир («Балашиха», «МО», «СК»), дают сценарий, за верный ответ по которому
 * обучающийся получит «критичную ошибку», — такие основы отбрасываются.
 */
@Component
public class GenerationBases {

    private static final Set<String> MOSCOW = Set.of("москва", "зеленоград");

    /** Части сырого адреса, которые описывают сам адрес, а не место: их модели не показываем. */
    private static final List<String> ADDRESS_PARTS = List.of(
            "москва", "дом", "д ", "корп", "к ", "стр", "кв", "под", "эт", "код", "домофон", "вл");

    private final AddressReferenceService addresses;

    public GenerationBases(AddressReferenceService addresses) {
        this.addresses = addresses;
    }

    public boolean usable(ScenarioListItem s) {
        if ("TRAINEE_MADE".equals(s.source())) return false;
        if (s.expectedIncidentTypes() == null || s.expectedIncidentTypes().isEmpty()) return false;
        FormalAddress a = s.expectedAddress();
        if (a == null || blank(a.street()) || blank(a.house())) return false;
        if (!blank(a.region()) && !MOSCOW.contains(a.region().toLowerCase(Locale.ROOT))) return false;
        if (!blank(a.locality()) && !MOSCOW.contains(a.locality().toLowerCase(Locale.ROOT))) return false;
        return addresses.exists(TextUtil.normalize(a.street()));
    }

    /**
     * Место происшествия словами — чтобы текст заявителя подходил к адресу основы.
     *
     * <p>Без этого модель пишет про квартиру, а адрес достаётся от тротуара у остановки.
     * Сам адрес модели не передаётся: его подставляет код, а модель его выдумывала
     * вопреки прямому запрету (LLM_EXPERIMENT.md §4б).
     */
    public String placeOf(ScenarioListItem s) {
        FormalAddress a = s.expectedAddress();
        String street = a == null ? "" : TextUtil.normalize(a.street());
        List<String> parts = new ArrayList<>();
        for (String raw : (s.rawAddress() == null ? "" : s.rawAddress()).split(",")) {
            String part = raw.trim();
            String low = part.toLowerCase(Locale.ROOT).replace('ё', 'е') + " ";
            if (part.isEmpty() || (!street.isEmpty() && TextUtil.normalize(part).contains(street))) continue;
            if (ADDRESS_PARTS.stream().anyMatch(low::startsWith) || part.matches("[\\d\\sА-Яа-я/]{1,6}")) continue;
            parts.add(part);
        }
        if (a != null && !blank(a.descriptive()) && parts.isEmpty()
                && (street.isEmpty() || !TextUtil.normalize(a.descriptive()).contains(street))) {
            parts.add(a.descriptive().trim());
        }
        if (!parts.isEmpty()) return String.join(", ", parts);
        return a != null && !blank(a.apartment()) ? "квартира в жилом доме" : "жилой дом или улица у дома";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
