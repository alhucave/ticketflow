package com.ticketflow.domain.port;

import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * Output port for the atomic steps that turn a reservation into a sale. Like
 * {@link OrderPlacementRepository}, each method is ONE all-or-nothing transaction covering the order
 * status change (conditional on the stored status), its audit entry and the matching inventory
 * counter move, so a crash can never leave a sale without inventory movement or the reverse.
 *
 * <p>The {@code order} argument is the order as read by the caller: its id, event and quantity drive
 * the write (and are guarded by the condition); its status is not trusted, the stored one is checked.
 */
public interface OrderFulfillmentRepository {

    /**
     * Moves the order {@code RESERVED -> PENDING_CONFIRMATION} and the inventory
     * {@code reserved -> pendingConfirmation}.
     *
     * @return the audit entry written
     * @throws com.ticketflow.domain.exception.OrderNotFoundException when the order does not exist
     * @throws com.ticketflow.domain.exception.OrderStatusConflictException when the order is not RESERVED
     * @throws com.ticketflow.domain.exception.InsufficientInventoryException when the reserved counter holds fewer tickets than the order
     * @throws com.ticketflow.domain.exception.EventNotFoundException when the event has no inventory
     */
    Mono<OrderAuditEntry> markPendingConfirmation(Order order, String actor, Instant at);

    /**
     * Moves the order {@code PENDING_CONFIRMATION -> SOLD} and the inventory
     * {@code pendingConfirmation -> sold}. SOLD is final: a second call fails with a conflict and
     * changes nothing.
     *
     * @return the audit entry written
     * @throws com.ticketflow.domain.exception.OrderNotFoundException when the order does not exist
     * @throws com.ticketflow.domain.exception.OrderStatusConflictException when the order is not PENDING_CONFIRMATION
     * @throws com.ticketflow.domain.exception.InsufficientInventoryException when the pending counter holds fewer tickets than the order
     * @throws com.ticketflow.domain.exception.EventNotFoundException when the event has no inventory
     */
    Mono<OrderAuditEntry> confirmSale(Order order, String actor, Instant at);
}
