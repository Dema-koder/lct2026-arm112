package ru.lct.arm112.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.api.ApiModels.User;
import ru.lct.arm112.api.ApiModels.UserAdminView;
import ru.lct.arm112.api.ApiModels.UserCreate;
import ru.lct.arm112.api.ApiModels.UserUpdate;
import ru.lct.arm112.persistence.LessonRepository;
import ru.lct.arm112.persistence.UserRepository;
import ru.lct.arm112.persistence.UserRepository.AppUser;
import ru.lct.arm112.security.Role;

import java.util.List;
import java.util.UUID;

/** Учётные записи трёх ролей и сид первых пользователей. */
@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final LessonRepository lessons;
    private final PasswordEncoder encoder;
    private final String adminPassword;
    private final String teacherPassword;
    private final String traineePassword;

    public UserService(UserRepository users, LessonRepository lessons, PasswordEncoder encoder,
                       @Value("${arm112.seed.admin-password:admin}") String adminPassword,
                       @Value("${arm112.seed.teacher-password:teacher}") String teacherPassword,
                       @Value("${arm112.seed.trainee-password:trainee}") String traineePassword) {
        this.users = users;
        this.lessons = lessons;
        this.encoder = encoder;
        this.adminPassword = adminPassword;
        this.teacherPassword = teacherPassword;
        this.traineePassword = traineePassword;
    }

    @PostConstruct
    void seed() {
        if (users.count() > 0) return;
        users.insert(AppUser.create("admin", encoder.encode(adminPassword), "Администратор системы", Role.ADMIN, null, null), null);
        users.insert(AppUser.create("teacher", encoder.encode(teacherPassword), "Петрова Мария Сергеевна", Role.TEACHER, null, null), null);
        users.insert(AppUser.create("trainee", encoder.encode(traineePassword), "Иванов Иван Иванович", Role.TRAINEE, "12", null), null);
        log.info("Созданы сидовые учётные записи admin / teacher / trainee");
    }

    public AppUser require(UUID id) {
        return users.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Пользователь не найден"));
    }

    public AppUser authenticate(String login, String password) {
        AppUser user = users.findByLogin(login).orElse(null);
        if (user == null || !user.active() || !encoder.matches(password, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Неверное имя пользователя или пароль");
        }
        return user;
    }

    public void changePassword(UUID id, String current, String next) {
        AppUser user = require(id);
        if (!encoder.matches(current, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Текущий пароль неверен");
        }
        users.setPasswordHash(id, encoder.encode(next));
    }

    public void resetPassword(UUID id, String password) {
        require(id);
        users.setPasswordHash(id, encoder.encode(password));
    }

    public UserAdminView create(UserCreate request, UUID createdBy) {
        Role role = parseRole(request.role());
        validateGroupAssignment(role, request.groupId());
        if (users.findByLogin(request.login()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "LOGIN_TAKEN", "Логин уже занят");
        }
        AppUser user = AppUser.create(request.login().trim(), encoder.encode(request.password()),
                request.displayName().trim(), role, blankToNull(request.workstationNumber()), request.groupId());
        users.insert(user, createdBy);
        return toAdminView(user);
    }

    public UserAdminView update(UUID id, UserUpdate request) {
        AppUser existing = require(id);
        Role role = parseRole(request.role());
        validateGroupAssignment(role, request.groupId());
        if (existing.role() == Role.TEACHER && role != Role.TEACHER && lessons.countGroupsByTeacher(id) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Сначала назначьте другого преподавателя его группам");
        }
        users.update(id, request.displayName().trim(), role,
                blankToNull(request.workstationNumber()), request.groupId());
        return toAdminView(require(id));
    }

    public UserAdminView setActive(UUID id, boolean active, UUID actor) {
        if (id.equals(actor) && !active) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Нельзя заблокировать себя");
        }
        require(id);
        users.setActive(id, active);
        return toAdminView(require(id));
    }

    public List<UserAdminView> list(String role, Boolean active) {
        Role parsed = role == null || role.isBlank() ? null : parseRole(role);
        return users.findAll(parsed, active).stream().map(UserService::toAdminView).toList();
    }

    public static User toUser(AppUser user) {
        return new User(user.id(), user.displayName(), user.role().name(), user.login(),
                user.workstationNumber(), user.groupId());
    }

    public static UserAdminView toAdminView(AppUser user) {
        return new UserAdminView(user.id(), user.login(), user.displayName(), user.role().name(),
                user.workstationNumber(), user.groupId(), user.active(), user.createdAt());
    }

    private static Role parseRole(String value) {
        Role role = Role.parse(value);
        if (role == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Роль должна быть ADMIN, TEACHER или TRAINEE");
        }
        return role;
    }

    private void validateGroupAssignment(Role role, UUID groupId) {
        if (groupId == null) return;
        if (role != Role.TRAINEE) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "К учебной группе можно прикрепить только обучающегося");
        }
        if (lessons.findGroup(groupId).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "Учебная группа не найдена");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
