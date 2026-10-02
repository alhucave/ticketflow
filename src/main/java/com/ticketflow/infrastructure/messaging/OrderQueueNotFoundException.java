package com.ticketflow.infrastructure.messaging;

/** The configured orders queue does not exist (or cannot be resolved). */
public class OrderQueueNotFoundException extends RuntimeException {

    public OrderQueueNotFoundException(String queueName, Throwable cause) {
        super("SQS queue '" + queueName + "' does not exist or cannot be resolved; "
                + "check ticketflow.sqs.orders-queue-name / orders-queue-url and the endpoint", cause);
    }
}
