package ru.lct.arm112.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.arm112.persistence.UserRepository.AppUser;
import ru.lct.arm112.security.CurrentUser;
import ru.lct.arm112.security.Role;
import ru.lct.arm112.service.AuditService;
import ru.lct.arm112.service.AuthSessionService;
import ru.lct.arm112.service.LoginThrottleService;
import ru.lct.arm112.service.OperationalMetrics;
import ru.lct.arm112.service.UserService;
import ru.lct.arm112.service.WsTicketService;

import static ru.lct.arm112.api.ApiModels.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserService users;
    private final AuthSessionService sessions;
    private final LoginThrottleService throttle;
    private final WsTicketService tickets;
    private final AuditService audit;
    private final OperationalMetrics metrics;

    public AuthController(UserService users, AuthSessionService sessions, LoginThrottleService throttle,
                          WsTicketService tickets, AuditService audit, OperationalMetrics metrics) {
        this.users = users;
        this.sessions = sessions;
        this.throttle = throttle;
        this.tickets = tickets;
        this.audit = audit;
        this.metrics = metrics;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        try {
            throttle.check(request.username(), ip);
        } catch (ApiException blocked) {
            metrics.loginBlocked();
            throw blocked;
        }
        AppUser user;
        try {
            user = users.authenticate(request.username(), request.password());
        } catch (ApiException failure) {
            throttle.failure(request.username(), ip);
            metrics.loginFailure();
            audit.record(new CurrentUser(null, request.username(), null), "auth.login_failed", "auth", null,
                    401, null, ip, null);
            throw failure;
        }
        throttle.success(request.username(), ip);
        metrics.loginSuccess();
        audit.record(new CurrentUser(user.id(), user.login(), user.role()), "auth.login", "auth", null,
                200, null, ip, null);
        return response(sessions.issue(user));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return response(sessions.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        sessions.logout(request.refreshToken());
    }

    @GetMapping("/me")
    public User me(CurrentUser actor) {
        return UserService.toUser(users.require(actor.id()));
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody PasswordChangeRequest request, CurrentUser actor) {
        users.changePassword(actor.id(), request.current(), request.next());
    }

    @PostMapping("/ws-ticket")
    @ResponseStatus(HttpStatus.CREATED)
    public WsTicket wsTicket(CurrentUser actor) {
        Role role = actor.role();
        WsTicketService.Ticket ticket = tickets.issue(actor.id().toString(), role == null ? null : role.name());
        return new WsTicket(ticket.value(), ticket.expiresAt());
    }

    private static AuthResponse response(AuthSessionService.SessionTokens tokens) {
        return new AuthResponse(tokens.access().value(), tokens.access().expiresAt(),
                UserService.toUser(tokens.user()), tokens.refreshToken(), tokens.refreshExpiresAt());
    }
}
