package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.OrderId;

/**
 * The order tied to an idempotency key was released (compensated after a failed enqueue, or
 * expired) and is back to AVAILABLE. The key is consumed; the client must retry with a new key.
 */
public class IdempotentOrderNotActiveException extends RuntimeException {

    private final IdempotencyKey key;
    private final OrderId orderId;

    public IdempotentOrderNotActiveException(IdempotencyKey key, OrderId orderId) {
        super("Order " + orderId.value() + " for this idempotency key was released; retry with a new key");
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
