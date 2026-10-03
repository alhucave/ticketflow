package com.ticketflow.infrastructure.web;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.usecase.Availability;
import com.ticketflow.usecase.OrderStatusView;
import com.ticketflow.usecase.RequestPurchaseCommand;
import com.ticketflow.usecase.RequestPurchaseResult;
import java.time.Instant;
import java.util.regex.Pattern;

/** Maps between the orders/availability DTOs and the use-case types. */
final class OrderMapper {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final Pattern KEY_CHARSET = Pattern.compile("[A-Za-z0-9._:-]+");

    private OrderMapper() {}

    /**
     * Shortest accepted key: with the orderId derived from the key alone, short keys are guessable
     * ("1", "abc") and would let one client collide with or probe another's order. 16 characters of the
     * allowed charset (a UUID is 36) is the floor. Enforced here, not in {@code IdempotencyKey}, so that
     * orders already stored with older, shorter keys can still be read back.
     */
    static final int KEY_MIN_LENGTH = 16;

    /**
     * Validates the raw header: present, non-blank, 16 to 128 characters, charset {@code [A-Za-z0-9._:-]}.
     * Messages are fixed text and never echo the supplied value.
     */
    static IdempotencyKey toKey(String header) {
        if (header == null || header.isBlank()) {
            throw new InvalidIdempotencyKeyException(IDEMPOTENCY_KEY_HEADER + " header is required");
        }
        if (header.length() < KEY_MIN_LENGTH) {
            throw new InvalidIdempotencyKeyException(IDEMPOTENCY_KEY_HEADER + " must be at least "
                    + KEY_MIN_LENGTH + " characters");
        }
        if (header.length() > IdempotencyKey.MAX_LENGTH) {
            throw new InvalidIdempotencyKeyException(IDEMPOTENCY_KEY_HEADER + " must be at most "
                    + IdempotencyKey.MAX_LENGTH + " characters");
        }
        if (!KEY_CHARSET.matcher(header).matches()) {
            throw new InvalidIdempotencyKeyException(
                    IDEMPOTENCY_KEY_HEADER + " may only contain letters, digits and . _ : -");
        }
        return new IdempotencyKey(header);
    }

    static RequestPurchaseCommand toCommand(PurchaseRequest request, IdempotencyKey key) {
        return new RequestPurchaseCommand(new EventId(request.eventId()), new Quantity(request.quantity()), key);
    }

    static PurchaseAcceptedResponse toResponse(RequestPurchaseResult result) {
        return new PurchaseAcceptedResponse(result.orderId().value(), result.status().name(),
                visibleExpiry(result.status(), result.reservationExpiresAt()));
    }

    static OrderStatusResponse toResponse(OrderStatusView view) {
        return new OrderStatusResponse(view.orderId().value(), view.eventId().value(), view.quantity().value(),
                view.status().name(), visibleExpiry(view.status(), view.reservationExpiresAt()), view.createdAt());
    }

    static AvailabilityResponse toResponse(Availability availability) {
        return new AvailabilityResponse(availability.available(), availability.reserved(),
                availability.pendingConfirmation(), availability.sold(), availability.complimentary(),
                availability.capacity());
    }

    /** Only RESERVED and PENDING_CONFIRMATION orders hold a reservation that can still expire. */
    private static Instant visibleExpiry(TicketStatus status, Instant expiresAt) {
        return switch (status) {
            case RESERVED, PENDING_CONFIRMATION -> expiresAt;
            case SOLD, COMPLIMENTARY, AVAILABLE -> null;
        };
    }
}
