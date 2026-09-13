package com.paytm.wallet.exception;

import com.paytm.wallet.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Central place that turns exceptions into JSON error responses, so controllers don't
 * need try/catch blocks of their own.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("IDEMPOTENCY_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", message));
    }

    /** Malformed JSON, or a field of the wrong type — answer in our own error shape, not Spring's default. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("MALFORMED_REQUEST", "Request body could not be parsed as JSON"));
    }

    /**
     * Anything not handled above. Two jobs:
     *
     * 1. Log it — as a structured event, at ERROR, with the stack trace and (via the MDC)
     *    the request's correlation id. This is what makes it safe to silence Hibernate's
     *    SqlExceptionHelper in logback-spring.xml: that logger reports the *expected*
     *    idempotency-key violation as an error, while genuine failures land here instead,
     *    where they are distinguishable and traceable.
     * 2. Answer in our own error shape. Without this, an unexpected failure returns
     *    Spring's default error JSON — a different schema from every other error this API
     *    produces, and one that leaks the exception class and stack to the caller.
     *
     * The message deliberately says nothing specific: the detail belongs in the log, not
     * in a response to an untrusted caller.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("unhandled exception", kv("event", "unhandled_exception"),
                kv("exceptionClass", ex.getClass().getName()), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "The request could not be completed"));
    }
}
