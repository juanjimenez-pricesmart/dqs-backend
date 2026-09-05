// exception/GlobalExceptionHandler.java
package com.dqs.api.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(QuotationNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(QuotationNotFoundException ex) {
        log.warn("[GlobalExceptionHandler] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(QuotationAlreadySubmittedException.class)
    public ResponseEntity<Map<String, String>> handleAlreadySubmitted(QuotationAlreadySubmittedException ex) {
        log.warn("[GlobalExceptionHandler] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        f -> f.getField(),
                        f -> f.getDefaultMessage()
                ));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * An unmapped path is a 404, not a server fault. Without this, the
     * catch-all below swallows NoResourceFoundException and reports every
     * typo in a URL as "Internal server error".
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException ex) {
        log.warn("[GlobalExceptionHandler] No handler for {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        // Spring MVC raises a family of exceptions that already carry the right
        // status (405 method not allowed, 415 unsupported media type, and
        // anything thrown as a ResponseStatusException). They implement the
        // ErrorResponse interface rather than sharing a common superclass, so
        // an instanceof check is what catches them all. Without it they fall
        // through to the 500 below and a client error is reported as a server
        // fault.
        if (ex instanceof ErrorResponse er) {
            log.warn("[GlobalExceptionHandler] {} -> {}",
                    ex.getClass().getSimpleName(), er.getStatusCode());
            String title = er.getBody() != null ? er.getBody().getTitle() : null;
            return ResponseEntity.status(er.getStatusCode())
                    .body(Map.of("error", title != null ? title : er.getStatusCode().toString()));
        }
        log.error("[GlobalExceptionHandler] Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
    }
}