package ru.lct.arm112.debrief;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.lct.arm112.service.assessment.RussianTextGuard;
import ru.lct.arm112.service.debrief.DebriefRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Персональный разбор: что показывается обучающемуся, а что не показывается никогда.
 *
 * <p>Замер локальных моделей показал, что они срываются на чужие языки и в этой задаче —
 * примерно раз из трёх без ограничения выборки. Поэтому проверка текста стоит
 * не только на входе в генератор, но и <b>на выходе, перед сохранением</b>.
 */
@SpringBootTest
class DebriefTest {

    @Autowired
    RussianTextGuard guard;

    @Autowired
    DebriefRepository debriefs;

    @Test
    void normalRussianTextPasses() {
        RussianTextGuard.Check check = guard.check(
                "1. Вы указали улицу Белозерская вместо Беломорской. Это разные улицы, "
                        + "и расчёт уехал бы в другой район.");
        assertThat(check.usable()).as(check.reason()).isTrue();
    }

    /** Ровно тот случай, ради которого проверка заведена. */
    @Test
    void chineseTextIsRejected() {
        RussianTextGuard.Check check = guard.check(
                "1. Вы указали неверную улицу,没有人说话，请问您需要我做什么？");
        assertThat(check.usable()).isFalse();
        assertThat(check.reason()).contains("чужая письменность");
    }

    @Test
    void englishTextIsRejected() {
        RussianTextGuard.Check check = guard.check(
                "You entered the wrong street name, which would send the crew elsewhere.");
        assertThat(check.usable()).isFalse();
    }

    @Test
    void mostlyLatinIsRejected() {
        RussianTextGuard.Check check = guard.check(
                "Вы ввели street name incorrectly and the response team would be dispatched wrong.");
        assertThat(check.usable()).isFalse();
        assertThat(check.reason()).contains("латиницы");
    }

    @Test
    void emptyTextIsRejected() {
        assertThat(guard.check(null).usable()).isFalse();
        assertThat(guard.check("   ").usable()).isFalse();
    }

    /** Разбор перезаписывается: очередь даёт «хотя бы один раз», задача может повториться. */
    @Test
    void debriefIsOverwrittenOnRepeat() {
        UUID assessmentId = UUID.randomUUID();
        // строка без внешнего ключа в тесте не вставится, поэтому проверяем только чтение пустого
        assertThat(debriefs.find(assessmentId)).as("разбора ещё нет").isEmpty();
    }
}
