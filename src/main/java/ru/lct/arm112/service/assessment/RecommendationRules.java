package ru.lct.arm112.service.assessment;

import ru.lct.arm112.api.ApiModels.AssessmentIssue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Рекомендации по правилам: одна на каждый код замечания, найденный в занятии.
 *
 * <p>Раньше рекомендация была одна на критерий («адрес ниже 100 — сверяйте адрес»), и за
 * опечатку в улице и за пропущенный дом обучающийся получал одну и ту же фразу. Здесь текст
 * привязан к коду из {@code assessment_issue}: он детерминирован, по нему можно считать,
 * что у обучающегося повторяется, и он не зависит от языковой модели. Разбор от модели
 * идёт отдельно и в фоне (service.debrief).
 *
 * <p>Порядок — сначала критичное, затем чаще встречающееся: обучающийся читает сверху вниз.
 */
public final class RecommendationRules {
    private RecommendationRules() {}

    private static final Map<String, String> TEXT = Map.ofEntries(
            // заполнение карточки оператором 112
            Map.entry("ADDRESS_STREET_MISMATCH", "Опечатка в названии улицы: службы уедут не туда. Сверяйте написание с подсказкой справочника улиц."),
            Map.entry("ADDRESS_STREET_WRONG", "Указана другая улица. Переспросите заявителя и записывайте улицу из уточнённого адреса, а не из ориентира."),
            Map.entry("ADDRESS_STREET_MISSING", "Улица не записана. Уточните адрес у заявителя: без улицы карточку не отработать."),
            Map.entry("ADDRESS_HOUSE_MISSING", "Не указан номер дома. Уточните его — без дома службы ищут место по всей улице."),
            Map.entry("ADDRESS_HOUSE_MISMATCH", "Номер дома не совпадает с уточнённым. Переспрашивайте литеры и корпуса."),
            Map.entry("ADDRESS_LOCALITY_MISMATCH", "Не тот населённый пункт. Для области начинайте адрес с города или округа."),
            Map.entry("ADDRESS_DETAIL_MISMATCH", "Корпус, строение или квартира записаны неверно — сверяйте детали адреса."),
            Map.entry("INCIDENT_TYPE_MISMATCH", "Тип происшествия определяет, какие службы оповещаются. Начинайте с верной позиции «Что случилось?» и отвечайте на вопросы опросной карты по словам заявителя."),
            Map.entry("SERVICE_MISSING", "Не оповещены службы: %s. Не удаляйте службы, подобранные по классификатору, и добавляйте нужные по обстановке."),
            Map.entry("PROCESSING_OVERDUE", "Карточка заполнялась дольше 3 минут. Сначала адрес и тип, подробности — в описание."),
            Map.entry("CARD_NOT_SAVED", "Карточка не сохранена — вызов остался без реагирования. Сохраняйте карточку, даже если часть данных ещё уточняется."),
            Map.entry("CALL_MISSED", "Входящий вызов не принят вовремя. Отвечайте сразу — заявитель ждёт не дольше 30 секунд."),
            Map.entry("CALL_LOST", "Вызов потерян: заявитель не дозвонился. Принимайте вызов, даже если заполняете другую карточку."),
            Map.entry("LANGUAGE", "Проверяйте текст перед сохранением: его читают диспетчеры служб, опечатки и сокращения мешают понять обстановку."),
            // действия диспетчера службы
            Map.entry("ACCEPTANCE_OVERDUE", "Открывайте карточку сразу при появлении в журнале: норматив 30 секунд считается с момента поступления."),
            Map.entry("CARD_NOT_FINISHED", "Карточка не доведена до завершения. Проставляйте статусы до «Завершено» или передавайте с причиной."),
            Map.entry("REFUSED_PROFILE_INCIDENT", "Отказ от происшествия своей службы. Перед отказом проверяйте компетенцию по классификатору."),
            Map.entry("ACCEPTED_FOREIGN_INCIDENT", "Принято происшествие не своей службы. Если тип вне компетенции, отклоняйте карточку с причиной."),
            Map.entry("DECLINE_CORRECTED", "Ошибочный отказ исправлен статусом «Принята» — верно, но с потерей времени. Проверяйте компетенцию до отказа."),
            Map.entry("REFUSE_WITHOUT_RESPONSE", "Отказ от работ без выезда. Такой отказ требует основания в комментарии."),
            Map.entry("CLICK_THROUGH", "Статусы проставлены подряд без паузы. Читайте карточку и ставьте статус по факту действия."),
            Map.entry("COMMENT_INCOMPLETE", "Комментарий к статусу должен содержать основание и результат: кому передано, что сделано."),
            Map.entry("CALL_MISSING", "Не выполнен обязательный доклад руководителю. Докладывайте по регламенту службы."));

    private static int rank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> 0;
            case "WARNING" -> 1;
            default -> 2;
        };
    }

    /**
     * @param serviceLabel подпись службы по коду — для рекомендации о неоповещённых службах
     */
    public static List<String> build(List<AssessmentIssue> issues, Function<String, String> serviceLabel) {
        record Group(String code, int rank, Set<Object> cards, Set<String> services, int order) {}
        Map<String, Group> groups = new LinkedHashMap<>();
        for (AssessmentIssue issue : issues) {
            if (!TEXT.containsKey(issue.code())) continue;
            Group group = groups.computeIfAbsent(issue.code(), code -> new Group(code, rank(issue.severity()),
                    new LinkedHashSet<>(), new LinkedHashSet<>(), groups.size()));
            group.cards().add(issue.cardId() == null ? "session" : issue.cardId());
            if ("SERVICE_MISSING".equals(issue.code()) && issue.expected() instanceof String code) {
                group.services().add(serviceLabel.apply(code));
            }
        }
        List<Group> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingInt(Group::rank)
                .thenComparing(Comparator.comparingInt((Group g) -> g.cards().size()).reversed())
                .thenComparingInt(Group::order));

        List<String> result = new ArrayList<>();
        for (Group group : ordered) {
            String text = TEXT.get(group.code());
            if (group.code().equals("SERVICE_MISSING")) text = text.formatted(String.join(", ", group.services()));
            int cards = (int) group.cards().stream().filter(c -> !"session".equals(c)).count();
            result.add(cards > 1 ? text + " (карточек: " + cards + ")" : text);
        }
        return result;
    }
}
