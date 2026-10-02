package com.ticketflow.infrastructure.messaging;

/**
 * Versioned body of the message published for each accepted order. It carries only what the
 * consumer needs: the order is re-read from DynamoDB by id, so state never travels in the queue.
 */
public record OrderQueueMessage(int version, String orderId) {

    public static final int CURRENT_VERSION = 1;

    public static OrderQueueMessage of(String orderId) {
        return new OrderQueueMessage(CURRENT_VERSION, orderId);
    }
}
