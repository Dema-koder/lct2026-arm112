package ru.lct.arm112.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** Кто выполняет запрос: извлекается из JWT, резолвится как аргумент контроллера. */
public record CurrentUser(UUID id, String login, Role role) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString("preferred_username"),
                Role.parse(jwt.getClaimAsString("role")));
    }

    /** Текущий пользователь из SecurityContext или null, если запрос анонимный. */
    public static CurrentUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        return from(jwt);
    }

    public boolean is(Role expected) {
        return role == expected;
    }
}
