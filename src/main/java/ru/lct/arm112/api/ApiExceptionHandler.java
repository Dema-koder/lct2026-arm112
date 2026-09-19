package ru.lct.arm112.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ru.lct.arm112.api.ApiModels.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorEnvelope> api(ApiException ex, HttpServletRequest request) {
        return response(ex.status(), ex.code(), ex.getMessage(), ex.fieldErrors(), ex.details(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorEnvelope> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), "INVALID", error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                "Проверьте переданные поля", fields, Map.of(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorEnvelope> malformed(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Некорректный запрос: " + ex.getMessage(), List.of(), Map.of(), request);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ErrorEnvelope> forbidden(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав", List.of(), Map.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorEnvelope> noResource(NoResourceFoundException ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Путь не найден", List.of(), Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> unexpected(Exception ex, HttpServletRequest request) {
        // Долг из history: раньше стектрейсы не логировались вовсе, и любая опечатка выглядела как падение сервера.
        log.error("Внутренняя ошибка на {} {}", request.getMethod(), request.getRequestURI(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Внутренняя ошибка", List.of(), Map.of(), request);
    }

    private ResponseEntity<ErrorEnvelope> response(HttpStatus status, String code,
                                                   String message, List<FieldError> fields,
                                                   Map<String, Object> details,
                                                   HttpServletRequest request) {
        UUID requestId = requestId(request);
        return ResponseEntity.status(status)
                .header("X-Request-Id", requestId.toString())
                .body(new ErrorEnvelope(new ErrorBody(code, message, requestId, fields, details)));
    }

    private UUID requestId(HttpServletRequest request) {
        try {
            return UUID.fromString(request.getHeader("X-Request-Id"));
        } catch (Exception ignored) {
            return UUID.randomUUID();
        }
    }
}
