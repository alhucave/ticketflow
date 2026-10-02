package com.ticketflow.infrastructure.persistence;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.exception.OrderAlreadyExistsException;
import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.exception.OrderStatusConflictException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderPlacementRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * DynamoDB adapter of {@link OrderPlacementRepository}: every operation is one
 * {@code TransactWriteItems}, so inventory, order and audit trail change together or not at all.
 *
 * <p>Items are ordered so that cancellation reasons can be mapped by index. Placement:
 * {@code [0] inventory update, [1] order put, [2] audit put}. Release:
 * {@code [0] order update, [1] audit put, [2] inventory update}.
 *
 * <p>Transient failures (throttling, {@code TransactionConflict} between concurrent transactions on
 * the same item) are retried with jittered exponential backoff; business outcomes never are. A
 * retry after an ambiguous outcome is safe: the order put is conditional on {@code attribute_not_exists},
 * so a transaction that did commit surfaces as {@link OrderAlreadyExistsException}.
 *
 * <p>Scalability note: all purchases of one event touch the same inventory item, so heavy
 * contention on a single event shows up as transaction conflicts (retried) rather than as
 * overselling; correctness does not depend on the retry budget, only success rate under bursts.
 */
@Repository
public class DynamoDbOrderPlacementRepository implements OrderPlacementRepository {

    static final int DEFAULT_MAX_RETRIES = 10;
    static final Duration DEFAULT_MIN_BACKOFF = Duration.ofMillis(20);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(1);

    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final String EVENT_ID = "eventId";
    private static final String QUANTITY = "quantity";
    private static final String STATUS = "status";
    private static final String VERSION = "version";
    private static final String AVAILABLE = "available";
    private static final String RESERVED = "reserved";
    private static final String PENDING_CONFIRMATION = "pendingConfirmation";

    private final DynamoDbAsyncClient client;
    private final Retry retry;

    @Autowired
    public DynamoDbOrderPlacementRepository(DynamoDbAsyncClient client) {
        this(client, DEFAULT_MAX_RETRIES, DEFAULT_MIN_BACKOFF);
    }

    DynamoDbOrderPlacementRepository(DynamoDbAsyncClient client, int maxRetries, Duration minBackoff) {
        this.client = client;
        this.retry = Retry.backoff(maxRetries, minBackoff)
                .maxBackoff(MAX_BACKOFF)
                .filter(DynamoDbOrderRepository::isTransient)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    @Override
    public Mono<Order> placeReservation(Order order, String actor) {
        return Mono.defer(() -> {
            if (order.status() != TicketStatus.RESERVED) {
                return Mono.error(new IllegalArgumentException(
                        "A placed order must be RESERVED but was " + order.status()));
            }
            // Validates actor and the AVAILABLE -> RESERVED transition before touching DynamoDB.
            OrderAuditEntry entry = new OrderAuditEntry(order.id(), order.createdAt(),
                    TicketStatus.AVAILABLE, TicketStatus.RESERVED, actor);
            TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                    .transactItems(
                            moveInventory(order.eventId(), order.quantity().value(), AVAILABLE, RESERVED),
                            TransactWriteItem.builder().put(Put.builder()
                                    .tableName(DynamoDbTables.ORDERS)
                                    .item(DynamoDbOrderRepository.toItem(order))
                                    .conditionExpression("attribute_not_exists(orderId)")
                                    .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                                    .build()).build(),
                            putAudit(entry))
                    .build();
            return execute(request).thenReturn(order)
                    .onErrorMap(TransactionCanceledException.class, error -> translatePlacement(error, order));
        });
    }

    @Override
    public Mono<OrderAuditEntry> releaseReservation(
            Order order, TicketStatus expected, String actor, String reason, Instant at) {
        return Mono.defer(() -> {
            // Validates expected -> AVAILABLE, actor, reason and nulls before touching DynamoDB.
            OrderAuditEntry entry = new OrderAuditEntry(order.id(), at, expected, TicketStatus.AVAILABLE, actor, reason);
            String source = switch (expected) {
                case RESERVED -> RESERVED;
                case PENDING_CONFIRMATION -> PENDING_CONFIRMATION;
                default -> throw new IllegalArgumentException("Cannot release a reservation in " + expected);
            };
            TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                    .transactItems(updateOrder(order, expected), putAudit(entry),
                            moveInventory(order.eventId(), order.quantity().value(), source, AVAILABLE))
                    .build();
            return execute(request).thenReturn(entry)
                    .onErrorMap(TransactionCanceledException.class, error -> translateRelease(error, order, expected));
        });
    }

    private Mono<?> execute(TransactWriteItemsRequest request) {
        return Mono.fromFuture(() -> client.transactWriteItems(request)).retryWhen(retry);
    }

    private static TransactWriteItem moveInventory(EventId eventId, int quantity, String source, String target) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(DynamoDbTables.INVENTORY)
                        .key(Map.of(EVENT_ID, DynamoDbOrderRepository.s(eventId.value())))
                        .updateExpression("SET #src = #src - :qty, #dst = #dst + :qty, #version = #version + :one")
                        .conditionExpression("attribute_exists(#key) AND #src >= :qty")
                        .expressionAttributeNames(Map.of(
                                "#key", EVENT_ID, "#src", source, "#dst", target, "#version", VERSION))
                        .expressionAttributeValues(Map.of(":qty", n(quantity), ":one", n(1)))
                        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                        .build())
                .build();
    }

    /** Guards on status plus event and quantity, so the caller's copy of the order cannot misdrive the inventory. */
    private static TransactWriteItem updateOrder(Order order, TicketStatus expected) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(DynamoDbTables.ORDERS)
                        .key(DynamoDbOrderRepository.key(order.id()))
                        .updateExpression("SET #status = :target")
                        .conditionExpression("attribute_exists(#id) AND #status = :expected"
                                + " AND #event = :event AND #qty = :qty")
                        .expressionAttributeNames(Map.of("#id", "orderId", "#status", STATUS,
                                "#event", EVENT_ID, "#qty", QUANTITY))
                        .expressionAttributeValues(Map.of(
                                ":expected", DynamoDbOrderRepository.s(expected.name()),
                                ":target", DynamoDbOrderRepository.s(TicketStatus.AVAILABLE.name()),
                                ":event", DynamoDbOrderRepository.s(order.eventId().value()),
                                ":qty", n(order.quantity().value())))
                        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                        .build())
                .build();
    }

    private static TransactWriteItem putAudit(OrderAuditEntry entry) {
        return TransactWriteItem.builder()
                .put(Put.builder().tableName(DynamoDbTables.ORDER_AUDIT)
                        .item(DynamoDbOrderRepository.auditItem(entry)).build())
                .build();
    }

    /** The order id wins over inventory outcomes: a retried request must be recognised as a replay. */
    private static Throwable translatePlacement(TransactionCanceledException error, Order order) {
        List<CancellationReason> reasons = error.cancellationReasons();
        if (isConditionFailure(reasons, 1)) {
            return new OrderAlreadyExistsException(order.id());
        }
        if (isConditionFailure(reasons, 0)) {
            return hasItem(reasons.get(0))
                    ? new InsufficientInventoryException(order.eventId(), order.quantity())
                    : new EventNotFoundException(order.eventId());
        }
        return error;
    }

    private static Throwable translateRelease(TransactionCanceledException error, Order order, TicketStatus expected) {
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
            return new IllegalStateException("Inventory counter for event " + order.eventId().value()
                    + " cannot take the release of order " + order.id().value() + "; counters are inconsistent");
        }
        return error;
    }

    private static boolean isConditionFailure(List<CancellationReason> reasons, int index) {
        return reasons.size() > index && CONDITIONAL_CHECK_FAILED.equals(reasons.get(index).code());
    }

    private static boolean hasItem(CancellationReason reason) {
        return reason.hasItem() && !reason.item().isEmpty();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
