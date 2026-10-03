package com.ticketflow.infrastructure.web.error;

import com.ticketflow.domain.exception.ConcurrentInventoryModificationException;
import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.IdempotencyKeyReusedException;
import com.ticketflow.domain.exception.IdempotentOrderNotActiveException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.InvalidEventException;
import com.ticketflow.domain.exception.InvalidStateTransitionException;
import com.ticketflow.domain.exception.OrderAlreadyExistsException;
import com.ticketflow.domain.exception.OrderEnqueueFailedException;
import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.exception.ReservationExpiredException;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.infrastructure.web.InvalidIdempotencyKeyException;
import com.ticketflow.infrastructure.web.InvalidPathIdException;
import com.ticketflow.infrastructure.web.InvalidRequestFieldException;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Translates every error into one RFC 7807 shape ({@code application/problem+json}): {@code type}
 * ({@code urn:ticketflow:problem:*}), {@code title}, {@code status}, {@code detail}, {@code instance}
 * ({@code urn:ticketflow:request:<correlationId>}), {@code correlationId} and, for validation errors,
 * {@code violations}. Messages are fixed or come from domain exceptions designed to be client-safe;
 * stack traces, exception messages of unexpected errors and internals are never exposed.
 *
 * <p>{@link #translate(Throwable, String)} is the single mapping point. It is used by this controller
 * advice (errors raised inside handlers) and by {@link ProblemWebExceptionHandler} (errors raised
 * outside the dispatcher: unknown route, wrong method, filters). To add a case, add a branch to
 * {@code map(...)} that builds its problem with {@link #problem(HttpStatusCode, String, String, String)}.
 *
 * <p>Retry policy at this boundary: nothing is retried here (a web handler cannot know whether a
 * request is safe to replay). Transient failures are retried close to the adapter (for example the SQS
 * publisher uses {@code Retry.backoff} for throttling and connection errors) and the client is told when
 * a retry makes sense with {@code Retry-After} ({@code 429}, {@code 409} concurrent modification).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    static final String TYPE_PREFIX = "urn:ticketflow:problem:";
    static final String INSTANCE_PREFIX = "urn:ticketflow:request:";
    static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred. Quote the correlationId when reporting it";

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    /** Headers of framework exceptions that are meaningful to clients and safe to forward. */
    private static final Set<String> FORWARDED_HEADERS = Set.of(HttpHeaders.ALLOW, HttpHeaders.ACCEPT,
            HttpHeaders.RETRY_AFTER);
    /** Suggested wait after losing an optimistic-locking race (transient contention). */
    private static final Duration CONCURRENT_RETRY_AFTER = Duration.ofSeconds(1);
    /** Suggested wait when a dependency (DynamoDB, SQS) is throttling or unreachable. */
    private static final Duration UNAVAILABLE_RETRY_AFTER = Duration.ofSeconds(5);

    private final OperationalMetrics metrics;

    public ApiExceptionHandler() {
        this.metrics = OperationalMetrics.NOOP;
    }

    @Autowired
    public ApiExceptionHandler(ObjectProvider<OperationalMetrics> metrics) {
        this(metrics.getIfAvailable(() -> OperationalMetrics.NOOP));
    }

    public ApiExceptionHandler(OperationalMetrics metrics) {
        this.metrics = metrics;
    }

    /** Catch-all for anything raised inside a controller: mapped when known, otherwise a generic 500. */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> handle(Throwable ex, ServerWebExchange exchange) {
        return translate(ex, CorrelationId.of(exchange), metrics);
    }

    /** A problem plus the response headers it needs (for example {@code Allow}, {@code Retry-After}). */
    private record Mapped(ProblemDetail problem, HttpHeaders headers) {
        Mapped(ProblemDetail problem) {
            this(problem, new HttpHeaders());
        }
    }

    static ResponseEntity<ProblemDetail> translate(Throwable ex, String correlationId) {
        return translate(ex, correlationId, OperationalMetrics.NOOP);
    }

    static ResponseEntity<ProblemDetail> translate(Throwable ex, String correlationId, OperationalMetrics metrics) {
        Mapped mapped = map(ex, correlationId, metrics);
        ProblemDetail problem = mapped.problem();
        problem.setInstance(URI.create(INSTANCE_PREFIX + correlationId));
        problem.setProperty(CorrelationId.KEY, correlationId);
        // Never logs the exception message of client errors (it may echo user input); status and type suffice.
        if (problem.getStatus() < 500) {
            withCorrelationId(correlationId, () -> LOG.info("Request rejected: status={} type={}", problem.getStatus(),
                    problem.getType()));
        }
        return respond(problem, mapped.headers());
    }

    private static Mapped map(Throwable ex, String correlationId, OperationalMetrics metrics) {
        return switch (ex) {
            case WebExchangeBindException bind -> validation(bind);
            /* Malformed JSON, wrong types or unparseable values (for example a bad ISO-8601 instant). */
            case ServerWebInputException input -> new Mapped(problem(HttpStatus.BAD_REQUEST, "malformed-request",
                    "Malformed request",
                    "The request could not be read: check the JSON syntax and field formats"));
            case InvalidRequestFieldException e -> validation(List.of(new Violation(e.field(), e.getMessage())));
            /* Fixed text: a path id that cannot exist is never echoed back. */
            case InvalidPathIdException e -> new Mapped(problem(HttpStatus.NOT_FOUND, "not-found", "Not found",
                    "The requested resource was not found"));
            case InvalidEventException e -> new Mapped(problem(HttpStatus.BAD_REQUEST, "invalid-event",
                    "Invalid event", e.getMessage()));
            case InvalidIdempotencyKeyException e -> new Mapped(problem(HttpStatus.BAD_REQUEST,
                    "invalid-idempotency-key", "Invalid Idempotency-Key", e.getMessage()));
            case EventNotFoundException e -> new Mapped(problem(HttpStatus.NOT_FOUND, "event-not-found",
                    "Event not found", e.getMessage()));
            case OrderNotFoundException e -> new Mapped(problem(HttpStatus.NOT_FOUND, "order-not-found",
                    "Order not found", e.getMessage()));
            case EventAlreadyExistsException e -> new Mapped(problem(HttpStatus.CONFLICT, "event-already-exists",
                    "Event already exists", e.getMessage()));
            /* Fixed text: the exception message names the event and quantity, which are not needed here. */
            case InsufficientInventoryException e -> new Mapped(problem(HttpStatus.CONFLICT,
                    "insufficient-inventory", "Insufficient inventory",
                    "Not enough tickets are available for the requested quantity"));
            case IdempotencyKeyReusedException e -> new Mapped(problem(HttpStatus.CONFLICT,
                    "idempotency-key-reused", "Idempotency-Key reused",
                    "This Idempotency-Key was already used with a different request; use a new key for a new request"));
            case IdempotentOrderNotActiveException e -> new Mapped(problem(HttpStatus.CONFLICT,
                    "idempotent-order-not-active", "Order no longer active",
                    "The order created with this Idempotency-Key was released; retry with a new Idempotency-Key"));
            /*
             * Transient contention (optimistic locking lost the race after the inventory use cases gave up
             * retrying). 409 rather than 503: the request conflicted with a concurrent change and was NOT
             * applied, and it is safe to replay (purchases carry an Idempotency-Key), so the client gets
             * a Retry-After hint. 503 is kept for "the service cannot accept work", e.g. queue down.
             */
            case ConcurrentInventoryModificationException e -> concurrentModification();
            case InvalidStateTransitionException e -> new Mapped(problem(HttpStatus.CONFLICT,
                    "invalid-state-transition", "Invalid state transition",
                    "The requested change is not allowed in the current state"));
            case OrderStatusConflictException e -> new Mapped(problem(HttpStatus.CONFLICT, "order-status-conflict",
                    "Order status conflict",
                    "The order changed concurrently or is no longer in the expected status"));
            case OrderAlreadyExistsException e -> new Mapped(problem(HttpStatus.CONFLICT, "order-already-exists",
                    "Order already exists", "An order with this identifier already exists"));
            case ReservationExpiredException e -> new Mapped(problem(HttpStatus.GONE, "reservation-expired",
                    "Reservation expired", "The reservation for this order has expired and can no longer be confirmed"));
            case RateLimitExceededException e -> rateLimited(e);
            case AdminAccessDeniedException e -> adminDenied(e);
            /* The cause (queue failure) is logged by the use case and never exposed. */
            case OrderEnqueueFailedException e -> new Mapped(problem(HttpStatus.SERVICE_UNAVAILABLE,
                    "order-enqueue-failed", "Order could not be accepted",
                    "The order could not be accepted right now; you may retry with a new Idempotency-Key"));
            case ErrorResponse framework -> framework(framework, ex, correlationId);
            /* After every business mapping: only a failure that is none of them can be an outage. */
            case Throwable transientFailure when TransientFailures.isTransient(transientFailure) ->
                    unavailable(transientFailure, correlationId, metrics);
            default -> internal(HttpStatus.INTERNAL_SERVER_ERROR, ex, correlationId);
        };
    }

    private static Mapped validation(WebExchangeBindException ex) {
        return validation(ex.getFieldErrors().stream()
                .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
                .toList());
    }

    private static Mapped validation(List<Violation> unsorted) {
        List<Violation> violations = unsorted.stream()
                .sorted(Comparator.comparing(Violation::field).thenComparing(Violation::message))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed",
                "The request body has invalid fields");
        problem.setProperty("violations", violations);
        return new Mapped(problem);
    }

    private static Mapped concurrentModification() {
        Mapped mapped = new Mapped(problem(HttpStatus.CONFLICT, "concurrent-modification",
                "Concurrent modification",
                "The resource was modified concurrently; retry the request shortly"));
        mapped.headers().set(HttpHeaders.RETRY_AFTER, seconds(CONCURRENT_RETRY_AFTER));
        return mapped;
    }

    /** Fixed texts: never a hint about the key, only whether the route is closed (403) or needs credentials (401). */
    private static Mapped adminDenied(AdminAccessDeniedException ex) {
        if (ex.isDisabled()) {
            return new Mapped(problem(HttpStatus.FORBIDDEN, "admin-disabled", "Admin access disabled",
                    "Admin operations are not available"));
        }
        Mapped mapped = new Mapped(problem(HttpStatus.UNAUTHORIZED, "admin-unauthorized", "Unauthorized",
                "Valid admin credentials are required"));
        mapped.headers().set(HttpHeaders.WWW_AUTHENTICATE, "ApiKey");
        return mapped;
    }

    /**
     * A dependency is throttling, timing out or unreachable (retries at the adapter are already spent):
     * {@code 503} + {@code Retry-After}. The request may have had no effect or only part of one, but every
     * write path is idempotent or compensating, so retrying is safe. The cause is logged by class only.
     */
    private static Mapped unavailable(Throwable ex, String correlationId, OperationalMetrics metrics) {
        metrics.dependencyUnavailable();
        withCorrelationId(correlationId, () -> LOG.warn("Dependency unavailable ({}); answering 503",
                ex.getClass().getSimpleName()));
        Mapped mapped = new Mapped(problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                "Service temporarily unavailable",
                "A required service is temporarily unavailable; retry shortly"));
        mapped.headers().set(HttpHeaders.RETRY_AFTER, seconds(UNAVAILABLE_RETRY_AFTER));
        return mapped;
    }

    private static Mapped rateLimited(RateLimitExceededException ex) {
        Mapped mapped = new Mapped(problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded",
                "Too many requests", "Rate limit exceeded; retry later"));
        if (ex.retryAfter() != null) {
            mapped.headers().set(HttpHeaders.RETRY_AFTER, seconds(ex.retryAfter()));
        }
        return mapped;
    }

    /** Whole seconds, rounded up, at least 1. */
    private static String seconds(Duration duration) {
        long millis = Math.max(duration.toMillis(), 0);
        return Long.toString(Math.max(1, (millis + 999) / 1000));
    }

    /** Errors raised by Spring itself (routing, content negotiation, size limits, {@code ResponseStatusException}). */
    private static Mapped framework(ErrorResponse error, Throwable ex, String correlationId) {
        HttpStatusCode status = error.getStatusCode();
        if (status.is5xxServerError()) {
            return internal(status, ex, correlationId);
        }
        Mapped mapped = switch (status.value()) {
            case 400 -> new Mapped(problem(status, "bad-request", "Bad request", "The request is invalid"));
            case 404 -> new Mapped(problem(status, "not-found", "Not found", "The requested resource was not found"));
            case 405 -> new Mapped(problem(status, "method-not-allowed", "Method not allowed",
                    "The HTTP method is not supported for this resource"));
            case 406 -> new Mapped(problem(status, "not-acceptable", "Not acceptable",
                    "The requested representation is not available"));
            case 413 -> new Mapped(problem(status, "payload-too-large", "Payload too large",
                    "The request body exceeds the maximum allowed size"));
            case 415 -> new Mapped(problem(status, "unsupported-media-type", "Unsupported media type",
                    "The request content type is not supported"));
            default -> new Mapped(problem(status, "client-error", "Request rejected",
                    "The request could not be processed"));
        };
        error.getHeaders().forEach((name, values) -> {
            if (FORWARDED_HEADERS.stream().anyMatch(name::equalsIgnoreCase)) {
                mapped.headers().put(name, values);
            }
        });
        return mapped;
    }

    /** Fixed generic body; the real error goes to the server log (stack trace and correlation id) only. */
    private static Mapped internal(HttpStatusCode status, Throwable ex, String correlationId) {
        withCorrelationId(correlationId,
                () -> LOG.error("Unhandled error while processing request (correlationId={})", correlationId, ex));
        return new Mapped(problem(status, "internal-error", "Internal server error", INTERNAL_ERROR_DETAIL));
    }

    /** Runs {@code action} with the id in the MDC even if the signal arrived without propagated context. */
    private static void withCorrelationId(String correlationId, Runnable action) {
        String previous = MDC.get(CorrelationId.KEY);
        MDC.put(CorrelationId.KEY, correlationId);
        try {
            action.run();
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationId.KEY);
            } else {
                MDC.put(CorrelationId.KEY, previous);
            }
        }
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem, HttpHeaders headers) {
        // Explicit problem+json so content negotiation never turns it into another format (for example
        // text/event-stream when an SSE client fails before the stream starts).
        return ResponseEntity.status(problem.getStatus()).headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    static ProblemDetail problem(HttpStatusCode status, String typeSuffix, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + typeSuffix));
        problem.setTitle(title);
        return problem;
    }
}
