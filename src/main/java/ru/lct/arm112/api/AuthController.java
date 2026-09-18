package ru.lct.arm112.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.arm112.security.JwtService;
import ru.lct.arm112.service.TrainingEngine;
import ru.lct.arm112.service.WsTicketService;

import static ru.lct.arm112.api.ApiModels.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final TrainingEngine engine;
    private final JwtService jwtService;
    private final WsTicketService tickets;
    private final PasswordEncoder passwordEncoder;
    private final String demoPasswordHash;

    public AuthController(TrainingEngine engine, JwtService jwtService,
                          WsTicketService tickets, PasswordEncoder passwordEncoder) {
        this.engine = engine;
        this.jwtService = jwtService;
        this.tickets = tickets;
        this.passwordEncoder = passwordEncoder;
        this.demoPasswordHash = passwordEncoder.encode("trainee");
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        if (!request.username().equals("trainee")
                || !passwordEncoder.matches(request.password(), demoPasswordHash)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Неверное имя пользователя или пароль");
        }
        JwtService.IssuedToken token = jwtService.issue(TrainingEngine.USER_ID, request.username());
        return new AuthResponse(token.value(), token.expiresAt(), engine.user());
    }

    @GetMapping("/me")
    public User me() {
        return engine.user();
    }

    @PostMapping("/ws-ticket")
    @ResponseStatus(HttpStatus.CREATED)
    public WsTicket wsTicket(@AuthenticationPrincipal Jwt jwt) {
        WsTicketService.Ticket ticket = tickets.issue(jwt.getSubject());
        return new WsTicket(ticket.value(), ticket.expiresAt());
    }
}
