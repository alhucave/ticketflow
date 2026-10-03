package com.ticketflow.usecase;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Quantity;

/**
 * Request to issue {@code quantity} complimentary tickets of an event; retries must carry the same key.
 * {@code reason} is optional short free text (recipient, campaign...) stored verbatim in the audit
 * entry; it is trimmed and a blank value becomes {@code null}. It is data, never markup: callers must
 * not render it as HTML.
 */
public record IssueComplimentaryCommand(
        EventId eventId, Quantity quantity, IdempotencyKey idempotencyKey, String reason) {

    public static final int MAX_QUANTITY = 1000;
    public static final int MAX_REASON_LENGTH = 200;

    public IssueComplimentaryCommand {
        if (eventId == null || quantity == null || idempotencyKey == null) {
            throw new IllegalArgumentException(
                    "IssueComplimentaryCommand eventId, quantity and idempotencyKey must not be null");
        }
        if (quantity.value() > MAX_QUANTITY) {
            throw new IllegalArgumentException("Complimentary quantity must be at most " + MAX_QUANTITY);
        }
        reason = reason == null || reason.isBlank() ? null : reason.strip();
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Complimentary reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
    }
}
