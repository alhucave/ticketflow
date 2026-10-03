package com.ticketflow.infrastructure.web.error;

import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.IdempotentOrderNotActiveException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.InvalidEventException;
import com.ticketflow.domain.exception.OrderEnqueueFailedException;
import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.infrastructure.web.InvalidIdempotencyKeyException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

/**
 * Translates domain and request errors into RFC 7807 problem details. Messages are fixed or come
 * from domain exceptions designed to be client-safe; stack traces and internals are never exposed.
 *
 * <p>To add a case, add an {@code @ExceptionHandler} that builds its response with
 * {@link #respond(ProblemDetail)} around {@link #problem(HttpStatus, String, String, String)};
 * cross-cutting additions (correlation id, catch-all 500) belong in those same helpers so every
 * error keeps one shape.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    static final String TYPE_PREFIX = "urn:ticketflow:problem:";

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ProblemDetail> handleValidation(WebExchangeBindException ex) {
        List<Violation> violations = ex.getFieldErrors().stream()
                .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(Violation::field).thenComparing(Violation::message))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed",
                "The request body has invalid fields");
        problem.setProperty("violations", violations);
        return respond(problem);
    }

    /** Malformed JSON, wrong types or unparseable values (for example a bad ISO-8601 instant). */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(ServerWebInputException ex) {
        return respond(problem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                "The request could not be read: check the JSON syntax and field formats"));
    }

    @ExceptionHandler(InvalidEventException.class)
    public ResponseEntity<ProblemDetail> handleInvalidEvent(InvalidEventException ex) {
        return respond(problem(HttpStatus.BAD_REQUEST, "invalid-event", "Invalid event", ex.getMessage()));
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(EventNotFoundException ex) {
        return respond(problem(HttpStatus.NOT_FOUND, "event-not-found", "Event not found", ex.getMessage()));
    }

    @ExceptionHandler(EventAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleAlreadyExists(EventAlreadyExistsException ex) {
        return respond(problem(HttpStatus.CONFLICT, "event-already-exists", "Event already exists", ex.getMessage()));
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ProblemDetail> handleInvalidIdempotencyKey(InvalidIdempotencyKeyException ex) {
        return respond(problem(HttpStatus.BAD_REQUEST, "invalid-idempotency-key", "Invalid Idempotency-Key", ex.getMessage()));
    }

    /** Fixed text: the exception message names the event and quantity, which are not needed here. */
    @ExceptionHandler(InsufficientInventoryException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientInventory(InsufficientInventoryException ex) {
        return respond(problem(HttpStatus.CONFLICT, "insufficient-inventory", "Insufficient inventory",
                "Not enough tickets are available for the requested quantity"));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        return respond(problem(HttpStatus.CONFLICT, "idempotency-key-reused", "Idempotency-Key reused",
                "This Idempotency-Key was already used with a different request; use a new key for a new request"));
    }

    @ExceptionHandler(IdempotentOrderNotActiveException.class)
    public ResponseEntity<ProblemDetail> handleOrderNotActive(IdempotentOrderNotActiveException ex) {
        return respond(problem(HttpStatus.CONFLICT, "idempotent-order-not-active", "Order no longer active",
                "The order created with this Idempotency-Key was released; retry with a new Idempotency-Key"));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleOrderNotFound(OrderNotFoundException ex) {
        return respond(problem(HttpStatus.NOT_FOUND, "order-not-found", "Order not found", ex.getMessage()));
    }

    /** The cause (queue failure) is logged by the use case and never exposed. */
    @ExceptionHandler(OrderEnqueueFailedException.class)
    public ResponseEntity<ProblemDetail> handleEnqueueFailed(OrderEnqueueFailedException ex) {
        return respond(problem(HttpStatus.SERVICE_UNAVAILABLE, "order-enqueue-failed", "Order could not be accepted",
                "The order could not be accepted right now; you may retry with a new Idempotency-Key"));
    }

    /**
     * Wraps a problem with an explicit {@code application/problem+json} content type, so content
     * negotiation never turns it into another format (for example {@code text/event-stream} when an
     * SSE client sends that {@code Accept} header and the failure happens before the stream starts).
     */
    static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    static ProblemDetail problem(HttpStatus status, String typeSuffix, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + typeSuffix));
        problem.setTitle(title);
        return problem;
    }
}
