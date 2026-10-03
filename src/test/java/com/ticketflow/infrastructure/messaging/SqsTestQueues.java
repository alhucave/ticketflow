package com.ticketflow.infrastructure.messaging;

import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Test helper that creates the orders queue exactly like {@code docker/localstack/init-queues.sh}:
 * a DLQ plus a main queue whose redrive policy points to it with the given {@code maxReceiveCount}.
 */
public final class SqsTestQueues {

    /** Same value as the compose init script ({@code ORDERS_MAX_RECEIVE_COUNT} default). */
    public static final int MAX_RECEIVE_COUNT = 3;

    public record Queues(String name, String url, String dlqUrl) { }

    private SqsTestQueues() {
    }

    public static Queues create(SqsAsyncClient sqs, String name) {
        var dlqUrl = sqs.createQueue(CreateQueueRequest.builder().queueName(name + "-dlq").build()).join().queueUrl();
        var dlqArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN).build()).join()
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        var redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"" + MAX_RECEIVE_COUNT + "\"}";
        var url = sqs.createQueue(CreateQueueRequest.builder().queueName(name)
                .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrive,
                        QueueAttributeName.VISIBILITY_TIMEOUT, "30")).build()).join().queueUrl();
        return new Queues(name, url, dlqUrl);
    }

    /** Visible + in-flight + delayed messages. */
    public static int total(SqsAsyncClient sqs, String queueUrl) {
        var attributes = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED).build()).join().attributes();
        return attributes.values().stream().mapToInt(Integer::parseInt).sum();
    }
}
