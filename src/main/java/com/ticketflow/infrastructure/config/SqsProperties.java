package com.ticketflow.infrastructure.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SQS settings bound from {@code ticketflow.sqs.*} (env: {@code TICKETFLOW_SQS_*}).
 *
 * <p>The orders queue is identified either by {@code ordersQueueUrl} (used as is) or by
 * {@code ordersQueueName}, which is resolved to a URL lazily on first publish. When
 * {@code accessKeyId} and {@code secretAccessKey} are both set, static credentials are used (local
 * development only); otherwise the AWS default credentials provider chain applies. A null
 * {@code endpoint} means the real AWS endpoint of the region.
 */
@ConfigurationProperties(prefix = "ticketflow.sqs")
public record SqsProperties(
        URI endpoint,
        @DefaultValue("us-east-1") String region,
        String accessKeyId,
        String secretAccessKey,
        @DefaultValue("orders") String ordersQueueName,
        String ordersQueueUrl) {

    public boolean hasStaticCredentials() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && secretAccessKey != null && !secretAccessKey.isBlank();
    }

    public boolean hasQueueUrl() {
        return ordersQueueUrl != null && !ordersQueueUrl.isBlank();
    }
}
