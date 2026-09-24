package ru.lct.arm112.service.assessment;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Проверка, что текст пригоден к показу человеку: русский и без чужих письменностей.
 *
 * <p>Заведена после замера локальных языковых моделей. Без ограничения выборки токенов
 * и 3B, и 7B срывались на китайский прямо посреди фразы — «Пожар в мусорном баку,
 * 没有人说话». Грамматика GBNF это закрывает, но полагаться только на неё нельзя:
 * модель может смениться, грамматику могут отключить, а показать обучающемуся
 * иероглифы нельзя ни при каких обстоятельствах.
 *
 * <p>Поэтому проверка стоит <b>на выходе</b>, перед показом, а не только на входе
 * в генератор. Это последний рубеж, и он дешёвый.
 */
@Component
public class RussianTextGuard {

    /** Доля латиницы к кириллице, выше которой текст подозрителен. */
    private static final double LATIN_SHARE = 0.1;

    /**
     * Что не так с текстом.
     *
     * @param usable можно ли показывать человеку
     * @param reason почему нельзя; {@code null}, если можно
     */
    public record Check(boolean usable, String reason) {
        public static final Check OK = new Check(true, null);
    }

    public Check check(String text) {
        if (text == null || text.isBlank()) {
            return new Check(false, "текст пуст");
        }
        Set<Character> foreign = foreignCharacters(text);
        if (!foreign.isEmpty()) {
            StringBuilder sample = new StringBuilder();
            foreign.stream().limit(6).forEach(sample::append);
            return new Check(false, "чужая письменность: «" + sample + "»");
        }
        int cyrillic = 0, latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (c >= 'а' && c <= 'я' || c == 'ё') cyrillic++;
            else if (c >= 'a' && c <= 'z') latin++;
        }
        if (cyrillic == 0) {
            return new Check(false, "текст не на русском языке");
        }
        if (latin > cyrillic * LATIN_SHARE) {
            return new Check(false, "слишком много латиницы: похоже, генерация сорвалась");
        }
        return Check.OK;
    }

    /** Символы письменностей, которых в тексте для оператора службы 112 быть не может. */
    private static Set<Character> foreignCharacters(String text) {
        Set<Character> found = new LinkedHashSet<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isForeignScript(c)) found.add(c);
        }
        return found;
    }

    private static boolean isForeignScript(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.ARABIC
                || block == Character.UnicodeBlock.HEBREW
                || block == Character.UnicodeBlock.DEVANAGARI
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
