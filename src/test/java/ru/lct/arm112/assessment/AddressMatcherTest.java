package ru.lct.arm112.assessment;

import org.junit.jupiter.api.Test;
import ru.lct.arm112.api.ApiModels.FormalAddress;
import ru.lct.arm112.service.assessment.AddressMatcher;
import ru.lct.arm112.service.assessment.LanguageChecker;
import ru.lct.arm112.service.assessment.StreetDictionary;
import ru.lct.arm112.service.assessment.TextUtil;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AddressMatcherTest {
    private static FormalAddress address(String locality, String street, String house) {
        return new FormalAddress("Россия", null, locality, null, null, null, street, house,
                null, null, null, null, null, null, null);
    }

    @Test
    void typoInStreetIsCritical() {
        AddressMatcher.Result result = AddressMatcher.score(
                address("Москва", "Дубининская", "12"), address("Москва", "Дубнинская", "12"), UUID.randomUUID());
        assertThat(result.score()).isLessThan(70);
        assertThat(result.issues()).anyMatch(i -> i.code().equals("ADDRESS_STREET_MISMATCH") && i.severity().equals("CRITICAL"));
    }

    /**
     * Ключевое различие, ради которого заведён справочник: «Белозерская» и «Беломорская» —
     * обе реальные улицы Москвы на расстоянии Левенштейна 2. Без справочника это неотличимо
     * от опечатки, а последствия разные: расчёт уедет по действительному адресу в другой район.
     */
    @Test
    void existingOtherStreetIsHeavierThanTypo() {
        StreetDictionary moscow = name -> Set.of("белозерская", "беломорская").contains(name);

        AddressMatcher.Result other = AddressMatcher.score(
                address("Москва", "Белозерская", "10"), address("Москва", "Беломорская", "10"),
                UUID.randomUUID(), moscow);
        assertThat(other.score()).isEqualTo(40);
        assertThat(other.issues()).anyMatch(i -> i.code().equals("ADDRESS_STREET_WRONG"));

        AddressMatcher.Result typo = AddressMatcher.score(
                address("Москва", "Беломарская", "10"), address("Москва", "Беломорская", "10"),
                UUID.randomUUID(), moscow);
        assertThat(typo.score()).isEqualTo(60);
        assertThat(typo.issues()).anyMatch(i -> i.code().equals("ADDRESS_STREET_MISMATCH"));
    }

    /** Без справочника поведение прежнее — обе ситуации считаются опечаткой. */
    @Test
    void withoutDictionaryBothLookLikeTypo() {
        AddressMatcher.Result result = AddressMatcher.score(
                address("Москва", "Белозерская", "10"), address("Москва", "Беломорская", "10"), UUID.randomUUID());
        assertThat(result.score()).isEqualTo(60);
        assertThat(result.issues()).anyMatch(i -> i.code().equals("ADDRESS_STREET_MISMATCH"));
    }

    @Test
    void normalizationStripsStreetType() {
        assertThat(TextUtil.normalize("ул. Берзарина")).isEqualTo("берзарина");
    }

    @Test
    void streetWithPrefixMatches() {
        AddressMatcher.Result result = AddressMatcher.score(
                address("Москва", "ул. Берзарина", "д. 21"), address("Москва", "Берзарина", "21"), UUID.randomUUID());
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void wrongHouseIsWarning() {
        AddressMatcher.Result result = AddressMatcher.score(
                address("Москва", "Грина", "13"), address("Москва", "Грина", "11"), UUID.randomUUID());
        assertThat(result.score()).isEqualTo(80);
        assertThat(result.issues()).anyMatch(i -> i.code().equals("ADDRESS_HOUSE_MISMATCH"));
    }

    @Test
    void detailMismatchIsLabelledInRussian() {
        FormalAddress expected = new FormalAddress("Россия", null, "Москва", null, null, null, "Берзарина", "21",
                "1", null, null, null, null, null, null);
        AddressMatcher.Result result = AddressMatcher.score(address("Москва", "Берзарина", "21"), expected, UUID.randomUUID());
        assertThat(result.score()).isEqualTo(95);
        assertThat(result.issues()).anyMatch(i -> i.code().equals("ADDRESS_DETAIL_MISMATCH")
                && i.message().equals("Не совпадает поле адреса: корпус"));
    }

    @Test
    void languageCheckerFindsPlaceTypoAndMixedAlphabet() {
        LanguageChecker checker = new LanguageChecker();
        checker.learnPlaces(List.of("Дубнинская", "Берзарина"));
        LanguageChecker.Result result = checker.check("Пожар на улице Дубнинскaя, дом 5. Улица Берзорина рядом.");
        assertThat(result.errors()).isGreaterThanOrEqualTo(2);
        assertThat(result.findings().toString()).contains("латиница", "Берзорина");
        assertThat(checker.check("Горит мусорный контейнер, пострадавших нет.").errors()).isEqualTo(0);
    }
}
