package ru.lct.arm112.service.assessment;

import org.languagetool.JLanguageTool;
import org.languagetool.language.Russian;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверка грамотности описаний и комментариев (задача D1 плана).
 *
 * <p>Раньше здесь было пять эвристик, которые ловили признаки небрежного набора, но не
 * орфографию и не согласование. Теперь основная работа у LanguageTool — локальной
 * библиотеки без обращений в сеть, — а собственные проверки оставлены там, где они
 * специфичны для задачи и библиотека их не делает.
 *
 * <h2>Почему не все правила подряд</h2>
 * Комментарий диспетчера — это не литературный текст: телеграфный стиль, сокращения,
 * отсутствие подлежащего. Правила категорий «типографика» и «стиль» на нём срабатывают
 * постоянно, и обучающийся быстро перестаёт верить замечаниям. Поэтому включена
 * орфография и грамматика, а придирки к стилю выключены: по
 * [METRICS.md §5.3](../../../../../../docs/assessment/METRICS.md) точность важнее полноты —
 * ложное срабатывание дороже пропуска.
 *
 * <h2>Отказоустойчивость</h2>
 * Если библиотека не поднялась, оценка не падает: {@link #check} возвращается к прежним
 * эвристикам. Оценка обязана считаться и без языковой модели.
 */
@Component
public class LanguageChecker {
    private static final Logger log = LoggerFactory.getLogger(LanguageChecker.class);

    private static final Pattern WORD = Pattern.compile("[A-Za-zА-Яа-яЁё\\-]{2,}");
    private static final Pattern MIXED_ALPHABET = Pattern.compile("(?=.*[A-Za-z])(?=.*[А-Яа-яЁё]).+");
    private static final Pattern TRIPLE_LETTER = Pattern.compile("(.)\\1\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOUBLE_PUNCT = Pattern.compile("[,.!?;:]{2,}|\\s[,.;:]");

    /** Категории, чьи правила на телеграфном стиле дают больше шума, чем пользы. */
    private static final Set<String> MUTED_CATEGORIES = Set.of("TYPOGRAPHY", "STYLE", "REDUNDANCY");

    /** Штраф за одну находку; шкала осталась прежней, чтобы старые результаты были сравнимы. */
    private static final double PENALTY = 15.0;

    /** Сколько проверок грамотности может идти одновременно. */
    private static final int POOL_SIZE = Integer.getInteger("arm112.language.pool",
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 3)));
    // Замер: увеличение пула с 4 до 8 дало 2262 мс против 2474 по p95 — в пределах
    // разброса, а память стоит. Узкое место не здесь.
    /**
     * Сколько ждать свободного проверяющего.
     *
     * <p>Порог щедрый и в норме не срабатывает: пул готов до первого запроса, а сама
     * проверка стоит около тридцати миллисекунд. Он оставлен как предохранитель от
     * зависания — оценка обязана посчитаться, даже если библиотека повела себя странно.
     * Срабатывание порога означает, что балл за грамотность занижен, поэтому оно
     * пишется в журнал предупреждением, а не молча.
     */
    private static final long BORROW_TIMEOUT_MS = 10_000;

    /**
     * Названия улиц и населённых пунктов из всех сценариев — ловим «Дубнинская / Дубининская».
     * Ключ нормализован для сравнения, значение — исходное написание: в замечании об ошибке
     * в названии показывать его строчными было бы странно.
     */
    private final java.util.Map<String, String> knownPlaces = new java.util.concurrent.ConcurrentHashMap<>();
    /** Слова, которые нельзя считать ошибкой: топонимы, сокращения служб, термины памятки ДДС. */
    private final Set<String> allowList = new CopyOnWriteArraySet<>(List.of(
            "ддс", "екп", "цодд", "мчс", "гибдд", "дпс", "оатн", "цэмп", "арм",
            "мосгортранс", "мослифт", "мосбез", "оив", "тинао", "пов"));

    /**
     * Пул проверяющих, а не одна инстанция на всё приложение.
     *
     * <p>{@code JLanguageTool} не потокобезопасен. С одной общей инстанцией тридцать
     * одновременных сохранений выстраиваются в очередь на ней: нагрузочный профиль
     * показал p95 в 4.3 секунды при нормативе в две.
     *
     * <p>Пул ограничен: словари в LanguageTool кэшируются статически и делятся между
     * инстанциями, но каждая всё же стоит памяти, а на CPU-сервере одновременных
     * проверок всё равно не может быть больше, чем ядер.
     */
    private final java.util.concurrent.BlockingQueue<JLanguageTool> pool =
            new java.util.concurrent.ArrayBlockingQueue<>(POOL_SIZE);
    private volatile boolean initialised;
    private volatile boolean available;

    /**
     * Словари загружаются при запуске приложения, а не при первой проверке.
     *
     * <p>Создание проверяющих стоит около восьми секунд. Без этого цену платил первый
     * обучающийся, завершивший занятие: нагрузочный профиль показал у него 5 секунд
     * на сохранении при 31 миллисекунде у остальных.
     *
     * <p><b>Почему синхронно, а не в фоне.</b> Фоновый прогрев соблазнителен — запуск
     * быстрее, — но тогда занятия, завершившиеся до его окончания, остались бы без
     * проверки грамотности. Балл зависел бы от того, насколько рано начали занятие,
     * и двое за одинаковую работу получили бы разное. Для оценивания это недопустимо.
     *
     * <p>Цена — около восьми секунд к запуску. Система поднимается раз в учебный день.
     */
    @jakarta.annotation.PostConstruct
    void loadDictionaries() {
        long startedAt = System.nanoTime();
        if (ensurePool()) {
            log.info("Проверка грамотности готова за {} мс", (System.nanoTime() - startedAt) / 1_000_000);
        }
    }

    public void learnPlaces(Collection<String> names) {
        for (String name : names) {
            if (name == null) continue;
            for (String token : TextUtil.normalize(name).split(" ")) {
                if (token.length() >= 5) knownPlaces.putIfAbsent(token, original(name, token));
                if (token.length() >= 3) allowList.add(token);
            }
        }
    }

    /** Исходное написание токена в названии: ищем его в имени без учёта регистра. */
    private static String original(String name, String normalizedToken) {
        for (String part : name.split("[\s,.]+")) {
            String normalized = TextUtil.normalize(part);
            if (normalized.equals(normalizedToken)) return part;
        }
        return normalizedToken;
    }

    public record Result(int errors, double score, List<String> findings) {}

    public Result check(String text) {
        if (text == null || text.isBlank()) {
            return new Result(0, 100, List.of());
        }
        boolean libraryActive = ensurePool();
        // Свои проверки идут первыми и забирают слова, о которых умеют сказать точнее
        // библиотеки: «смешаны латиница и кириллица» понятнее абзаца про кодировку,
        // а «похоже на опечатку в названии, вместо Берзарина» словарь сказать не может.
        Set<String> covered = new LinkedHashSet<>();
        Set<String> findings = new LinkedHashSet<>(ownChecks(text, libraryActive, covered));
        findings.addAll(libraryChecks(text, covered));
        int errors = findings.size();
        return new Result(errors, TextUtil.clamp(100 - PENALTY * errors), List.copyOf(findings));
    }

    /**
     * Проверки, которых нет в библиотеке: они специфичны для карточки происшествия.
     *
     * <p>Главная — похожее написание известного топонима. Для словаря это просто незнакомое
     * слово, а для оператора 112 это ошибка, из-за которой расчёт уедет не туда.
     */
    private List<String> ownChecks(String text, boolean libraryActive, Set<String> covered) {
        List<String> findings = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            if (MIXED_ALPHABET.matcher(word).matches()) {
                findings.add("Смешаны латиница и кириллица: «" + word + "»");
                covered.add(word);
                continue;
            }
            if (TRIPLE_LETTER.matcher(word).find()) {
                findings.add("Тройная буква: «" + word + "»");
                covered.add(word);
                continue;
            }
            String lower = word.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (lower.length() >= 5 && !knownPlaces.containsKey(lower)) {
                for (String place : knownPlaces.keySet()) {
                    if (Math.abs(place.length() - lower.length()) <= 1 && TextUtil.levenshtein(place, lower) == 1) {
                        findings.add("Похоже на опечатку в названии: «" + word
                                + "» вместо «" + knownPlaces.get(place) + "»");
                        covered.add(word);
                        break;
                    }
                }
            }
        }
        // Пунктуацию и заглавные библиотека разбирает точнее и адресно — с указанием
        // конкретного места и замены. Свои проверки остаются только на случай, когда
        // библиотека недоступна: иначе одна и та же небрежность штрафуется дважды.
        if (!libraryActive) {
            Matcher punct = DOUBLE_PUNCT.matcher(text);
            int punctErrors = 0;
            while (punct.find()) punctErrors++;
            if (punctErrors > 0) findings.add("Небрежная пунктуация (" + punctErrors + ")");
            if (text.length() > 40 && text.equals(text.toLowerCase(Locale.ROOT))) {
                findings.add("Текст набран без заглавных букв");
            }
        }
        return findings;
    }

    /** Орфография и грамматика от LanguageTool; при недоступности библиотеки — пусто. */
    private List<String> libraryChecks(String text, Set<String> covered) {
        if (!ensurePool()) return List.of();
        JLanguageTool languageTool = null;
        try {
            languageTool = pool.poll(BORROW_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (languageTool == null) {
                log.warn("Проверка грамотности пропущена: все проверяющие заняты дольше {} мс — балл за грамотность занижен", BORROW_TIMEOUT_MS);
                return List.of();
            }
            List<String> findings = new ArrayList<>();
            for (RuleMatch match : languageTool.check(text)) {
                String word = text.substring(match.getFromPos(), match.getToPos());
                if (allowList.contains(word.toLowerCase(Locale.ROOT).replace('ё', 'е'))) continue;
                if (covered.contains(word)) continue;
                String replacement = match.getSuggestedReplacements().isEmpty() ? null
                        : match.getSuggestedReplacements().get(0);
                findings.add(replacement == null
                        ? shorten(match.getMessage()) + ": «" + word + "»"
                        : "«" + word + "» — возможно, «" + replacement + "»");
            }
            return findings;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (IOException | RuntimeException exception) {
            // грамотность — обогащение, а не критичный путь: оценка обязана посчитаться
            log.warn("Проверка грамотности недоступна: {}", exception.getMessage());
            return List.of();
        } finally {
            if (languageTool != null) pool.offer(languageTool);
        }
    }

    /** Сообщения библиотеки бывают в абзац; обучающемуся нужна первая фраза. */
    private static String shorten(String message) {
        int dot = message.indexOf(". ");
        String first = dot > 0 ? message.substring(0, dot) : message;
        return first.length() > 160 ? first.substring(0, 157) + "…" : first;
    }

    /** Создание проверяющих: тянет словари и стоит секунды, поэтому делается один раз. */
    private boolean ensurePool() {
        if (initialised) return available;
        synchronized (this) {
            if (initialised) return available;
            initialised = true;
            try {
                for (int i = 0; i < POOL_SIZE; i++) {
                    pool.offer(build());
                }
                available = true;
                log.info("Проверка грамотности: LanguageTool, проверяющих в пуле {}", POOL_SIZE);
            } catch (RuntimeException exception) {
                log.warn("LanguageTool не поднялся, остаются собственные проверки: {}", exception.getMessage());
                available = false;
            }
            return available;
        }
    }

    private static JLanguageTool build() {
        JLanguageTool created = new JLanguageTool(new Russian());
        for (Rule rule : created.getAllActiveRules()) {
            if (MUTED_CATEGORIES.contains(rule.getCategory().getId().toString())) {
                created.disableRule(rule.getId());
            }
        }
        return created;
    }
}
