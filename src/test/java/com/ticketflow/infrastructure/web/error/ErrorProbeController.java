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
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.infrastructure.web.InvalidIdempotencyKeyException;
import com.ticketflow.infrastructure.web.InvalidPathIdException;
import com.ticketflow.infrastructure.web.InvalidRequestFieldException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Test-only controller that throws arbitrary exceptions so every mapping can be exercised over HTTP. */
@RestController
class ErrorProbeController {

    static final String SECRET = "secret-internal-detail-4711";
    private static final Logger LOG = LoggerFactory.getLogger(ErrorProbeController.class);

    @GetMapping("/probe/ok")
    Mono<Map<String, String>> ok() {
        return Mono.just(Map.of("status", "fine"));
    }

    /** Logs after hopping threads, to prove the correlation id follows the request. */
    @GetMapping("/probe/log/{delayMs}")
    Mono<Map<String, String>> log(@PathVariable long delayMs,
                                  @RequestHeader(CorrelationId.HEADER) String claimed) {
        return Mono.delay(Duration.ofMillis(delayMs))
                .publishOn(Schedulers.boundedElastic())
                .map(tick -> {
                    LOG.info("probe-log-line claimed={}", claimed);
                    return Map.of("status", "logged");
                });
    }

    @PostMapping(value = "/probe/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<Map<String, String>> body(@RequestBody Map<String, String> body) {
        return Mono.just(body);
    }

    @GetMapping("/probe/throw/{kind}")
    Mono<Void> fail(@PathVariable String kind) {
        return Mono.error(switch (kind) {
            case "unexpected" -> new IllegalStateException(SECRET);
            case "unexpected-sync-cause" -> new RuntimeException(SECRET, new IllegalArgumentException(SECRET));
            case "error" -> new StackOverflowError(SECRET);
            case "invalid-event" -> new InvalidEventException("name must not be blank");
            case "invalid-key" -> new InvalidIdempotencyKeyException("Idempotency-Key header is required");
            case "event-not-found" -> new EventNotFoundException(new EventId("evt-1"));
            case "order-not-found" -> new OrderNotFoundException(new OrderId("ord-1"));
            case "event-exists" -> new EventAlreadyExistsException(new EventId("evt-1"));
            case "insufficient" -> new InsufficientInventoryException(new EventId("evt-1"), new Quantity(3));
            case "key-reused" -> new IdempotencyKeyReusedException(new IdempotencyKey("k1"), new OrderId("ord-1"));
            case "not-active" -> new IdempotentOrderNotActiveException(new IdempotencyKey("k1"), new OrderId("ord-1"));
            case "enqueue" -> new OrderEnqueueFailedException(new OrderId("ord-1"), true, new RuntimeException(SECRET));
            case "expired" -> new ReservationExpiredException(new OrderId("ord-1"));
            case "concurrent" -> new ConcurrentInventoryModificationException(new EventId("evt-1"), 7);
            case "transition" -> new InvalidStateTransitionException(TicketStatus.SOLD, TicketStatus.AVAILABLE);
            case "status-conflict" -> new OrderStatusConflictException(new OrderId("ord-1"), TicketStatus.RESERVED,
                    TicketStatus.SOLD);
            case "order-exists" -> new OrderAlreadyExistsException(new OrderId("ord-1"));
            case "rate-limit" -> new RateLimitExceededException(Duration.ofMillis(1500));
            case "rate-limit-unknown" -> new RateLimitExceededException();
            case "bad-path-id" -> new InvalidPathIdException();
            case "bad-field" -> new InvalidRequestFieldException("quantity", "must be less than or equal to 3");
            case "throttled" -> ProvisionedThroughputExceededException.builder().message(SECRET).build();
            case "aws-503" -> AwsServiceException.builder().message(SECRET).statusCode(503).build();
            case "aws-429" -> AwsServiceException.builder().message(SECRET).statusCode(429).build();
            case "aws-400" -> AwsServiceException.builder().message(SECRET).statusCode(400).build();
            case "conditional-failed" -> ConditionalCheckFailedException.builder().message(SECRET).build();
            case "tx-conflict" -> TransactionCanceledException.builder().message(SECRET).cancellationReasons(
                    CancellationReason.builder().code("TransactionConflict").build()).build();
            case "sdk-client" -> SdkClientException.create(SECRET);
            case "timeout" -> new TimeoutException(SECRET);
            case "retry-exhausted" -> Exceptions.retryExhausted(SECRET, new IllegalStateException(SECRET));
            case "status-418" -> new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT, SECRET);
            case "status-503" -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, SECRET);
            case "status-413" -> new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, SECRET);
            case "status-400" -> new ResponseStatusException(HttpStatus.BAD_REQUEST, SECRET);
            default -> new IllegalArgumentException("unknown probe kind");
        });
    }
}
