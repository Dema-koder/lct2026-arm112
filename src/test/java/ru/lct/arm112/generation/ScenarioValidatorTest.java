package ru.lct.arm112.generation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.lct.arm112.api.ApiModels.FormalAddress;
import ru.lct.arm112.api.ApiModels.ScenarioUpsert;
import ru.lct.arm112.service.generation.ScenarioValidator;
import ru.lct.arm112.service.generation.ScenarioValidator.Verdict;
import ru.lct.arm112.service.generation.ScenarioValidator.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Приёмка защиты от галлюцинаций (METRICS.md §5.8).
 *
 * <p>Проверяется <b>без всякой модели</b>: на вход подаются намеренно испорченные
 * сценарии — по одному дефекту на каждый вид, — и меряется доля пойманного.
 * Это и есть тот слой, который решает, попадёт ли выдумка в библиотеку;
 * качество самого генератора здесь ни при чём.
 */
@SpringBootTest
class ScenarioValidatorTest {

    @Autowired
    ScenarioValidator validator;

    /** Заведомо годный сценарий: улица из справочника, тип следует из текста, служб по ЕКП. */
    private static ScenarioUpsert good() {
        return new ScenarioUpsert(null, "Возгорание мусора на Цюрупы", "FIRE", 5,
                "Во дворе горит мусорный контейнер, огонь до метра, пострадавших нет.",
                null, "Цюрупы, во дворе",
                new FormalAddress("Россия", null, "Москва", null, null, null, "Цюрупы", "12",
                        "6", null, null, null, null, null, null),
                List.of("fire.garbage"), null, "ACCEPT", null, true);
    }

    @Test
    void acceptsValidScenario() {
        Verdict verdict = validator.validate(good());
        assertThat(verdict.fatal()).as("годный сценарий отклонён: %s", verdict.fatal()).isEmpty();
        assertThat(verdict.valid()).isTrue();
    }

    @Test
    void catchesEveryKindOfDefect() {
        record Broken(String name, String expectedCode, Function<ScenarioUpsert, ScenarioUpsert> breakIt) {}

        List<Broken> cases = List.of(
                new Broken("выдуманная улица", "STREET_UNKNOWN",
                        s -> withAddress(s, new FormalAddress("Россия", null, "Москва", null, null, null,
                                "Звёздногорская", "12", null, null, null, null, null, null, null))),
                new Broken("нет адреса вовсе", "ADDRESS_MISSING", s -> withAddress(s, null)),
                new Broken("несуществующий тип", "TYPE_UNKNOWN", s -> withTypes(s, List.of("fire.dragon"))),
                new Broken("тип противоречит тексту", "TYPE_CONTRADICTS_TEXT",
                        s -> withTypes(s, List.of("person.missing"))),
                new Broken("нет типа", "TYPE_MISSING", s -> withTypes(s, List.of())),
                new Broken("пустой текст заявителя", "CALLER_TEXT_MISSING", s -> withText(s, null)),
                new Broken("текст в два слова", "CALLER_TEXT_TOO_SHORT", s -> withText(s, "Горит.")),
                new Broken("адрес пересказан в тексте", "ADDRESS_LEAKED",
                        s -> withText(s, "Горит мусорный контейнер, адрес цюрупы 12, пострадавших нет.")),
                new Broken("подпись типа в тексте", "TYPE_LEAKED",
                        s -> withText(s, "У нас тут пожар мусора во дворе, огонь до метра, никто не пострадал.")),
                new Broken("нет названия", "TITLE_MISSING", s -> withTitle(s, null)));

        List<String> missed = new ArrayList<>();
        for (Broken broken : cases) {
            Verdict verdict = validator.validate(broken.breakIt().apply(good()));
            List<String> codes = verdict.all().stream().map(Violation::code).toList();
            if (!codes.contains(broken.expectedCode())) {
                missed.add(String.format("%s: ждали %s, получили %s",
                        broken.name(), broken.expectedCode(), codes));
            }
        }

        double caught = 100.0 * (cases.size() - missed.size()) / cases.size();
        System.out.printf(Locale.ROOT, "%n=== Защита от галлюцинаций ===%n"
                + "видов дефектов: %d, поймано: %.0f %%%n", cases.size(), caught);
        missed.forEach(m -> System.out.println("  пропущено — " + m));

        assertThat(missed).as("пропущенные дефекты").isEmpty();
    }

    /** Дефект обязан быть фатальным, а не замечанием: иначе выдумка всё равно попадёт в библиотеку. */
    @Test
    void inventedStreetIsFatal() {
        Verdict verdict = validator.validate(withAddress(good(),
                new FormalAddress("Россия", null, "Москва", null, null, null, "Звёздногорская", "12",
                        null, null, null, null, null, null, null)));
        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.fatal()).anyMatch(v -> v.code().equals("STREET_UNKNOWN"));
    }

    /** Совпадение адреса и типа с существующим сценарием — повод взглянуть, но не запрет. */
    @Test
    void duplicateIsWarningNotRejection() {
        Verdict verdict = validator.validate(good());
        assertThat(verdict.valid()).as("дубль не должен блокировать сохранение").isTrue();
    }

    // ------------------------------------------------------------------ помощники

    private static ScenarioUpsert withAddress(ScenarioUpsert s, FormalAddress address) {
        return new ScenarioUpsert(s.id(), s.title(), s.category(), s.difficulty(), s.callerText(), s.caller(),
                s.rawAddress(), address, s.expectedIncidentTypes(), s.expectedServices(),
                s.expectedDecision(), s.expectedDecisionReason(), s.outboundCallRequired());
    }

    private static ScenarioUpsert withTypes(ScenarioUpsert s, List<String> types) {
        return new ScenarioUpsert(s.id(), s.title(), s.category(), s.difficulty(), s.callerText(), s.caller(),
                s.rawAddress(), s.expectedAddress(), types, s.expectedServices(),
                s.expectedDecision(), s.expectedDecisionReason(), s.outboundCallRequired());
    }

    private static ScenarioUpsert withText(ScenarioUpsert s, String text) {
        return new ScenarioUpsert(s.id(), s.title(), s.category(), s.difficulty(), text, s.caller(),
                s.rawAddress(), s.expectedAddress(), s.expectedIncidentTypes(), s.expectedServices(),
                s.expectedDecision(), s.expectedDecisionReason(), s.outboundCallRequired());
    }

    private static ScenarioUpsert withTitle(ScenarioUpsert s, String title) {
        return new ScenarioUpsert(s.id(), title, s.category(), s.difficulty(), s.callerText(), s.caller(),
                s.rawAddress(), s.expectedAddress(), s.expectedIncidentTypes(), s.expectedServices(),
                s.expectedDecision(), s.expectedDecisionReason(), s.outboundCallRequired());
    }
}
