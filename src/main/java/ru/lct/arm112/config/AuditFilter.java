package ru.lct.arm112.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import ru.lct.arm112.security.CurrentUser;
import ru.lct.arm112.service.AuditService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Журнал действий всех ролей (решение №4): каждый изменяющий запрос под /api/v1 пишется в audit_event
 * после выполнения — с автором, шаблоном пути, статусом и телом без паролей.
 */
@Component
@Order(Integer.MAX_VALUE - 10)
public class AuditFilter extends OncePerRequestFilter {
    private final AuditService audit;

    public AuditFilter(AuditService audit) {
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !request.getRequestURI().startsWith("/api/v1/")
                || method.equalsIgnoreCase("GET") || method.equalsIgnoreCase("OPTIONS") || method.equalsIgnoreCase("HEAD")
                || request.getRequestURI().equals("/api/v1/auth/login");   // логин пишет AuthController сам
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, 8192);
        try {
            chain.doFilter(wrapped, response);
        } finally {
            Object pattern = wrapped.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String path = pattern == null ? wrapped.getRequestURI() : pattern.toString();
            String action = wrapped.getMethod() + " " + path;
            String body = wrapped.getContentType() != null && wrapped.getContentType().contains("json")
                    ? new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8) : null;
            String[] parts = wrapped.getRequestURI().split("/");
            String resourceType = parts.length > 3 ? parts[3] : null;
            String resourceId = parts.length > 4 ? parts[4] : null;
            audit.record(CurrentUser.current(), action, resourceType, resourceId, response.getStatus(),
                    requestId(wrapped), wrapped.getRemoteAddr(), body);
        }
    }

    private static UUID requestId(HttpServletRequest request) {
        try {
            return UUID.fromString(request.getHeader("X-Request-Id"));
        } catch (Exception ignored) {
            return null;
        }
    }
}
