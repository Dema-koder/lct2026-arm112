package ru.lct.arm112.service.assessment;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверка грамотности ввода — ровно в том объёме, который просил заказчик (Q&A §6):
 * не орфографический словарь, а ловля опечаток, из-за которых службы поедут не туда,
 * плюс явные признаки небрежного набора.
 */
@Component
public class LanguageChecker {
    private static final Pattern WORD = Pattern.compile("[A-Za-zА-Яа-яЁё\\-]{2,}");
    private static final Pattern MIXED_ALPHABET = Pattern.compile("(?=.*[A-Za-z])(?=.*[А-Яа-яЁё]).+");
    private static final Pattern TRIPLE_LETTER = Pattern.compile("(.)\\1\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOUBLE_PUNCT = Pattern.compile("[,.!?;:]{2,}|\\s[,.;:]");

    /** Названия улиц и населённых пунктов из всех сценариев — ловим «Дубнинская / Дубининская». */
    private final Set<String> knownPlaces = new CopyOnWriteArraySet<>();

    public void learnPlaces(Collection<String> names) {
        for (String name : names) {
            if (name == null) continue;
            for (String token : TextUtil.normalize(name).split(" ")) {
                if (token.length() >= 5) knownPlaces.add(token);
            }
        }
    }

    public record Result(int errors, double score, List<String> findings) {}

    public Result check(String text) {
        List<String> findings = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new Result(0, 100, findings);
        }
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            if (MIXED_ALPHABET.matcher(word).matches()) {
                findings.add("Смешаны латиница и кириллица: «" + word + "»");
                continue;
            }
            if (TRIPLE_LETTER.matcher(word).find()) {
                findings.add("Тройная буква: «" + word + "»");
                continue;
            }
            String lower = word.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (lower.length() >= 5 && !knownPlaces.contains(lower)) {
                for (String place : knownPlaces) {
                    if (Math.abs(place.length() - lower.length()) <= 1 && TextUtil.levenshtein(place, lower) == 1) {
                        findings.add("Похоже на опечатку в названии: «" + word + "» вместо «" + place + "»");
                        break;
                    }
                }
            }
        }
        Matcher punct = DOUBLE_PUNCT.matcher(text);
        int punctErrors = 0;
        while (punct.find()) punctErrors++;
        if (punctErrors > 0) findings.add("Небрежная пунктуация (" + punctErrors + ")");
        if (text.length() > 40 && text.equals(text.toLowerCase(Locale.ROOT))) {
            findings.add("Текст набран без заглавных букв");
        }
        int errors = findings.size();
        return new Result(errors, TextUtil.clamp(100 - 15.0 * errors), findings);
    }
}
