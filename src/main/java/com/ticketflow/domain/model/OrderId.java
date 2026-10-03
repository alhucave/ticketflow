package com.ticketflow.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Identifier of an order. */
public record OrderId(String value) {

    public OrderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OrderId must not be blank");
        }
    }

    public static OrderId generate() {
        return new OrderId(UUID.randomUUID().toString());
    }

    /**
     * Derives the order id deterministically from the idempotency key (name-based UUID, version 5
     * layout over SHA-256), so the same key always maps to the same id and the order table's
     * primary-key uniqueness enforces "one order per key" without a secondary lookup.
     */
    public static OrderId fromIdempotencyKey(IdempotencyKey key) {
        return derive("ticketflow:order:", key);
    }

    /**
     * Same derivation under a different namespace, so a complimentary issuance and a purchase that
     * happen to use the same idempotency key can never map to the same order id.
     */
    public static OrderId complimentaryFromIdempotencyKey(IdempotencyKey key) {
        return derive("ticketflow:complimentary:", key);
    }

    private static OrderId derive(String namespace, IdempotencyKey key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((namespace + key.value()).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            long most = 0;
            long least = 0;
            for (int i = 0; i < 8; i++) {
                most = (most << 8) | (hash[i] & 0xff);
                least = (least << 8) | (hash[8 + i] & 0xff);
            }
            return new OrderId(new UUID(most, least).toString());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
