package com.ticketflow.usecase;

import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.TicketStatus;
import java.time.Instant;

/**
 * Outcome of a purchase request. {@code replayed} is true when the idempotency key had already
 * created the order, which is returned as it currently stands (nothing was reserved again).
 */
public record RequestPurchaseResult(
        OrderId orderId, TicketStatus status, Instant reservationExpiresAt, boolean replayed) {

    static RequestPurchaseResult of(Order order, boolean replayed) {
        return new RequestPurchaseResult(order.id(), order.status(), order.reservationExpiresAt(), replayed);
    }
}
