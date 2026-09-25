package ru.lct.arm112.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.arm112.api.ApiException;
import ru.lct.arm112.persistence.IdempotencyRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              @Value("${arm112.idempotency.ttl:PT24H}") Duration ttl) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Transactional
    public <T> T execute(String operation, UUID key, String signature, Class<T> responseType,
                         Supplier<T> action) {
        boolean owner = repository.reserve(operation, key, signature, Instant.now().plus(ttl));
        if (!owner) return replay(operation, key, signature, responseType);
        try {
            T response = action.get();
            repository.complete(operation, key, responseType.getName(), objectMapper.writeValueAsString(response));
            return response;
        } catch (JacksonException exception) {
            repository.release(operation, key);
            throw new IllegalStateException("Не удалось сохранить идемпотентный ответ", exception);
        } catch (RuntimeException exception) {
            repository.release(operation, key);
            throw exception;
        }
    }

    private <T> T replay(String operation, UUID key, String signature, Class<T> responseType) {
        IdempotencyRepository.Record record = repository.find(operation, key).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                        "Запрос с этим ключом уже выполняется"));
        if (!record.signature().equals(signature)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Ключ уже использован с другим запросом");
        }
        if (record.responsePayload() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                    "Запрос с этим ключом ещё выполняется");
        }
        try {
            return objectMapper.readValue(record.responsePayload(), responseType);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Не удалось прочитать идемпотентный ответ", exception);
        }
    }

    @Scheduled(cron = "${arm112.idempotency.cleanup-cron:0 17 * * * *}")
    void cleanup() {
        repository.deleteExpired();
    }
}
