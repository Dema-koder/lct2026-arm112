package ru.lct.arm112.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ru.lct.arm112.api.ApiModels.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorEnvelope> api(ApiException ex, HttpServletRequest request) {
        return response(ex.status(), ex.code(), ex.getMessage(), ex.fieldErrors(), ex.details(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorEnvelope> validation(MethodArgumentNotValidException ex,
                                             HttpServletRequest request) {
        List<FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), "INVALID", error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                "Проверьте переданные поля", fields, Map.of(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorEnvelope> malformed(HttpMessageNotReadableException ex,
                                            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Некорректное тело запроса", List.of(), Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> unexpected(Exception ex, HttpServletRequest request) {
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
