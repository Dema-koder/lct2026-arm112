package ru.lct.arm112.api;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static ru.lct.arm112.api.ApiModels.FieldError;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldError> fieldErrors;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of(), Map.of());
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<FieldError> fieldErrors, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.details = Map.copyOf(details);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<FieldError> fieldErrors() { return fieldErrors; }
    public Map<String, Object> details() { return details; }
}
