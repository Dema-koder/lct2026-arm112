package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.lct.arm112.service.BackupService;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Список таблиц для резервной копии должен совпадать со схемой.
 *
 * <p>Эта проверка появилась после двух одинаковых поломок: миграции {@code V9__assessment_issue}
 * и {@code V10__assessment_card} добавляли таблицу с внешним ключом на {@code assessment},
 * но не в {@link BackupService}. Восстановление падало на удалении {@code assessment} —
 * внешний ключ не давал его очистить. Оба раза ошибка ловилась только полным прогоном
 * интеграционных тестов, то есть дорого и поздно.
 */
@SpringBootTest
class BackupTablesTest {

    /** Служебные таблицы, которые в резервную копию не входят по замыслу. */
    private static final Set<String> EXCLUDED = Set.of("flyway_schema_history");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void backupCoversEveryApplicationTable() {
        List<String> schema = jdbc.queryForList("""
                select table_name from information_schema.tables
                 where table_schema = schema() and table_type = 'BASE TABLE'
                """, String.class);

        Set<String> actual = schema.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(name -> !EXCLUDED.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> declared = new TreeSet<>(BackupService.tables());

        assertThat(actual)
                .as("таблицы в схеме, которых нет в BackupService.TABLES — восстановление из копии сломается")
                .isEqualTo(declared);
    }
}
