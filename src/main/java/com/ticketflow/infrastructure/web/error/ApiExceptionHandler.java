package com.ticketflow.infrastructure.web.error;

import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.InvalidEventException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

/**
 * Translates domain and request errors into RFC 7807 problem details. Messages are fixed or come
 * from domain exceptions designed to be client-safe; stack traces and internals are never exposed.
 *
 * <p>To add a case, add an {@code @ExceptionHandler} that builds its response with
 * {@link #problem(HttpStatus, String, String, String)}; cross-cutting additions (correlation id,
 * catch-all 500) belong in that same helper so every error keeps one shape.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    static final String TYPE_PREFIX = "urn:ticketflow:problem:";

    @ExceptionHandler(WebExchangeBindException.class)
    public ProblemDetail handleValidation(WebExchangeBindException ex) {
        List<Violation> violations = ex.getFieldErrors().stream()
                .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(Violation::field).thenComparing(Violation::message))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed",
                "The request body has invalid fields");
        problem.setProperty("violations", violations);
        return problem;
    }

    /** Malformed JSON, wrong types or unparseable values (for example a bad ISO-8601 instant). */
    @ExceptionHandler(ServerWebInputException.class)
    public ProblemDetail handleUnreadable(ServerWebInputException ex) {
        return problem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                "The request could not be read: check the JSON syntax and field formats");
    }

    @ExceptionHandler(InvalidEventException.class)
    public ProblemDetail handleInvalidEvent(InvalidEventException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-event", "Invalid event", ex.getMessage());
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ProblemDetail handleNotFound(EventNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "event-not-found", "Event not found", ex.getMessage());
    }

    @ExceptionHandler(EventAlreadyExistsException.class)
    public ProblemDetail handleAlreadyExists(EventAlreadyExistsException ex) {
        return problem(HttpStatus.CONFLICT, "event-already-exists", "Event already exists", ex.getMessage());
    }

    static ProblemDetail problem(HttpStatus status, String typeSuffix, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + typeSuffix));
        problem.setTitle(title);
        return problem;
    }
}
