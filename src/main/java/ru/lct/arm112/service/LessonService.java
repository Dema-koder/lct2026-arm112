package ru.lct.arm112.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.*;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.persistence.AssessmentRepository.AssessmentRow;
import ru.lct.arm112.persistence.LessonRepository;
import ru.lct.arm112.persistence.SessionRepository;
import ru.lct.arm112.persistence.SessionRepository.SessionRow;
import ru.lct.arm112.persistence.UserRepository;
import ru.lct.arm112.persistence.UserRepository.AppUser;
import ru.lct.arm112.security.CurrentUser;
import ru.lct.arm112.security.Role;
import ru.lct.arm112.service.assessment.AssessmentWeights;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Занятия преподавателя: создание, старт персональных сессий, монитор, отчёт, оценка, публикация. */
@Service
public class LessonService {
    private static final Set<String> KINDS = Set.of("TRAINING", "CHECK", "EXAM");
    private static final Set<String> MODES = Set.of("CARD_FILL", "CARD_ACTIONS");
    private static final Set<String> SOURCES = Set.of("GENERATED", "TRAINEE_MADE", "MIXED");

    private final LessonRepository lessons;
    private final SessionRepository sessions;
    private final UserRepository users;
    private final AssessmentRepository assessments;
    private final ScenarioService scenarios;
    private final TrainingEngine engine;
    private final EventService events;
    private final RatingService ratings;

    public LessonService(LessonRepository lessons, SessionRepository sessions, UserRepository users,
                         AssessmentRepository assessments, ScenarioService scenarios, TrainingEngine engine,
                         EventService events, RatingService ratings) {
        this.lessons = lessons;
        this.sessions = sessions;
        this.users = users;
        this.assessments = assessments;
        this.scenarios = scenarios;
        this.engine = engine;
        this.events = events;
        this.ratings = ratings;
    }

    // ---------------------------------------------------------------- groups

    public List<Group> groups(CurrentUser actor) {
        UUID teacherId = actor.is(Role.ADMIN) ? null : actor.id();
        return lessons.findGroups(teacherId).stream().map(this::withMembers).toList();
    }

    public Group group(UUID id) {
        return lessons.findGroup(id).map(this::withMembers)
                .orElseThrow(() -> TrainingEngine.notFound("Группа не найдена"));
    }

    public Group createGroup(GroupUpsert request, CurrentUser actor) {
        UUID teacherId = request.teacherId() != null ? request.teacherId() : actor.id();
        AppUser teacher = users.findById(teacherId).orElseThrow(() -> TrainingEngine.notFound("Преподаватель не найден"));
        if (teacher.role() != Role.TEACHER || !teacher.active()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Указанный пользователь не преподаватель");
        }
        UUID id = UUID.randomUUID();
        lessons.insertGroup(id, request.name().trim(), teacherId);
        return group(id);
    }

    public Group updateGroup(UUID id, GroupUpsert request) {
        Group existing = group(id);
        UUID teacherId = request.teacherId() != null ? request.teacherId() : existing.teacherId();
        AppUser teacher = users.findById(teacherId)
                .orElseThrow(() -> TrainingEngine.notFound("Преподаватель не найден"));
        if (teacher.role() != Role.TEACHER || !teacher.active()) {
            throw invalid("Указанный пользователь не преподаватель");
        }
        lessons.updateGroup(id, request.name().trim(), teacherId);
        return group(id);
    }

    private Group withMembers(Group group) {
        List<User> members = users.findByGroup(group.id()).stream()
                .filter(u -> u.role() == Role.TRAINEE).map(UserService::toUser).toList();
        return new Group(group.id(), group.name(), group.teacherId(), group.teacherName(), members);
    }

    // ---------------------------------------------------------------- lessons

    public List<Lesson> list(CurrentUser teacher, String state) {
        return lessons.findLessons(teacher.id(), state);
    }

    public Lesson require(UUID id, CurrentUser teacher) {
        Lesson lesson = lessons.findLesson(id).orElseThrow(() -> TrainingEngine.notFound("Занятие не найдено"));
        // Преподаватель видит только свои занятия (решение №8).
        if (!lesson.teacherId().equals(teacher.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Занятие другого преподавателя");
        }
        return lesson;
    }

    public Lesson create(LessonCreate request, CurrentUser teacher) {
        if (!KINDS.contains(request.kind())) throw invalid("Вид занятия: TRAINING, CHECK или EXAM");
        if (!MODES.contains(request.mode())) throw invalid("Режим: CARD_FILL или CARD_ACTIONS");
        if (!SOURCES.contains(request.cardSource())) throw invalid("Источник карточек: GENERATED, TRAINEE_MADE или MIXED");
        scenarios.requireAll(request.scenarioIds());
        List<AppUser> trainees = users.findByIds(request.traineeIds());
        if (trainees.size() != request.traineeIds().size()
                || trainees.stream().anyMatch(u -> u.role() != Role.TRAINEE || !u.active())) {
            throw invalid("Среди выбранных участников есть неактивные пользователи или не обучающиеся");
        }
        if (request.groupId() != null) {
            Group group = group(request.groupId());
            if (!group.teacherId().equals(teacher.id())) throw invalid("Группа другого преподавателя");
        }
        int normScore = request.normScore() == null ? 60 : request.normScore();
        UUID id = UUID.randomUUID();
        Lesson lesson = new Lesson(id, teacher.id(), request.groupId(), null, request.title().trim(), request.kind(),
                request.mode(), request.cardSource(), "DRAFT", request.scenarioIds(), Instant.now(), null, null, null, 0,
                normScore);
        lessons.insertLesson(lesson);
        for (AppUser trainee : trainees) {
            sessions.insert(new SessionRow(UUID.randomUUID(), id, trainee.id(), trainee.workstationNumber(),
                    "PENDING", null, null));
        }
        return require(id, teacher);
    }

    /** Старт: у каждого участника своя сессия с его АРМ из учётной записи (решения №1, №2). */
    public Lesson start(UUID id, CurrentUser teacher) {
        Lesson lesson = require(id, teacher);
        if (!lesson.state().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "LESSON_NOT_DRAFT", "Занятие уже начато");
        }
        List<Scenario> lessonScenarios = scenarios.requireAll(lesson.scenarioIds());
        List<SessionRow> lessonSessions = sessions.findByLesson(id);
        Map<UUID, AppUser> trainees = lessonSessions.stream()
                .map(row -> users.findById(row.traineeId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(AppUser::id, user -> user));
        if (trainees.size() != lessonSessions.size()
                || trainees.values().stream().anyMatch(user -> user.role() != Role.TRAINEE || !user.active())) {
            throw invalid("Нельзя запустить занятие: один из участников удалён, заблокирован или сменил роль");
        }
        Instant now = Instant.now();
        lessons.setState(id, "ACTIVE", now, null);
        for (SessionRow row : lessonSessions) {
            AppUser trainee = trainees.get(row.traineeId());
            // у обучающегося одно активное занятие: предыдущее закрывается принудительно
            sessions.findActiveByTrainee(row.traineeId()).ifPresent(active -> engine.forceComplete(active.id()));
            String workstation = trainee.workstationNumber();
            SessionRow started = new SessionRow(row.id(), row.lessonId(), row.traineeId(), workstation, "ACTIVE", now, null);
            sessions.setState(row.id(), "ACTIVE", null);
            Lesson current = lessons.findLesson(id).orElse(lesson);
            engine.open(started, current, lessonScenarios);
            events.notifyUser(row.traineeId(), "training.session_started",
                    Map.of("sessionId", row.id().toString(), "lessonId", id.toString(), "mode", lesson.mode()));
        }
        return require(id, teacher);
    }

    public Lesson complete(UUID id, CurrentUser teacher) {
        Lesson lesson = require(id, teacher);
        for (SessionRow row : sessions.findByLesson(id)) {
            if (row.state().equals("ACTIVE")) engine.forceComplete(row.id());
            if (row.state().equals("PENDING")) sessions.setState(row.id(), "COMPLETED", Instant.now());
        }
        lessons.setState(id, "COMPLETED", null, Instant.now());
        return require(id, teacher);
    }

    /** Публикация результатов — только для зачёта (решение №10). */
    public Lesson publish(UUID id, CurrentUser teacher) {
        Lesson lesson = require(id, teacher);
        if (!lesson.kind().equals("EXAM")) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_EXAM", "Публикуются только результаты зачёта");
        }
        if (!lesson.state().equals("COMPLETED")) {
            throw new ApiException(HttpStatus.CONFLICT, "LESSON_NOT_COMPLETED", "Сначала завершите занятие");
        }
        lessons.publish(id, Instant.now());
        for (SessionRow row : sessions.findByLesson(id)) {
            events.notifyUser(row.traineeId(), "results.published", Map.of("lessonId", id.toString()));
        }
        return require(id, teacher);
    }

    public LessonMonitor monitor(UUID id, CurrentUser teacher) {
        Lesson lesson = require(id, teacher);
        List<MonitorRow> rows = new ArrayList<>();
        for (SessionRow row : sessions.findByLesson(id)) {
            String name = users.findById(row.traineeId()).map(AppUser::displayName).orElse("—");
            rows.add(engine.monitorRow(row, name));
        }
        return new LessonMonitor(lesson, rows);
    }

    public LessonReport report(UUID id, CurrentUser teacher) {
        Lesson lesson = require(id, teacher);
        Map<UUID, AssessmentRow> byLessonSession = assessments.findByLesson(id).stream()
                .collect(Collectors.toMap(AssessmentRow::sessionId, a -> a, (a, b) -> b));
        List<ReportRow> rows = new ArrayList<>();
        for (SessionRow row : sessions.findByLesson(id)) {
            AppUser trainee = users.findById(row.traineeId()).orElse(null);
            AssessmentRow a = byLessonSession.get(row.id());
            Rating rating = ratings.traineeRating(row.traineeId(), trainee == null ? null : trainee.groupId());
            rows.add(new ReportRow(row.id(), row.traineeId(), trainee == null ? "—" : trainee.displayName(),
                    row.workstationNumber(), a == null ? null : a.timingScore(), a == null ? null : a.syntaxErrors(),
                    rating.value(), a == null ? null : a.aiTotal(), a == null ? null : a.teacherTotal(),
                    a == null ? null : a.finalTotal(), row.state()));
        }
        rows.sort((x, y) -> Double.compare(y.finalTotal() == null ? -1 : y.finalTotal(), x.finalTotal() == null ? -1 : x.finalTotal()));
        return new LessonReport(lesson, rows, ratings.lessonRating(id));
    }

    public String reportCsv(UUID id, CurrentUser teacher) {
        LessonReport report = report(id, teacher);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("Обучающийся;АРМ;Оценка по времени;Синтаксических ошибок;Уровень (рейтинг);Оценка ИИ;Оценка преподавателя;Итог;Состояние\n");
        for (ReportRow row : report.rows()) {
            csv.append(escape(row.traineeName())).append(';').append(nz(row.workstationNumber())).append(';')
                    .append(num(row.timingScore())).append(';').append(row.syntaxErrors() == null ? "" : row.syntaxErrors()).append(';')
                    .append(num(row.level())).append(';').append(num(row.aiTotal())).append(';')
                    .append(num(row.teacherTotal())).append(';').append(num(row.finalTotal())).append(';')
                    .append(row.state()).append('\n');
        }
        return csv.toString();
    }

    public Assessment assess(UUID sessionId, TeacherAssessment request, CurrentUser teacher) {
        SessionRow row = sessions.findById(sessionId).orElseThrow(() -> TrainingEngine.notFound("Занятие не найдено"));
        require(row.lessonId(), teacher);
        AssessmentRow assessment = assessments.findBySession(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_ASSESSED", "Обучающийся ещё не завершил занятие"));
        List<CriterionScore> criteria = request.criteria() == null ? List.of() : request.criteria();
        Set<String> allowed = AssessmentWeights.forMode(assessment.mode()).stream()
                .map(AssessmentWeights.Criterion::code).collect(Collectors.toSet());
        for (CriterionScore c : criteria) {
            if (!allowed.contains(c.code())) throw invalid("Неизвестный критерий для режима: " + c.code());
        }
        boolean anyScore = criteria.stream().anyMatch(c -> c.score() != null);
        Double total;
        if (anyScore) {
            // итог считается по весам режима: балл преподавателя там, где он задан, иначе ИИ
            total = AssessmentWeights.total(assessment.ai(), criteria);
        } else if (request.total() != null) {
            total = request.total();
        } else {
            throw invalid("Укажите итоговый балл или балл хотя бы по одному критерию");
        }
        assessments.setTeacher(assessment.id(), teacher.id(), total, request.comment(), criteria);
        events.notifyUser(row.traineeId(), "assessment.updated", Map.of("assessmentId", assessment.id().toString()));
        return assessments.findById(assessment.id()).map(AssessmentRepository::finalOf).orElseThrow();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", message);
    }

    private static String escape(String value) {
        if (value == null) return "";
        String safe = value.replace(";", ",").replace('\r', ' ').replace('\n', ' ');
        return !safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0 ? "'" + safe : safe;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String num(Double value) {
        return value == null ? "" : String.valueOf(value).replace('.', ',');
    }
}
