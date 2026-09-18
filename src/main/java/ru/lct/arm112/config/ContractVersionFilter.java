package ru.lct.arm112.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.lct.arm112.api.ApiModels.ErrorBody;
import ru.lct.arm112.api.ApiModels.ErrorEnvelope;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ContractVersionFilter extends OncePerRequestFilter {
    private static final String SUPPORTED_VERSION = "0.2";
    private final ObjectMapper objectMapper;

    public ContractVersionFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/")
                || request.getMethod().equalsIgnoreCase("OPTIONS");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SUPPORTED_VERSION.equals(request.getHeader("X-Contract-Version"))) {
            filterChain.doFilter(request, response);
            return;
        }
        UUID requestId = requestId(request);
        response.setStatus(426);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Request-Id", requestId.toString());
        objectMapper.writeValue(response.getOutputStream(), new ErrorEnvelope(new ErrorBody(
                "CONTRACT_VERSION_UNSUPPORTED", "Поддерживается версия контракта 0.2",
                requestId, List.of(), Map.of("supportedVersion", SUPPORTED_VERSION))));
    }

    private UUID requestId(HttpServletRequest request) {
        try {
            return UUID.fromString(request.getHeader("X-Request-Id"));
        } catch (Exception ignored) {
            return UUID.randomUUID();
        }
    }
}
