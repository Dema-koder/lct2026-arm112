package ru.lct.arm112.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.lct.arm112.api.ApiModels.GroupUpsert;
import ru.lct.arm112.api.ApiModels.Lesson;
import ru.lct.arm112.api.ApiModels.LessonCreate;
import ru.lct.arm112.persistence.LessonRepository;
import ru.lct.arm112.persistence.UserRepository;
import ru.lct.arm112.persistence.UserRepository.AppUser;
import ru.lct.arm112.security.CurrentUser;
import ru.lct.arm112.security.Role;

import java.util.List;

/**
 * Демо-стенд из коробки: группа «Демо», обучающийся trainee в ней и запущенная проверка
 * в режиме заполнения карточки по двум билетам. Срабатывает только на пустой базе занятий.
 */
@Component
public class DemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final LessonRepository lessons;
    private final UserRepository users;
    private final LessonService lessonService;

    public DemoSeeder(LessonRepository lessons, UserRepository users, LessonService lessonService) {
        this.lessons = lessons;
        this.users = users;
        this.lessonService = lessonService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (lessons.countGroups() > 0 || !lessons.findLessons(null, null).isEmpty()) return;
        AppUser teacher = users.findByLogin("teacher").orElse(null);
        AppUser trainee = users.findByLogin("trainee").orElse(null);
        if (teacher == null || trainee == null) return;
        CurrentUser actor = new CurrentUser(teacher.id(), teacher.login(), Role.TEACHER);
        var group = lessonService.createGroup(new GroupUpsert("Демо-группа", teacher.id()), actor);
        users.update(trainee.id(), trainee.displayName(), trainee.role(), trainee.workstationNumber(), group.id());
        Lesson lesson = lessonService.create(new LessonCreate("Демо: заполнение карточки", group.id(),
                "TRAINING", "CARD_FILL", "GENERATED", List.of("ticket-01-1", "ticket-02-1"), List.of(trainee.id()), 60), actor);
        lessonService.start(lesson.id(), actor);
        log.info("Создано демо-занятие {} для обучающегося trainee", lesson.id());
    }
}
