package com.ticketflow.infrastructure.persistence;

import static com.ticketflow.infrastructure.persistence.DynamoDbOrderPlacementRepository.hasItem;
import static com.ticketflow.infrastructure.persistence.DynamoDbOrderPlacementRepository.isConditionFailure;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderFulfillmentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * DynamoDB adapter of {@link OrderFulfillmentRepository}: every step is one
 * {@code TransactWriteItems} with items {@code [0] order update (conditional on the expected status,
 * event and quantity), [1] audit put, [2] inventory update (conditional on source >= quantity, version + 1)}.
 * It reuses the item builders of {@link DynamoDbOrderPlacementRepository}.
 *
 * <p>Order condition failures map to {@link OrderNotFoundException} / {@link OrderStatusConflictException}
 * (a concurrent worker won); they take precedence over inventory ones. Transient failures
 * (throttling, transaction conflicts) are retried with jittered backoff; business outcomes never are.
 * A retry after an ambiguous commit is safe: it then fails the status condition, never applies twice.
 */
@Repository
public class DynamoDbOrderFulfillmentRepository implements OrderFulfillmentRepository {

    private static final String RESERVED = "reserved";
    private static final String PENDING_CONFIRMATION = "pendingConfirmation";
    private static final String SOLD = "sold";
    private static final String STATUS = "status";

    private final DynamoDbAsyncClient client;
    private final Retry retry;

    @Autowired
    public DynamoDbOrderFulfillmentRepository(DynamoDbAsyncClient client) {
        this(client, DynamoDbOrderPlacementRepository.DEFAULT_MAX_RETRIES,
                DynamoDbOrderPlacementRepository.DEFAULT_MIN_BACKOFF);
    }

    DynamoDbOrderFulfillmentRepository(DynamoDbAsyncClient client, int maxRetries, Duration minBackoff) {
        this.client = client;
        this.retry = Retry.backoff(maxRetries, minBackoff)
                .maxBackoff(DynamoDbOrderPlacementRepository.MAX_BACKOFF)
                .filter(DynamoDbOrderRepository::isTransient)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    @Override
    public Mono<OrderAuditEntry> markPendingConfirmation(Order order, String actor, Instant at) {
        return step(order, actor, at, TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION,
                RESERVED, PENDING_CONFIRMATION);
    }

    @Override
    public Mono<OrderAuditEntry> confirmSale(Order order, String actor, Instant at) {
        return step(order, actor, at, TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD,
                PENDING_CONFIRMATION, SOLD);
    }

    private Mono<OrderAuditEntry> step(Order order, String actor, Instant at, TicketStatus from, TicketStatus to,
                                       String sourceCounter, String targetCounter) {
        return Mono.defer(() -> {
            // Validates the transition, actor and nulls before touching DynamoDB.
            OrderAuditEntry entry = new OrderAuditEntry(order.id(), at, from, to, actor);
            TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                    .transactItems(
                            DynamoDbOrderPlacementRepository.updateOrder(order, from, to),
                            DynamoDbOrderPlacementRepository.putAudit(entry),
                            DynamoDbOrderPlacementRepository.moveInventory(
                                    order.eventId(), order.quantity().value(), sourceCounter, targetCounter))
                    .build();
            return Mono.fromFuture(() -> client.transactWriteItems(request)).retryWhen(retry)
                    .thenReturn(entry)
                    .onErrorMap(TransactionCanceledException.class, error -> translate(error, order, from));
        });
    }

    private static Throwable translate(TransactionCanceledException error, Order order, TicketStatus expected) {
        List<CancellationReason> reasons = error.cancellationReasons();
        if (isConditionFailure(reasons, 0)) {
            CancellationReason reason = reasons.get(0);
            if (!hasItem(reason)) {
                return new OrderNotFoundException(order.id());
            }
            AttributeValue status = reason.item().get(STATUS);
            return new OrderStatusConflictException(order.id(), expected,
                    status == null ? null : TicketStatus.valueOf(status.s()));
        }
        if (isConditionFailure(reasons, 2)) {
            return hasItem(reasons.get(2))
                    ? new InsufficientInventoryException(order.eventId(), order.quantity())
                    : new EventNotFoundException(order.eventId());
        }
        return error;
    }
}
