package com.ticketflow.usecase;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;

/**
 * Point-in-time ticket availability of an event. {@code available} only counts tickets that can
 * still be bought: reserved and pending-confirmation tickets are reported separately.
 */
public record Availability(
        EventId eventId,
        int available,
        int reserved,
        int pendingConfirmation,
        int sold,
        int complimentary,
        int capacity) {

    static Availability from(Inventory inventory) {
        return new Availability(
                inventory.eventId(),
                inventory.available(),
                inventory.reserved(),
                inventory.pendingConfirmation(),
                inventory.sold(),
                inventory.complimentary(),
                inventory.capacity());
    }
}
