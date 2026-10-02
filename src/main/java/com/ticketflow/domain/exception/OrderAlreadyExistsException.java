package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.OrderId;

/** An order with the same id already exists; it is never overwritten. */
public class OrderAlreadyExistsException extends RuntimeException {

    private final OrderId orderId;

    public OrderAlreadyExistsException(OrderId orderId) {
        super("Order already exists: " + orderId.value());
        this.orderId = orderId;
    }

    public OrderId orderId() {
        return orderId;
    }
}
