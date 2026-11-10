package com.dunghaiquyen.catalog.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class NotFoundException extends RuntimeException { NotFoundException(String message) { super(message); } }
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<?> notFound(NotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<?> conflict(ConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid(MethodArgumentNotValidException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Invalid request", request);
    }

    private ResponseEntity<?> error(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(), "status", status.value(), "error", status.getReasonPhrase(),
                "message", message, "path", request.getRequestURI(), "correlationId", MDC.get("correlationId")));
    }
}
