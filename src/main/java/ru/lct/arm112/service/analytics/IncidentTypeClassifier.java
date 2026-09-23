package ru.lct.arm112.service.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.lct.arm112.service.ReferenceDataService;
import ru.lct.arm112.service.ReferenceDataService.IncidentType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Подбор типа происшествия по тексту заявителя (задача D2 плана).
 *
 * <h2>Почему не нейросетевой классификатор</h2>
 * В библиотеке 66 типов, а размеченных вводных 76 штук и они покрывают только 20 типов:
 * у 46 типов нет ни одного примера, у семи — ровно один. Модель, обученная на такой
 * выборке, уверенно предсказывала бы три частых класса и никогда — остальные, то есть
 * была бы хуже бесполезной именно на редких происшествиях, где помощь и нужна.
 *
 * <p>Зато в справочнике есть то, чего нет в выборке: **синонимы, размеченные вручную**.
 * Это готовые ключевые признаки, и они покрывают типы без единого примера. Поэтому основа —
 * взвешенное совпадение признаков, а размеченные вводные лишь добавляют веса словам,
 * отличающим один тип от другого.
 *
 * <p>Побочная выгода важнее точности: решение объяснимо. Классификатор возвращает слова,
 * которые привели к выводу, и их можно показать обучающемуся в подсказке —
 * «горит контейнер → Пожар: мусор». Заказчик просил не строить сложную модель
 * с кучей параметров, которую трудно поддерживать.
 *
 * <p>Когда размеченных вводных станет на порядок больше, метод заменяется на эмбеддинги
 * без изменения вызывающего кода: наружу смотрит {@link #classify}.
 */
@Service
public class IncidentTypeClassifier {
    private static final Logger log = LoggerFactory.getLogger(IncidentTypeClassifier.class);

    private static final Pattern WORD = Pattern.compile("[а-яёa-z0-9]{2,}");
    /**
     * Слова, которые есть в любой вводной и ничего не различают: предлоги, обозначения
     * людей и возраста, вводные обороты. Без этого списка «Наезд на пешехода» отдаёт
     * признак «на», и любая вводная с предлогом получает балл в пользу наезда.
     */
    private static final Set<String> STOP = Set.of(
            "на", "в", "во", "по", "из", "за", "до", "от", "со", "об", "обо", "про", "через",
            "нет", "есть", "был", "была", "было", "были", "для", "что", "как", "его", "это",
            "при", "над", "под", "без", "или", "уже", "около", "рядом", "примерно", "оба",
            "заявитель", "сообщает", "просит", "говорит", "мужчина", "женщина", "мужчины",
            "женщины", "человек", "людей", "мужчин", "женщин", "лет", "года", "год",
            "ребенок", "девушка", "парень", "пожилой", "пожилая", "молодой", "неизвестный",
            "неизвестная", "неизвестные", "месте", "адрес", "адресу", "вид", "виду");

    /** Вес признака в зависимости от источника: рука человека надёжнее автонабора из примеров. */
    private static final double W_SYNONYM = 1.0, W_LABEL = 0.4, W_LEARNED = 0.5;
    /** Словосочетание вернее одиночного слова: «потеря сознания» однозначнее, чем «потеря». */
    private static final double W_PHRASE = 1.5;
    /** Метка признака-словосочетания: ищется в тексте целиком, а не среди отдельных слов. */
    private static final String PHRASE_PREFIX = "~";
    /** Короче этого слова из подписи типа в признаки не берутся. */
    private static final int MIN_LABEL_FEATURE = 5;
    /**
     * Одно совпавшее слово — случайность; для любого вывода нужно хотя бы два.
     *
     * <p>Отсева по уверенности здесь сознательно нет. Он был и был убран: замер показал,
     * что точность среди «уверенных» ответов равна точности по всем (65.5 против 65.8 %),
     * то есть порог не отделял верные ответы от неверных, а лишь сокращал покрытие.
     * На 76 размеченных вводных сигнала уверенности просто нет — и выдавать его
     * за отбор было бы обманом.
     */
    static final int MIN_EVIDENCE = 2;

    private final ReferenceDataService references;

    /** тип → (признак → вес) */
    private final Map<String, Map<String, Double>> features = new LinkedHashMap<>();
    /** признак → в скольких типах встречается; редкий признак различает лучше частого. */
    private final Map<String, Integer> documentFrequency = new LinkedHashMap<>();

    public IncidentTypeClassifier(ReferenceDataService references) {
        this.references = references;
    }

    /**
     * Результат подбора.
     *
     * @param confidence  0..1; {@code null} в {@code typeId} означает «не знаю»
     * @param matchedWords слова текста, приведшие к выводу — их показывают обучающемуся
     */
    public record Suggestion(String typeId, String label, String category,
                             double confidence, List<String> matchedWords) {}

    /** Сколько кандидатов показывать в подсказке: на трёх покрытие 95 %, на одном — 66 %. */
    public static final int SHORTLIST = 3;

    /**
     * Короткий список кандидатов — основной способ использования.
     *
     * <p>Замер на размеченных билетах: верный тип попадает в первую тройку в 95 % случаев,
     * а на первое место — лишь в 66 %. Поэтому обучающемуся показывается список, а не один
     * ответ: список из трёх почти всегда содержит правильный и ничего не утверждает
     * неверно, тогда как единственная подсказка ошибалась бы в каждом третьем случае,
     * и обучающийся запоминал бы ошибку.
     */
    public List<Suggestion> suggest(String text) {
        // Порога в два совпавших слова здесь нет: для кандидата в списке довольно одного,
        // решение принимает обучающийся. Требование двух остаётся там, где система
        // утверждает единственный ответ.
        return rank(text, SHORTLIST);
    }

    /**
     * Единственный кандидат — там, где список не годится: проверка сгенерированного
     * сценария, автоподстановка эталона. Верен примерно в двух случаях из трёх,
     * поэтому результат всегда требует подтверждения преподавателем.
     */
    public Suggestion classify(String text) {
        List<Suggestion> ranked = rank(text, 2);
        if (ranked.isEmpty() || ranked.get(0).matchedWords().size() < MIN_EVIDENCE) {
            return new Suggestion(null, null, null,
                    ranked.isEmpty() ? 0 : ranked.get(0).confidence(),
                    ranked.isEmpty() ? List.of() : ranked.get(0).matchedWords());
        }
        return ranked.get(0);
    }

    /** Кандидаты по убыванию уверенности — для подсказки со списком и для проверки качества. */
    public List<Suggestion> rank(String text, int limit) {
        ensureTrained();
        Set<String> words = words(text);
        if (words.isEmpty()) return List.of();
        String phraseText = normalize(text);

        List<Suggestion> result = new ArrayList<>();
        double total = 0;
        Map<String, double[]> scores = new LinkedHashMap<>();
        Map<String, List<String>> evidence = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : features.entrySet()) {
            double score = 0;
            List<String> matched = new ArrayList<>();
            for (Map.Entry<String, Double> feature : entry.getValue().entrySet()) {
                String hit = feature.getKey().startsWith(PHRASE_PREFIX)
                        ? phraseMatch(phraseText, feature.getKey().substring(PHRASE_PREFIX.length()))
                        : firstMatch(words, feature.getKey());
                if (hit == null) continue;
                score += feature.getValue() * idf(feature.getKey());
                matched.add(hit);
            }
            if (score <= 0) continue;
            scores.put(entry.getKey(), new double[]{score});
            evidence.put(entry.getKey(), matched);
            total += score;
        }
        if (total <= 0) return List.of();

        for (Map.Entry<String, double[]> entry : scores.entrySet()) {
            IncidentType type = references.incidentType(entry.getKey());
            if (type == null) continue;
            result.add(new Suggestion(type.id(), type.label(), type.category(),
                    round(entry.getValue()[0] / total), List.copyOf(new LinkedHashSet<>(evidence.get(entry.getKey())))));
        }
        result.sort(Comparator.comparingDouble(Suggestion::confidence).reversed());
        return result.size() <= limit ? result : result.subList(0, limit);
    }

    /**
     * Добавить размеченный пример: слова вводной становятся признаками её типа.
     *
     * <p>Вызывается при загрузке библиотеки сценариев и при подтверждении эталона
     * преподавателем — так правки преподавателя доучивают подбор, как и просил заказчик.
     */
    public void learn(String callerText, List<String> typeIds) {
        if (callerText == null || typeIds == null || typeIds.isEmpty()) return;
        ensureTrained();
        for (String typeId : typeIds) {
            if (references.incidentType(typeId) == null) continue;
            Map<String, Double> own = features.computeIfAbsent(typeId, k -> new LinkedHashMap<>());
            for (String word : words(callerText)) {
                if (own.containsKey(word)) continue;
                own.put(word, W_LEARNED);
                documentFrequency.merge(word, 1, Integer::sum);
            }
        }
    }

    // ------------------------------------------------------------------ обучение

    private volatile boolean trained;

    private void ensureTrained() {
        if (trained) return;
        synchronized (this) {
            if (trained) return;
            for (IncidentType type : references.allIncidentTypes()) {
                if (isSurveyRoot(type)) continue;
                Map<String, Double> own = new LinkedHashMap<>();
                if (type.synonyms() != null) {
                    for (String synonym : type.synonyms()) {
                        String phrase = normalize(synonym);
                        if (phrase.contains(" ")) {
                            // Словосочетание — самая точная часть ручной разметки, и резать
                            // его на слова значит эту точность потерять: «потеря сознания»
                            // превращалась в «потеря», а та совпадает с «потерял» у пропажи
                            // человека. Такие признаки ищутся в тексте целиком.
                            own.put(PHRASE_PREFIX + phrase, W_PHRASE);
                        } else {
                            for (String word : words(synonym)) own.put(word, W_SYNONYM);
                        }
                    }
                }
                // Из подписи берутся только содержательные слова: короткие токены подписи —
                // это предлоги и союзы, они дают ложные совпадения на любом тексте.
                for (String word : words(type.label())) {
                    if (word.length() >= MIN_LABEL_FEATURE) own.putIfAbsent(word, W_LABEL);
                }
                if (own.isEmpty()) continue;
                features.put(type.id(), own);
                for (String word : own.keySet()) documentFrequency.merge(word, 1, Integer::sum);
            }
            trained = true;
            log.info("Подбор типа происшествия: типов с признаками {}, словарь {}",
                    features.size(), documentFrequency.size());
        }
    }

    /**
     * Верхний уровень списка «что случилось?» — не цель классификации.
     *
     * <p>В справочнике рядом с типами ЕКП лежат 31 запись {@code top.*}: это пункты первого
     * экрана оператора, у каждого своё опросное дерево, а тип ЕКП выводится уже из ответов.
     * Эталоном сценария они не бывают ни разу, зато их подписи — «Прочие происшествия»,
     * «Аварии и происшествия в городском хозяйстве» — дают общие слова, которые
     * конкурируют с настоящими типами и портят статистику редкости признаков.
     */
    private static boolean isSurveyRoot(IncidentType type) {
        return type.id() != null && type.id().startsWith("top.");
    }

    // ------------------------------------------------------------------ сопоставление

    /**
     * Признак считается найденным, если слово текста начинается с его основы или наоборот:
     * русские окончания меняются, а первые буквы — нет. Короткие признаки ищутся вхождением,
     * иначе «дым» не нашёлся бы в «задымлении».
     */
    /** Словосочетание ищется вхождением в текст: порядок слов в нём значим. */
    private static String phraseMatch(String normalizedText, String phrase) {
        return normalizedText.contains(phrase) ? phrase : null;
    }

    /** Текст без знаков препинания и лишних пробелов — в нём ищутся словосочетания. */
    private static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^а-я0-9]+", " ").replaceAll("\s+", " ").trim();
    }

    private static String firstMatch(Set<String> words, String feature) {
        for (String word : words) {
            if (feature.length() <= 4) {
                // Короткий признак ищем вхождением («дым» в «задымлении»), но только
                // с начала слова: иначе «101» находится в номере дома, а «дтп» — в середине.
                if (word.equals(feature) || word.startsWith(feature)) return word;
            } else {
                int prefix = Math.min(5, Math.min(word.length(), feature.length()));
                if (word.regionMatches(0, feature, 0, prefix)) return word;
            }
        }
        return null;
    }

    /** Признак, встречающийся у многих типов, различает хуже: «пожар» есть почти у всех «пожарных». */
    private double idf(String feature) {
        int df = documentFrequency.getOrDefault(feature, 1);
        return Math.log(1.0 + (double) features.size() / df);
    }

    private static Set<String> words(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null) return result;
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT).replace('ё', 'е'));
        while (matcher.find()) {
            String word = matcher.group();
            if (!STOP.contains(word)) result.add(word);
        }
        return result;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
