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
import java.nio.file.StandardCopyOption;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            "lesson", "training_session", "assessment", "assessment_issue", "assessment_card", "assessment_debrief",
            "training_state", "realtime_event",
            "material", "material_group", "audit_event", "job");
    /** Список таблиц копии — открыт для проверки соответствия схеме (см. BackupTablesTest). */
    public static List<String> tables() {
        return TABLES;
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String MATERIALS_PREFIX = "materials/";
    private static final String MATERIALS_MANIFEST = MATERIALS_PREFIX + ".manifest";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path directory;
    private final Path materialsDirectory;

    public BackupService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                         @Value("${arm112.backup.dir:./backups}") String directory,
                         @Value("${arm112.materials.dir:./materials-store}") String materialsDirectory) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.directory = Path.of(directory);
        this.materialsDirectory = Path.of(materialsDirectory);
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
            String name = "arm112-" + LocalDateTime.now(ZoneOffset.UTC).format(STAMP) + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".zip";
            Path file = directory.resolve(name);
            try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
                for (String table : TABLES) {
                    zip.putNextEntry(new ZipEntry(table + ".json"));
                    zip.write(objectMapper.writeValueAsBytes(export(table)));
                    zip.closeEntry();
                }
                appendMaterials(zip);
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
        Map<String, byte[]> materialFiles = new LinkedHashMap<>();
        boolean containsMaterials = false;
        try (InputStream in = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.endsWith(".json") && !entryName.contains("/")) {
                    JsonNode parsed = objectMapper.readTree(zip.readAllBytes());
                    if (!(parsed instanceof ObjectNode dump) || !TABLES.contains(dump.path("table").asText())) {
                        throw new IOException("Некорректный JSON-раздел резервной копии: " + entryName);
                    }
                    dumps.add(dump);
                } else if (entryName.equals(MATERIALS_MANIFEST)) {
                    containsMaterials = true;
                } else if (entryName.startsWith(MATERIALS_PREFIX)) {
                    containsMaterials = true;
                    String storedName = entryName.substring(MATERIALS_PREFIX.length());
                    if (!safeStoredName(storedName)) {
                        throw new IOException("Некорректное имя материала в резервной копии");
                    }
                    materialFiles.put(storedName, zip.readAllBytes());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось прочитать резервную копию", exception);
        }
        for (String table : TABLES) {
            if (dumps.stream().noneMatch(dump -> table.equals(dump.path("table").asText()))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_INVALID",
                        "В резервной копии отсутствует таблица " + table);
            }
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
        if (containsMaterials) {
            restoreMaterials(materialFiles);
        }
        rewriteMaterialPaths();
        // Восстановление меняет состояние учётных записей: все выпущенные ранее JWT должны быть отозваны.
        jdbc.update("update app_user set auth_version = auth_version + 1");
        log.warn("База восстановлена из резервной копии {}", fileName);
    }

    private void appendMaterials(ZipOutputStream zip) throws IOException {
        zip.putNextEntry(new ZipEntry(MATERIALS_MANIFEST));
        zip.closeEntry();
        if (!Files.isDirectory(materialsDirectory)) return;
        try (Stream<Path> files = Files.list(materialsDirectory)) {
            for (Path material : files.filter(Files::isRegularFile).toList()) {
                String storedName = material.getFileName().toString();
                if (!safeStoredName(storedName)) continue;
                zip.putNextEntry(new ZipEntry(MATERIALS_PREFIX + storedName));
                Files.copy(material, zip);
                zip.closeEntry();
            }
        }
    }

    private void restoreMaterials(Map<String, byte[]> materialFiles) {
        Path staging = null;
        try {
            Path targetDirectory = materialsDirectory.toAbsolutePath().normalize();
            Path parent = targetDirectory.getParent();
            if (parent == null) throw new IOException("Не удалось определить каталог материалов");
            Files.createDirectories(parent);
            staging = Files.createTempDirectory(parent, ".arm112-materials-restore-");
            for (Map.Entry<String, byte[]> material : materialFiles.entrySet()) {
                Files.write(staging.resolve(material.getKey()), material.getValue());
            }
            Files.createDirectories(targetDirectory);
            try (Stream<Path> existing = Files.list(targetDirectory)) {
                for (Path path : existing.filter(Files::isRegularFile).toList()) {
                    Files.delete(path);
                }
            }
            try (Stream<Path> restored = Files.list(staging)) {
                for (Path path : restored.filter(Files::isRegularFile).toList()) {
                    Files.move(path, targetDirectory.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось восстановить файлы материалов", exception);
        } finally {
            if (staging != null) {
                try (Stream<Path> leftovers = Files.list(staging)) {
                    for (Path path : leftovers.toList()) Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Временный каталог не влияет на восстановленные данные.
                }
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) {
                    // Неудачная очистка временного каталога не отменяет восстановление.
                }
            }
        }
    }

    private void rewriteMaterialPaths() {
        List<Object[]> materials = jdbc.query("select id, storage_path from material",
                (resultSet, rowNumber) -> new Object[]{resultSet.getObject("id", UUID.class),
                        resultSet.getString("storage_path")});
        for (Object[] material : materials) {
            UUID id = (UUID) material[0];
            String storedName = Path.of((String) material[1]).getFileName().toString();
            jdbc.update("update material set storage_path = ? where id = ?",
                    materialsDirectory.resolve(storedName).toAbsolutePath().normalize().toString(), id);
        }
    }

    private static boolean safeStoredName(String value) {
        return value != null && !value.isBlank() && !value.equals(".") && !value.equals("..")
                && !value.contains("/") && !value.contains("\\");
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
