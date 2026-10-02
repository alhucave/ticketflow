package com.ticketflow.usecase;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import java.time.Instant;

/** Read model with the current status of an order. */
public record OrderStatusView(
        OrderId orderId,
        EventId eventId,
        Quantity quantity,
        TicketStatus status,
        Instant reservationExpiresAt,
        Instant createdAt) {

    static OrderStatusView from(Order order) {
        return new OrderStatusView(order.id(), order.eventId(), order.quantity(), order.status(),
                order.reservationExpiresAt(), order.createdAt());
    }
}
