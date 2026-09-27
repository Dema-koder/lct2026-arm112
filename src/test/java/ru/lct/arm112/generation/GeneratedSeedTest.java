package ru.lct.arm112.generation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import ru.lct.arm112.api.ApiModels.FormalAddress;
import ru.lct.arm112.api.ApiModels.ScenarioUpsert;
import ru.lct.arm112.service.generation.ScenarioValidator;
import ru.lct.arm112.service.generation.ScenarioValidator.Verdict;
import ru.lct.arm112.service.generation.ScenarioValidator.Violation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка банка сценариев, сгенерированного заранее вне контура.
 *
 * <p>Заказчик разрешил именно такой порядок: модель может работать снаружи, а в локальный
 * контур попадает готовый набор ([q-and-a.md](../../../../../../docs/materials/q-and-a.md),
 * «Время отклика без GPU» — «генерируйте вопросы и ответы заранее, не в онлайне»).
 *
 * <p>Банк проходит <b>тот же</b> {@link ScenarioValidator}, что и вывод локальной модели.
 * Поэтому цифра отсева здесь и цифра отсева у модели будут сравнимы напрямую, а качество
 * источника — видно по одной метрике независимо от того, кто генерировал.
 *
 * <p>Сценарии попадают в библиотеку <b>неподтверждёнными</b>: решение за преподавателем.
 */
@SpringBootTest
class GeneratedSeedTest {

    @Autowired
    ScenarioValidator validator;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void generatedBankPassesValidation() throws Exception {
        JsonNode root;
        try (InputStream stream = new ClassPathResource("seed/scenarios-generated.json").getInputStream()) {
            root = objectMapper.readTree(stream);
        }
        assertThat(root.size()).as("банк пуст").isGreaterThan(0);

        Map<String, Integer> rejections = new LinkedHashMap<>();
        Map<String, Integer> warnings = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();

        for (JsonNode node : root) {
            ScenarioUpsert candidate = toUpsert(node);
            Verdict verdict = validator.validate(candidate);
            verdict.warnings().forEach(v -> warnings.merge(v.code(), 1, Integer::sum));
            if (!verdict.valid()) {
                verdict.fatal().forEach(v -> rejections.merge(v.code(), 1, Integer::sum));
                rejected.add(node.get("id").asText() + " → "
                        + verdict.fatal().stream().map(Violation::message).toList());
            }
        }

        int accepted = root.size() - rejected.size();
        System.out.printf(Locale.ROOT, "%n=== Банк сценариев, сгенерированный заранее ===%n"
                        + "кандидатов %d, принято %d (%.0f %%), отсеяно %d%n"
                        + "причины отсева: %s%nзамечания:      %s%n",
                root.size(), accepted, 100.0 * accepted / root.size(), rejected.size(),
                rejections.isEmpty() ? "нет" : rejections, warnings.isEmpty() ? "нет" : warnings);
        rejected.forEach(r -> System.out.println("  " + r));

        // Банк пишется под контроль валидатора: если он не проходит, его нельзя поставлять.
        assertThat(rejected).as("кандидаты, не прошедшие проверку").isEmpty();
    }

    private ScenarioUpsert toUpsert(JsonNode node) {
        FormalAddress address = objectMapper.treeToValue(node.get("expectedAddress"), FormalAddress.class);
        List<String> types = new ArrayList<>();
        node.get("expectedIncidentTypes").forEach(t -> types.add(t.asText()));
        List<String> services = new ArrayList<>();
        node.get("expectedServices").forEach(t -> services.add(t.asText()));
        return new ScenarioUpsert(node.get("id").asText(), node.get("callerText").asText(),
                node.get("category").asText(), node.get("difficulty").asInt(5),
                node.get("callerText").asText(), null, node.get("rawAddress").asText(), address,
                types, services, "ACCEPT", null, true);
    }
}
