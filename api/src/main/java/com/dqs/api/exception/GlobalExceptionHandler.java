// exception/GlobalExceptionHandler.java
package com.dqs.api.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
     * An upstream failure is not our fault, and a 404 from the Business API is
     * not a server error. Mapping these through the catch-all below reported a
     * missing membership as "Internal server error".
     */
    @ExceptionHandler(BusinessApiException.class)
    public ResponseEntity<Map<String, String>> handleBusinessApi(BusinessApiException ex) {
        if (ex.getStatus() == HttpStatus.NOT_FOUND.value()) {
            log.warn("[GlobalExceptionHandler] Business API 404 on {}", ex.getPath());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No encontrado en el Business API"));
        }
        log.error("[GlobalExceptionHandler] Business API {} on {}", ex.getStatus(), ex.getPath());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Business API respondió " + ex.getStatus()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("[GlobalExceptionHandler] Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
    }
}