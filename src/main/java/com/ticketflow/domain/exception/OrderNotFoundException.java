package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.OrderId;

/** The requested order does not exist. */
public class OrderNotFoundException extends RuntimeException {

    private final OrderId orderId;

    public OrderNotFoundException(OrderId orderId) {
        super("Order not found: " + orderId.value());
        this.orderId = orderId;
    }

    public OrderId orderId() {
        return orderId;
    }
}
