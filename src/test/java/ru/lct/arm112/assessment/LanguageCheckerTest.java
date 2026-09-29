package ru.lct.arm112.assessment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.lct.arm112.service.assessment.LanguageChecker;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Приёмка проверки грамотности (METRICS.md §5.3).
 *
 * <p>Главное требование — точность, а не полнота: ложное срабатывание дороже пропуска,
 * потому что обучающийся, получив придирку к правильному тексту, перестаёт верить
 * замечаниям вообще. Отсюда отдельная проверка на топонимах и сокращениях служб.
 */
class LanguageCheckerTest {

    private static LanguageChecker checker;

    @BeforeAll
    static void setUp() {
        checker = new LanguageChecker();
        checker.learnPlaces(List.of("Дубнинская", "Берзарина", "Беломорская", "Цюрупы", "Грина"));
    }

    @Test
    void cleanOperatorTextHasNoFindings() {
        LanguageChecker.Result result = checker.check(
                "Задымление мусоропровода в жилом доме. Открытого пламени нет, заявитель на седьмом этаже.");
        assertThat(result.findings()).as("ложные срабатывания на нормальном тексте").isEmpty();
        assertThat(result.score()).isEqualTo(100);
    }

    /** Сокращения служб и названия улиц — не ошибки, сколько бы словарь ни возражал. */
    @Test
    void servicesAndStreetsAreNotErrors() {
        LanguageChecker.Result result = checker.check(
                "Карточка передана в ДДС и ЦОДД. Адрес: Берзарина, 21. Расчёт направлен.");
        assertThat(result.findings()).isEmpty();
    }

    /**
     * Фамилия, переписанная из вводной, — не опечатка. Раньше словарь предлагал
     * «Легкодушев — возможно, Легкодухов», а «ул. Беломорская дом 10» считал ошибкой согласования.
     */
    @Test
    void namesAndTelegraphicAddressAreNotErrors() {
        LanguageChecker.Result result = checker.check(
                "Легкодушев Дмитрий Константинович, головокружение, теряет сознание. "
                        + "Адрес: ул. Беломорская дом 10 корп. 2, вызывает отец Тутуянов.");
        assertThat(result.findings()).isEmpty();
    }

    /** Ключевой случай: похожее написание известной улицы ловится, хотя словарю слово незнакомо. */
    @Test
    void findsTypoInKnownStreet() {
        LanguageChecker.Result result = checker.check("Пожар на улице Берзорина, дом 5.");
        assertThat(result.findings().toString()).contains("Берзорина", "Берзарина");
    }

    @Test
    void findsMixedAlphabet() {
        LanguageChecker.Result result = checker.check("Пожар на улице Дубнинскaя, дом 5.");
        assertThat(result.findings().toString()).contains("латиница");
    }

    @Test
    void findsSpellingError() {
        LanguageChecker.Result result = checker.check("Прибыл на место проишествия, пострадавших нет.");
        assertThat(result.errors()).as("орфографическая ошибка не найдена").isGreaterThan(0);
    }

    /** Одна беда не должна штрафоваться дважды разными проверками. */
    @Test
    void lowercaseTextIsPenalisedOnce() {
        LanguageChecker.Result result = checker.check(
                "задымление мусоропровода, открытого пламени нет, заявитель на седьмом этаже дома");
        assertThat(result.findings())
                .as("отсутствие заглавных должно штрафоваться один раз: %s", result.findings())
                .hasSize(1);
    }

    /** Текст из эталонного прогона R06: фиксируем, за что именно снимаются баллы. */
    @Test
    void sloppyTextFindingsAreDocumented() {
        LanguageChecker.Result result = checker.check(
                "задымление мусоропровода,, открытого пламени нет, заявитель на седьмом этаже дома");
        assertThat(result.findings()).as("находки на небрежном тексте").containsExactlyInAnyOrder(
                "«задымление» — возможно, «Задымление»",
                "«,,» — возможно, «,»");
    }

    @Test
    void emptyTextIsNotAnError() {
        assertThat(checker.check("").errors()).isZero();
        assertThat(checker.check(null).score()).isEqualTo(100);
    }
}
