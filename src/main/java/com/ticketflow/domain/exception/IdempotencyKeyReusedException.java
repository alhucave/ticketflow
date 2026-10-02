package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.OrderId;

/** An idempotency key was reused with a different payload than the order it first created. */
public class IdempotencyKeyReusedException extends RuntimeException {

    private final IdempotencyKey key;
    private final OrderId orderId;

    public IdempotencyKeyReusedException(IdempotencyKey key, OrderId orderId) {
        super("Idempotency key already used with a different payload by order " + orderId.value());
        this.key = key;
        this.orderId = orderId;
    }

    public IdempotencyKey key() {
        return key;
    }

    public OrderId orderId() {
        return orderId;
    }
}
