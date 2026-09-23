package ru.lct.arm112.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ru.lct.arm112.service.assessment.StreetDictionary;
import ru.lct.arm112.service.assessment.TextUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Справочник существующих улиц для сверки адреса (задача A1 плана).
 *
 * <h2>Откуда берутся названия</h2>
 * <ol>
 *   <li><b>Библиотека сценариев</b> — все улицы из эталонных адресов. Загружается всегда.</li>
 *   <li><b>{@code seed/streets.json}</b> — внешний список, если он поставлен вместе со сборкой.</li>
 * </ol>
 *
 * <h2>Почему этого достаточно для оценки</h2>
 * Справочник отвечает на один вопрос: «названа ли реально существующая улица, но не та».
 * Опасен именно этот случай — расчёт уедет по действительному адресу в другом районе.
 * Улицы, которые обучающийся реально может перепутать в рамках занятия, — это улицы из
 * библиотеки сценариев, и они в справочнике есть по построению. Полная выгрузка ГАР/ФИАС
 * или OSM расширяет покрытие, но не меняет ни правило, ни алгоритм: достаточно положить
 * её в {@code seed/streets.json} как массив строк.
 *
 * <p><b>Осознанный компромисс.</b> Неполный справочник ошибается в безопасную сторону:
 * неизвестная реальная улица будет засчитана как опечатка (−40 вместо −60), то есть
 * обучающийся получит меньший штраф. Обратной ошибки — назвать опечатку существующей
 * улицей — не бывает, потому что в справочник попадают только подтверждённые названия.
 */
@Service
public class AddressReferenceService implements StreetDictionary {
    private static final Logger log = LoggerFactory.getLogger(AddressReferenceService.class);

    private final Set<String> streets = new CopyOnWriteArraySet<>();
    private final ObjectMapper objectMapper;

    public AddressReferenceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        loadExternal();
    }

    @Override
    public boolean exists(String normalizedName) {
        return normalizedName != null && !normalizedName.isBlank() && streets.contains(normalizedName);
    }

    /** Добавить названия из библиотеки сценариев; вызывается при загрузке сценариев. */
    public void learn(Collection<String> names) {
        int before = streets.size();
        for (String name : names) {
            String normalized = TextUtil.normalize(name);
            // Порога по длине нет: название, встречающееся в библиотеке сценариев,
            // по определению существует. В билетах есть короткие обозначения
            // вроде «МО» и «СК», и отбрасывать их значит отвергать верный адрес.
            if (!normalized.isBlank()) streets.add(normalized);
        }
        if (streets.size() > before) {
            log.info("Справочник улиц: {} названий (добавлено {})", streets.size(), streets.size() - before);
        }
    }

    public int size() {
        return streets.size();
    }

    private void loadExternal() {
        ClassPathResource resource = new ClassPathResource("seed/streets.json");
        if (!resource.exists()) {
            log.info("Внешний справочник улиц не поставлен — используются названия из библиотеки сценариев");
            return;
        }
        try (InputStream stream = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(stream);
            // Допускаются два вида файла: голый массив названий и объект с полем streets
            // рядом со сведениями об источнике и лицензии. Второй предпочтителен:
            // данные о происхождении должны ехать вместе с данными, а не в соседнем файле.
            JsonNode list = root.isArray() ? root : root.path("streets");
            List<String> names = new ArrayList<>();
            list.forEach(node -> names.add(node.asText()));
            learn(names);
            log.info("Загружен внешний справочник улиц: {} названий, источник «{}», лицензия «{}»",
                    names.size(), root.path("source").asText("не указан"),
                    root.path("licence").asText("не указана"));
        } catch (IOException exception) {
            // справочник — обогащение, а не обязательный ресурс: без него оценка работает по-старому
            log.warn("Не удалось прочитать seed/streets.json: {}", exception.getMessage());
        }
    }
}
