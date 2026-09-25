package com.github.highcumontoa.localcolumnarsegmentstorejava.web;

import com.github.highcumontoa.localcolumnarsegmentstorejava.except.ConcurrentConflictException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.IncompatibleSchemaException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.TableAlreadyExistsException;
import com.github.highcumontoa.localcolumnarsegmentstorejava.except.TableNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Translates store exceptions into distinguishable HTTP responses.
 * The {@code error} code is machine-readable; {@code message} carries the
 * precise rejection basis.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TableNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(TableNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(TableAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleExists(TableAlreadyExistsException e) {
        return body(HttpStatus.CONFLICT, "TABLE_ALREADY_EXISTS", e.getMessage());
    }

    @ExceptionHandler(ConcurrentConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConcurrentConflictException e) {
        return body(HttpStatus.CONFLICT, "CONCURRENT_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(IncompatibleSchemaException.class)
    public ResponseEntity<Map<String, Object>> handleIncompatible(IncompatibleSchemaException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "INCOMPATIBLE_SCHEMA", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", code,
                "message", String.valueOf(message)));
    }
}
