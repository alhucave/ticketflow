package com.ticketflow.domain.model;

import com.ticketflow.domain.exception.InvalidStateTransitionException;

/**
 * Lifecycle state of a ticket. Valid transitions:
 * AVAILABLE -> RESERVED | COMPLIMENTARY, RESERVED -> PENDING_CONFIRMATION | AVAILABLE,
 * PENDING_CONFIRMATION -> SOLD | AVAILABLE. SOLD and COMPLIMENTARY are final.
 */
public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    /** Final states have no outgoing transitions. */
    public boolean isFinal() {
        return switch (this) {
            case SOLD, COMPLIMENTARY -> true;
            case AVAILABLE, RESERVED, PENDING_CONFIRMATION -> false;
        };
    }

    /** Only SOLD counts as a sale. */
    public boolean isSale() {
        return this == SOLD;
    }

    public boolean canTransitionTo(TicketStatus target) {
        return switch (this) {
            case AVAILABLE -> target == RESERVED || target == COMPLIMENTARY;
            case RESERVED -> target == PENDING_CONFIRMATION || target == AVAILABLE;
            case PENDING_CONFIRMATION -> target == SOLD || target == AVAILABLE;
            case SOLD, COMPLIMENTARY -> false;
        };
    }

    /**
     * Pure transition function.
     *
     * @return the target state when the transition is allowed
     * @throws InvalidStateTransitionException otherwise
     */
    public TicketStatus transitionTo(TicketStatus target) {
        if (!canTransitionTo(target)) {
            throw new InvalidStateTransitionException(this, target);
        }
        return target;
    }
}
