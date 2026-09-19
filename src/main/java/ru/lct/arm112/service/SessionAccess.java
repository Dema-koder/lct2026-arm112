package ru.lct.arm112.service;

import org.springframework.stereotype.Component;
import ru.lct.arm112.persistence.LessonRepository;
import ru.lct.arm112.persistence.SessionRepository;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Кто имеет доступ к сессии: её обучающийся и преподаватель занятия. Кэш на время жизни процесса. */
@Component
public class SessionAccess {
    private final SessionRepository sessions;
    private final LessonRepository lessons;
    private final Map<UUID, Recipients> cache = new ConcurrentHashMap<>();

    public SessionAccess(SessionRepository sessions, LessonRepository lessons) {
        this.sessions = sessions;
        this.lessons = lessons;
    }

    public Optional<Recipients> recipientsOf(UUID sessionId) {
        Recipients cached = cache.get(sessionId);
        if (cached != null) return Optional.of(cached);
        return sessions.findById(sessionId).flatMap(session -> lessons.findLesson(session.lessonId())
                .map(lesson -> {
                    Recipients recipients = new Recipients(session.traineeId(), lesson.teacherId(), lesson.id());
                    cache.put(sessionId, recipients);
                    return recipients;
                }));
    }

    public void remember(UUID sessionId, UUID traineeId, UUID teacherId, UUID lessonId) {
        cache.put(sessionId, new Recipients(traineeId, teacherId, lessonId));
    }

    public record Recipients(UUID traineeId, UUID teacherId, UUID lessonId) {
        public Set<UUID> userIds() {
            return Set.of(traineeId, teacherId);
        }
    }
}
