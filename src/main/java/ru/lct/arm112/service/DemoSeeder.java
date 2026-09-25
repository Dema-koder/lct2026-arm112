package ru.lct.arm112.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.arm112.api.ApiModels.Assessment;
import ru.lct.arm112.api.ApiModels.AssessmentIssue;
import ru.lct.arm112.api.ApiModels.CriterionScore;
import ru.lct.arm112.api.ApiModels.Group;
import ru.lct.arm112.api.ApiModels.Lesson;
import ru.lct.arm112.api.ApiModels.LessonCreate;
import ru.lct.arm112.persistence.AssessmentRepository;
import ru.lct.arm112.persistence.LessonRepository;
import ru.lct.arm112.persistence.SessionRepository;
import ru.lct.arm112.persistence.SessionRepository.SessionRow;
import ru.lct.arm112.persistence.UserRepository;
import ru.lct.arm112.persistence.UserRepository.AppUser;
import ru.lct.arm112.security.CurrentUser;
import ru.lct.arm112.security.Role;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Воспроизводимый демо-набор для проверки рабочих экранов и аналитики обеих ролей.
 * Детерминированные идентификаторы позволяют безопасно запускать сидер повторно:
 * пользовательские занятия сохраняются, а демо-записи не дублируются.
 */
@Component
public class DemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private static final String DEMO_GROUP = "Демо-группа";
    private static final String DEMO_PREFIX = "Демо-аналитика:";
    private static final List<String> TITLES = List.of(
            "Приём сообщения о пожаре",
            "Задымление в жилом доме",
            "ДТП с пострадавшими",
            "Вызов экстренных служб",
            "Уточнение адреса заявителя",
            "Передача карточки службе 102",
            "Массовое поступление обращений",
            "Работа с повторным звонком",
            "Координация нескольких служб",
            "Итоговая смена диспетчера"
    );
    private static final int[] PRIMARY_SCORES = {56, 61, 65, 68, 72, 75, 79, 83, 86, 90};
    private static final List<DemoTrainee> DEMO_TRAINEES = List.of(
            new DemoTrainee("demo.trainee.14", "Соколова Анна Викторовна", "14", -8),
            new DemoTrainee("demo.trainee.15", "Кузнецов Максим Олегович", "15", 2),
            new DemoTrainee("demo.trainee.16", "Орлова Елена Андреевна", "16", 7),
            new DemoTrainee("demo.trainee.17", "Морозов Дмитрий Павлович", "17", -2)
    );

    private final LessonRepository lessons;
    private final SessionRepository sessions;
    private final AssessmentRepository assessments;
    private final UserRepository users;
    private final LessonService lessonService;
    private final boolean enabled;

    public DemoSeeder(LessonRepository lessons, SessionRepository sessions,
                      AssessmentRepository assessments, UserRepository users,
                      LessonService lessonService,
                      @Value("${arm112.seed.analytics-demo-enabled:true}") boolean enabled) {
        this.lessons = lessons;
        this.sessions = sessions;
        this.assessments = assessments;
        this.users = users;
        this.lessonService = lessonService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (!enabled) {
            log.info("Демонстрационные данные аналитики отключены настройкой");
            return;
        }
        AppUser teacher = users.findByLogin("teacher").orElse(null);
        AppUser primary = users.findByLogin("trainee").orElse(null);
        if (teacher == null || primary == null) {
            log.warn("Демо-аналитика не создана: отсутствует teacher или trainee");
            return;
        }

        Group group = resolveGroup(teacher, primary);
        if (primary.groupId() == null || !primary.groupId().equals(group.id())) {
            users.update(primary.id(), primary.displayName(), primary.role(), primary.workstationNumber(), group.id());
            primary = users.findById(primary.id()).orElseThrow();
        }

        List<AppUser> participants = new ArrayList<>();
        participants.add(primary);
        for (DemoTrainee demo : DEMO_TRAINEES) participants.add(resolveTrainee(demo, primary, group, teacher));

        int created = 0;
        for (int index = 0; index < TITLES.size(); index++) {
            if (seedCompletedLesson(index, teacher, group, participants)) created++;
        }
        for (int index = 0; index < 2; index++) {
            if (seedDraftLesson(index, teacher, group, participants)) created++;
        }
        seedActiveLessonIfNeeded(teacher, group, primary);
        log.info("Демо-аналитика готова: создано {} занятий, всего демо-занятий {}", created,
                lessons.findLessons(teacher.id(), null).stream().filter(l -> l.title().startsWith("Демо")).count());
    }

    private Group resolveGroup(AppUser teacher, AppUser primary) {
        if (primary.groupId() != null) {
            Group assigned = lessons.findGroup(primary.groupId()).orElse(null);
            if (assigned != null && assigned.teacherId().equals(teacher.id())) return assigned;
        }
        return lessons.findGroups(teacher.id()).stream()
                .filter(group -> group.name().equals(DEMO_GROUP))
                .findFirst()
                .orElseGet(() -> {
                    UUID id = stableId("group");
                    if (lessons.findGroup(id).isEmpty()) lessons.insertGroup(id, DEMO_GROUP, teacher.id());
                    return lessons.findGroup(id).orElseThrow();
                });
    }

    private AppUser resolveTrainee(DemoTrainee demo, AppUser passwordSource, Group group, AppUser teacher) {
        AppUser existing = users.findByLogin(demo.login()).orElse(null);
        if (existing == null) {
            existing = new AppUser(stableId("user:" + demo.login()), demo.login(), passwordSource.passwordHash(),
                    demo.name(), Role.TRAINEE, demo.workstation(), group.id(), true, 1, Instant.now());
            users.insert(existing, teacher.id());
        } else if (!group.id().equals(existing.groupId())) {
            users.update(existing.id(), existing.displayName(), Role.TRAINEE, existing.workstationNumber(), group.id());
        }
        return users.findByLogin(demo.login()).orElseThrow();
    }

    private boolean seedCompletedLesson(int index, AppUser teacher, Group group, List<AppUser> participants) {
        UUID lessonId = stableId("lesson:completed:" + index);
        if (lessons.findLesson(lessonId).isPresent()) return false;

        String mode = index % 2 == 0 ? "CARD_FILL" : "CARD_ACTIONS";
        String kind = index % 5 == 4 ? "EXAM" : index % 3 == 0 ? "TRAINING" : "CHECK";
        Instant completedAt = Instant.now().minus(Duration.ofDays(TITLES.size() - index));
        Instant startedAt = completedAt.minus(Duration.ofMinutes(38 + index * 3L));
        Lesson lesson = new Lesson(lessonId, teacher.id(), group.id(), group.name(),
                DEMO_PREFIX + " " + TITLES.get(index), kind, mode, "GENERATED", "DRAFT",
                List.of("ticket-0" + (index % 5 + 1) + "-1"), Instant.now(), null, null, null, 0, 70);
        lessons.insertLesson(lesson);

        for (int participantIndex = 0; participantIndex < participants.size(); participantIndex++) {
            AppUser trainee = participants.get(participantIndex);
            UUID sessionId = stableId("session:" + index + ":" + trainee.login());
            sessions.insert(new SessionRow(sessionId, lessonId, trainee.id(), trainee.workstationNumber(),
                    "COMPLETED", startedAt.plusSeconds(participantIndex * 45L), completedAt));
            sessions.setState(sessionId, "COMPLETED", completedAt.plusSeconds(participantIndex * 50L));

            int offset = participantIndex == 0 ? 0 : DEMO_TRAINEES.get(participantIndex - 1).scoreOffset();
            double aiTotal = clamp(PRIMARY_SCORES[index] + offset + ((index + participantIndex) % 3 - 1));
            Assessment assessment = assessment(index, participantIndex, sessionId, mode, aiTotal);
            assessments.insert(assessment, sessionId);
            if ((index + participantIndex) % 3 != 0) {
                double correction = switch ((index + participantIndex) % 4) {
                    case 0 -> -4;
                    case 1 -> 3;
                    case 2 -> -2;
                    default -> 2;
                };
                assessments.setTeacher(assessment.id(), teacher.id(), clamp(aiTotal + correction),
                        correction >= 0 ? "Учтена полнота действий и корректная коммуникация"
                                : "Снижен балл за неточность формулировки и нарушение норматива",
                        List.<CriterionScore>of());
            }
        }
        lessons.setState(lessonId, "COMPLETED", startedAt, completedAt);
        if (kind.equals("EXAM")) lessons.publish(lessonId, completedAt.plus(Duration.ofHours(2)));
        return true;
    }

    private boolean seedDraftLesson(int index, AppUser teacher, Group group, List<AppUser> participants) {
        UUID lessonId = stableId("lesson:draft:" + index);
        if (lessons.findLesson(lessonId).isPresent()) return false;
        String mode = index == 0 ? "CARD_FILL" : "CARD_ACTIONS";
        Lesson lesson = new Lesson(lessonId, teacher.id(), group.id(), group.name(),
                "Демо: " + (index == 0 ? "разбор сложных адресов" : "тренировка ночной смены"),
                "TRAINING", mode, "MIXED", "DRAFT", List.of("ticket-01-1", "ticket-03-1"),
                Instant.now(), null, null, null, 0, 70);
        lessons.insertLesson(lesson);
        for (int i = 0; i < Math.min(3, participants.size()); i++) {
            AppUser trainee = participants.get(i);
            sessions.insert(new SessionRow(stableId("session:draft:" + index + ":" + trainee.login()),
                    lessonId, trainee.id(), trainee.workstationNumber(), "PENDING", null, null));
        }
        return true;
    }

    private void seedActiveLessonIfNeeded(AppUser teacher, Group group, AppUser primary) {
        if (sessions.findActiveByTrainee(primary.id()).isPresent()) return;
        boolean alreadyCreated = lessons.findLessons(teacher.id(), null).stream()
                .anyMatch(lesson -> lesson.title().equals("Демо: текущее занятие"));
        if (alreadyCreated) return;
        CurrentUser actor = new CurrentUser(teacher.id(), teacher.login(), Role.TEACHER);
        Lesson lesson = lessonService.create(new LessonCreate("Демо: текущее занятие", group.id(),
                "TRAINING", "CARD_FILL", "GENERATED", List.of("ticket-01-1", "ticket-02-1"),
                List.of(primary.id()), 70), actor);
        lessonService.start(lesson.id(), actor);
    }

    private Assessment assessment(int lessonIndex, int participantIndex, UUID sessionId, String mode, double total) {
        double timing = clamp(total - 8 + (lessonIndex % 4) * 3);
        double language = clamp(total + 5 - participantIndex);
        int syntaxErrors = total >= 85 ? 0 : total >= 72 ? 1 : total >= 60 ? 2 : 4;
        List<AssessmentIssue> issues = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        if (timing < 80) {
            issues.add(new AssessmentIssue("TIME_LIMIT", "WARNING", "Превышен норматив обработки карточки", null, 180, 224));
            recommendations.add("Сначала фиксируйте адрес и тип происшествия, затем дополняйте описание.");
        }
        if (syntaxErrors >= 2) {
            issues.add(new AssessmentIssue("LANGUAGE", syntaxErrors >= 4 ? "CRITICAL" : "INFO",
                    "Обнаружены ошибки в описании происшествия", null, 0, syntaxErrors));
            recommendations.add("Проверяйте текст перед передачей карточки экстренным службам.");
        }
        if ((lessonIndex + participantIndex) % 4 == 0) {
            issues.add(new AssessmentIssue("CLASSIFICATION", "WARNING",
                    "Тип происшествия выбран недостаточно точно", null, "уточнённый тип", "общий тип"));
            recommendations.add("Используйте синонимы и уточняющие признаки классификатора.");
        }

        UUID assessmentId = stableId("assessment:" + sessionId);
        if (mode.equals("CARD_FILL")) {
            double address = clamp(total + 2);
            double classification = clamp(total - (lessonIndex % 3) * 4);
            double services = clamp(total + 4 - participantIndex);
            return new Assessment(assessmentId, sessionId, "COMPLETED", mode, total,
                    timing, null, null, language, address, classification, services, syntaxErrors,
                    issues, recommendations, "AI", total, null, null, null, List.of(), List.of());
        }
        double actions = clamp(total - 3 + participantIndex);
        double communication = clamp(total + 3);
        return new Assessment(assessmentId, sessionId, "COMPLETED", mode, total,
                timing, actions, communication, language, null, null, null, syntaxErrors,
                issues, recommendations, "AI", total, null, null, null, List.of(), List.of());
    }

    private static UUID stableId(String key) {
        return UUID.nameUUIDFromBytes(("arm112-demo:" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static double clamp(double value) {
        return Math.max(35, Math.min(98, Math.round(value * 10) / 10.0));
    }

    private record DemoTrainee(String login, String name, String workstation, int scoreOffset) {}
}
