package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.OrderId;

/** The reservation backing an order has expired and can no longer be confirmed. */
public class ReservationExpiredException extends RuntimeException {

    private final OrderId orderId;

    public ReservationExpiredException(OrderId orderId) {
        super("Reservation expired for order: " + orderId.value());
        this.orderId = orderId;
    }

    public OrderId orderId() {
        return orderId;
    }
}
