package com.ticketflow.domain.port;

import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderAuditEntry;
import com.ticketflow.domain.model.TicketStatus;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * Output port for the multi-aggregate writes that must be atomic across inventory, order and audit
 * trail. Each method is a single all-or-nothing transaction, so a crash can never leave inventory
 * moved without the matching order state (or the reverse).
 */
public interface OrderPlacementRepository {

    /**
     * In one transaction: moves {@code order.quantity()} tickets {@code available -> reserved}
     * (conditional on enough availability, bumping the inventory version), creates the order and
     * writes the initial audit entry {@code AVAILABLE -> RESERVED}. The order must be in RESERVED.
     *
     * @return the order stored
     * @throws com.ticketflow.domain.exception.OrderAlreadyExistsException when the order id is taken
     *         (takes precedence over the inventory errors, so retries are recognised as such)
     * @throws com.ticketflow.domain.exception.InsufficientInventoryException when not enough tickets are available
     * @throws com.ticketflow.domain.exception.EventNotFoundException when the event has no inventory
     */
    Mono<Order> placeReservation(Order order, String actor);

    /**
     * In one transaction: moves the order {@code expected -> AVAILABLE} (conditional on its current
     * status, event and quantity), writes the audit entry carrying {@code reason}, and returns the
     * quantity to the inventory ({@code reserved -> available} for RESERVED,
     * {@code pendingConfirmation -> available} for PENDING_CONFIRMATION). A second call for the
     * same order fails with a conflict and changes nothing.
     *
     * @param order the order as read by the caller; its id, event and quantity drive the release
     * @param expected RESERVED or PENDING_CONFIRMATION
     * @return the audit entry written
     * @throws com.ticketflow.domain.exception.InvalidStateTransitionException when expected -> AVAILABLE is not allowed
     * @throws com.ticketflow.domain.exception.OrderNotFoundException when the order does not exist
     * @throws com.ticketflow.domain.exception.OrderStatusConflictException when the order is not in {@code expected}
     */
    Mono<OrderAuditEntry> releaseReservation(
            Order order, TicketStatus expected, String actor, String reason, Instant at);

    /**
     * In one transaction: moves {@code order.quantity()} tickets {@code available -> complimentary}
     * (conditional on enough availability, bumping the inventory version), creates the order in
     * COMPLIMENTARY and writes the audit entry {@code AVAILABLE -> COMPLIMENTARY} carrying
     * {@code actor} and the optional {@code reason}. The tickets leave the available pool for good:
     * COMPLIMENTARY is final and is never counted as sold.
     *
     * @param reason optional free text for the audit entry; {@code null} when none
     * @return the order stored
     * @throws com.ticketflow.domain.exception.OrderAlreadyExistsException when the order id is taken
     *         (takes precedence over the inventory errors, so retries are recognised as such)
     * @throws com.ticketflow.domain.exception.InsufficientInventoryException when not enough tickets are available
     * @throws com.ticketflow.domain.exception.EventNotFoundException when the event has no inventory
     */
    Mono<Order> issueComplimentary(Order order, String actor, String reason);
}
