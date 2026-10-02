package com.ticketflow.usecase;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Quantity;

/** Request to buy {@code quantity} tickets of an event; retries must carry the same key. */
public record RequestPurchaseCommand(EventId eventId, Quantity quantity, IdempotencyKey idempotencyKey) {

    public RequestPurchaseCommand {
        if (eventId == null || quantity == null || idempotencyKey == null) {
            throw new IllegalArgumentException(
                    "RequestPurchaseCommand eventId, quantity and idempotencyKey must not be null");
        }
    }
}
