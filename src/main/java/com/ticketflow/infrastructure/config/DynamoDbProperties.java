package com.ticketflow.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DynamoDB settings bound from {@code ticketflow.dynamodb.*} (env: {@code TICKETFLOW_DYNAMODB_*}).
 *
 * <p>When {@code accessKeyId} and {@code secretAccessKey} are both set, static credentials are used
 * (local development only); otherwise the AWS default credentials provider chain applies.
 * A null {@code endpoint} means the real AWS endpoint of the region.
 */
@ConfigurationProperties(prefix = "ticketflow.dynamodb")
public record DynamoDbProperties(
        URI endpoint,
        @DefaultValue("us-east-1") String region,
        String accessKeyId,
        String secretAccessKey,
        @DefaultValue("false") boolean provisioningEnabled,
        @DefaultValue("30") int provisioningMaxAttempts,
        @DefaultValue("500ms") Duration provisioningPollInterval) {

    public boolean hasStaticCredentials() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && secretAccessKey != null && !secretAccessKey.isBlank();
    }
}
