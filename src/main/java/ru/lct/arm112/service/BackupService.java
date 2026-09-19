package ru.lct.arm112.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.BackupInfo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Резервное копирование через JDBC-экспорт всех прикладных таблиц в ZIP с JSON — без pg_dump,
 * одинаково работает в контейнере и в тестах на H2. Ежедневно по cron и по кнопке администратора.
 */
@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    /** Порядок вставки: родители раньше детей. app_user.group_id проставляется после training_group. */
    private static final List<String> TABLES = List.of("app_user", "training_group", "app_setting", "scenario",
            "lesson", "training_session", "assessment", "training_state", "realtime_event", "material", "material_group",
            "audit_event");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path directory;

    public BackupService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                         @Value("${arm112.backup.dir:./backups}") String directory) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.directory = Path.of(directory);
    }

    public List<BackupInfo> list() {
        try {
            if (!Files.isDirectory(directory)) return List.of();
            try (Stream<Path> files = Files.list(directory)) {
                return files.filter(p -> p.getFileName().toString().endsWith(".zip"))
                        .map(p -> {
                            try {
                                return new BackupInfo(p.getFileName().toString(), Files.size(p),
                                        Files.getLastModifiedTime(p).toInstant());
                            } catch (IOException e) {
                                return new BackupInfo(p.getFileName().toString(), 0, Instant.EPOCH);
                            }
                        })
                        .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                        .toList();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось прочитать каталог резервных копий", exception);
        }
    }

    @Scheduled(cron = "${arm112.backup.cron:0 0 2 * * *}")
    public void scheduled() {
        try {
            BackupInfo info = create();
            log.info("Плановая резервная копия: {} ({} байт)", info.fileName(), info.sizeBytes());
        } catch (RuntimeException ex) {
            log.error("Плановая резервная копия не удалась: {}", ex.getMessage());
        }
    }

    public synchronized BackupInfo create() {
        try {
            Files.createDirectories(directory);
            String name = "arm112-" + LocalDateTime.now(ZoneOffset.UTC).format(STAMP) + ".zip";
            Path file = directory.resolve(name);
            try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
                for (String table : TABLES) {
                    zip.putNextEntry(new ZipEntry(table + ".json"));
                    zip.write(objectMapper.writeValueAsBytes(export(table)));
                    zip.closeEntry();
                }
            }
            return new BackupInfo(name, Files.size(file), Files.getLastModifiedTime(file).toInstant());
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось записать резервную копию", exception);
        }
    }

    @Transactional
    public synchronized void restore(String fileName) {
        if (fileName.contains("/") || fileName.contains("\\") || !fileName.endsWith(".zip")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Некорректное имя файла");
        }
        Path file = directory.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Резервная копия не найдена");
        }
        List<ObjectNode> dumps = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                dumps.add((ObjectNode) objectMapper.readTree(zip.readAllBytes()));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось прочитать резервную копию", exception);
        }
        // очистка в обратном порядке; циклическая связь app_user ↔ training_group разрывается через group_id
        jdbc.update("update app_user set group_id = null");
        for (int i = TABLES.size() - 1; i >= 0; i--) {
            jdbc.update("delete from " + TABLES.get(i));
        }
        List<Object[]> deferredGroups = new ArrayList<>();
        for (String table : TABLES) {
            dumps.stream().filter(d -> table.equals(d.path("table").asText())).findFirst()
                    .ifPresent(dump -> importTable(table, dump, deferredGroups));
        }
        // группы пользователей — после того, как training_group восстановлена
        for (Object[] pair : deferredGroups) {
            jdbc.update("update app_user set group_id = ? where id = ?", pair);
        }
        log.warn("База восстановлена из резервной копии {}", fileName);
    }

    private ObjectNode export(String table) {
        ObjectNode dump = objectMapper.createObjectNode();
        dump.put("table", table);
        ArrayNode columns = dump.putArray("columns");
        ArrayNode rows = dump.putArray("rows");
        jdbc.query("select * from " + table, rs -> {
            ResultSetMetaData meta = rs.getMetaData();
            if (columns.isEmpty()) {
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    ObjectNode column = columns.addObject();
                    column.put("name", meta.getColumnName(i).toLowerCase());
                    column.put("cls", "");
                }
            }
            ArrayNode row = rows.addArray();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value != null && ((ObjectNode) columns.get(i - 1)).get("cls").asText().isEmpty()) {
                    // класс значения — надёжнее JDBC-типа: у PostgreSQL и H2 они расходятся для uuid/timestamptz
                    ((ObjectNode) columns.get(i - 1)).put("cls", value.getClass().getName());
                }
                if (value == null) {
                    row.addNull();
                } else if (value instanceof Timestamp ts) {
                    row.add(ts.toInstant().toString());
                } else if (value instanceof java.time.OffsetDateTime odt) {
                    row.add(odt.toInstant().toString());
                } else if (value instanceof Boolean b) {
                    row.add(b);
                } else if (value instanceof Number n) {
                    row.add(n.toString());
                } else {
                    row.add(value.toString());
                }
            }
        });
        return dump;
    }

    private void importTable(String table, ObjectNode dump, List<Object[]> deferredGroups) {
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (JsonNode column : dump.path("columns")) {
            names.add(column.get("name").asText());
            types.add(column.path("cls").asText(""));
        }
        if (names.isEmpty()) return;
        boolean deferGroup = table.equals("app_user") && names.contains("group_id");
        int groupIndex = names.indexOf("group_id");
        String sql = "insert into " + table + " (" + String.join(", ", names) + ") values ("
                + String.join(", ", java.util.Collections.nCopies(names.size(), "?")) + ")";
        for (JsonNode row : dump.path("rows")) {
            Object[] args = new Object[names.size()];
            for (int i = 0; i < names.size(); i++) {
                args[i] = convert(row.get(i), types.get(i));
            }
            if (deferGroup && args[groupIndex] != null) {
                deferredGroups.add(new Object[]{args[groupIndex], args[names.indexOf("id")]});
                args[groupIndex] = null;
            }
            jdbc.update(sql, args);
        }
    }

    private static Object convert(JsonNode value, String cls) {
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return switch (cls) {
            case "java.sql.Timestamp", "java.time.OffsetDateTime", "java.time.Instant" -> Timestamp.from(Instant.parse(text));
            case "java.lang.Boolean" -> value.isBoolean() ? value.asBoolean() : Boolean.parseBoolean(text);
            case "java.lang.Integer", "java.lang.Short" -> Integer.parseInt(text);
            case "java.lang.Long" -> Long.parseLong(text);
            case "java.math.BigDecimal", "java.lang.Double", "java.lang.Float" -> new java.math.BigDecimal(text);
            case "java.util.UUID" -> UUID.fromString(text);
            default -> text;
        };
    }
}
