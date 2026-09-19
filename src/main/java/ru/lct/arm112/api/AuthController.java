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
import ru.lct.arm112.security.JwtService;
import ru.lct.arm112.security.Role;
import ru.lct.arm112.service.AuditService;
import ru.lct.arm112.service.UserService;
import ru.lct.arm112.service.WsTicketService;

import static ru.lct.arm112.api.ApiModels.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserService users;
    private final JwtService jwtService;
    private final WsTicketService tickets;
    private final AuditService audit;

    public AuthController(UserService users, JwtService jwtService, WsTicketService tickets, AuditService audit) {
        this.users = users;
        this.jwtService = jwtService;
        this.tickets = tickets;
        this.audit = audit;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AppUser user;
        try {
            user = users.authenticate(request.username(), request.password());
        } catch (ApiException failure) {
            audit.record(new CurrentUser(null, request.username(), null), "auth.login_failed", "auth", null,
                    401, null, http.getRemoteAddr(), null);
            throw failure;
        }
        audit.record(new CurrentUser(user.id(), user.login(), user.role()), "auth.login", "auth", null,
                200, null, http.getRemoteAddr(), null);
        JwtService.IssuedToken token = jwtService.issue(user);
        return new AuthResponse(token.value(), token.expiresAt(), UserService.toUser(user));
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
}
