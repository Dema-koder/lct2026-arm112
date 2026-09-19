package ru.lct.arm112.service.assessment;

import java.util.Locale;

final class TextUtil {
    private TextUtil() {}

    static String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        lower = lower.replaceAll("[«»\"'.,;:()\\[\\]]", " ");
        // (?U): с Java 19 \b без флага не считает кириллицу словом
        lower = lower.replaceAll("(?U)\\b(улица|ул|проспект|пр-т|просп|переулок|пер|шоссе|ш|набережная|наб|площадь|пл|бульвар|б-р|проезд|аллея|дом|д|корпус|корп|к|строение|стр|с)\\b", " ");
        return lower.replaceAll("\\s+", " ").trim();
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    static double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
