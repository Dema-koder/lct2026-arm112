package ru.lct.arm112.api;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.arm112.persistence.MaterialRepository;
import ru.lct.arm112.persistence.MaterialRepository.MaterialRow;
import ru.lct.arm112.security.CurrentUser;
import ru.lct.arm112.service.LessonService;
import ru.lct.arm112.service.ScenarioService;
import ru.lct.arm112.service.TrainingEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static ru.lct.arm112.api.ApiModels.*;

/** Рабочее место преподавателя. Всё — только в рамках своих занятий и групп (решение №8). */
@RestController
@RequestMapping("/api/v1/teacher")
@PreAuthorize("hasRole('TEACHER')")
public class TeacherController {
    private final LessonService lessons;
    private final ScenarioService scenarios;
    private final TrainingEngine engine;
    private final MaterialRepository materials;
    private final Path materialsDir;

    public TeacherController(LessonService lessons, ScenarioService scenarios, TrainingEngine engine,
                             MaterialRepository materials,
                             @Value("${arm112.materials.dir:./materials-store}") String materialsDir) {
        this.lessons = lessons;
        this.scenarios = scenarios;
        this.engine = engine;
        this.materials = materials;
        this.materialsDir = Path.of(materialsDir);
    }

    // ---------------------------------------------------------------- scenarios

    @GetMapping("/scenarios")
    public List<ScenarioListItem> scenarios(@RequestParam(required = false) String category,
                                            @RequestParam(required = false) String source,
                                            @RequestParam(required = false) Boolean confirmed) {
        return scenarios.list(category, source, confirmed);
    }

    @GetMapping("/scenarios/{id}")
    public Scenario scenario(@PathVariable String id) {
        return scenarios.require(id);
    }

    @PostMapping("/scenarios")
    @ResponseStatus(HttpStatus.CREATED)
    public Scenario createScenario(@Valid @RequestBody ScenarioUpsert request, CurrentUser actor) {
        return scenarios.create(request, actor.id());
    }

    @PutMapping("/scenarios/{id}")
    public Scenario updateScenario(@PathVariable String id, @Valid @RequestBody ScenarioUpsert request, CurrentUser actor) {
        return scenarios.update(id, request, actor.id());
    }

    @PostMapping("/scenarios/{id}/confirm-reference")
    public Scenario confirmReference(@PathVariable String id, CurrentUser actor) {
        return scenarios.confirm(id, actor.id());
    }

    @PostMapping("/scenarios/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Scenario> generate(@Valid @RequestBody GenerateRequest request, CurrentUser actor) {
        return scenarios.generate(request, actor.id());
    }

    // ---------------------------------------------------------------- groups

    @GetMapping("/groups")
    public List<Group> groups(CurrentUser actor) {
        return lessons.groups(actor);
    }

    // ---------------------------------------------------------------- lessons

    @GetMapping("/lessons")
    public List<Lesson> lessonList(@RequestParam(required = false) String state, CurrentUser actor) {
        return lessons.list(actor, state);
    }

    @PostMapping("/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public Lesson createLesson(@Valid @RequestBody LessonCreate request, CurrentUser actor) {
        return lessons.create(request, actor);
    }

    @GetMapping("/lessons/{id}")
    public Lesson lesson(@PathVariable UUID id, CurrentUser actor) {
        return lessons.require(id, actor);
    }

    @PostMapping("/lessons/{id}/start")
    public Lesson start(@PathVariable UUID id, CurrentUser actor) {
        return lessons.start(id, actor);
    }

    @PostMapping("/lessons/{id}/complete")
    public Lesson complete(@PathVariable UUID id, CurrentUser actor) {
        return lessons.complete(id, actor);
    }

    @PostMapping("/lessons/{id}/publish")
    public Lesson publish(@PathVariable UUID id, CurrentUser actor) {
        return lessons.publish(id, actor);
    }

    @GetMapping("/lessons/{id}/monitor")
    public LessonMonitor monitor(@PathVariable UUID id, CurrentUser actor) {
        return lessons.monitor(id, actor);
    }

    @GetMapping("/lessons/{id}/report")
    public LessonReport report(@PathVariable UUID id, CurrentUser actor) {
        return lessons.report(id, actor);
    }

    @GetMapping(value = "/lessons/{id}/report.csv", produces = "text/csv")
    public ResponseEntity<byte[]> reportCsv(@PathVariable UUID id, CurrentUser actor) {
        byte[] body = lessons.reportCsv(id, actor).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lesson-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(body);
    }

    // ---------------------------------------------------------------- sessions / assessment

    @GetMapping("/sessions/{sessionId}")
    public SessionDetail session(@PathVariable UUID sessionId, CurrentUser actor) {
        return engine.detail(sessionId, actor);
    }

    @PutMapping("/sessions/{sessionId}/assessment")
    public Assessment assess(@PathVariable UUID sessionId, @Valid @RequestBody TeacherAssessment request, CurrentUser actor) {
        return lessons.assess(sessionId, request, actor);
    }

    // ---------------------------------------------------------------- materials

    @GetMapping("/materials")
    public List<Material> materialList(CurrentUser actor) {
        return materials.findByTeacher(actor.id()).stream().map(this::toMaterial).toList();
    }

    @PostMapping(value = "/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Material upload(@RequestParam("file") MultipartFile file,
                           @RequestParam("title") String title,
                           @RequestParam(value = "groupIds", required = false) List<UUID> groupIds,
                           CurrentUser actor) throws IOException {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Файл пустой");
        }
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Название материала должно содержать от 1 до 200 символов");
        }
        List<UUID> assignedGroups = groupIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(groupIds));
        for (UUID groupId : assignedGroups) {
            Group group = lessons.group(groupId);
            if (!group.teacherId().equals(actor.id())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Нельзя назначить материал чужой группе");
            }
        }
        Path storageRoot = materialsDir.toAbsolutePath().normalize();
        Files.createDirectories(storageRoot);
        UUID id = UUID.randomUUID();
        String original = file.getOriginalFilename() == null ? "material" : Path.of(file.getOriginalFilename()).getFileName().toString();
        Path target = storageRoot.resolve(id + "-" + original.replaceAll("[^\\p{L}\\p{N}._-]", "_")).normalize();
        file.transferTo(target);
        MaterialRow row = new MaterialRow(id, actor.id(), title.trim(), original,
                file.getContentType(), file.getSize(), target.toString(), Instant.now());
        try {
            materials.insert(row, assignedGroups);
        } catch (RuntimeException exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
        return toMaterial(row);
    }

    @DeleteMapping("/materials/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMaterial(@PathVariable UUID id, CurrentUser actor) throws IOException {
        MaterialRow row = materials.findById(id).orElseThrow(() -> TrainingEngine.notFound("Материал не найден"));
        if (!row.teacherId().equals(actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Материал другого преподавателя");
        }
        materials.delete(id);
        Files.deleteIfExists(Path.of(row.storagePath()));
    }

    private Material toMaterial(MaterialRow row) {
        return new Material(row.id(), row.teacherId(), row.title(), row.fileName(), row.contentType(),
                row.sizeBytes(), row.uploadedAt(), materials.groupsOf(row.id()));
    }
}
