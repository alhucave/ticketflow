package com.ticketflow.domain.exception;

import com.ticketflow.domain.model.OrderId;

/**
 * The order could not be enqueued for processing. {@code reservationReleased} tells whether the
 * compensation succeeded (inventory back to available); when it is {@code false} the release error
 * is attached as a suppressed exception and the reservation will be reclaimed by the expiry sweep.
 */
public class OrderEnqueueFailedException extends RuntimeException {

    private final OrderId orderId;
    private final boolean reservationReleased;

    public OrderEnqueueFailedException(OrderId orderId, boolean reservationReleased, Throwable cause) {
        super("Order " + orderId.value() + " could not be enqueued; reservation "
                + (reservationReleased ? "released" : "NOT released"), cause);
        this.orderId = orderId;
        this.reservationReleased = reservationReleased;
    }

    public OrderId orderId() {
        return orderId;
    }

    public boolean reservationReleased() {
        return reservationReleased;
    }
}
