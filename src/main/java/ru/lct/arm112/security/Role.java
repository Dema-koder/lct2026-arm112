package ru.lct.arm112.security;

/** Три уровня доступа по Q&A §15: администратор, преподаватель, обучающийся. */
public enum Role {
    ADMIN, TEACHER, TRAINEE;

    public static Role parse(String value) {
        try {
            return Role.valueOf(value);
        } catch (Exception ex) {
            return null;
        }
    }
}
