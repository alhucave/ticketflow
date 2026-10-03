package com.ticketflow.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DynamoDB settings bound from {@code ticketflow.dynamodb.*} (env: {@code TICKETFLOW_DYNAMODB_*}).
 *
 * <p>Static credentials are used ONLY when {@code accessKeyId} and {@code secretAccessKey} are both set
 * (local development with DynamoDB Local, dummy values); otherwise the AWS default credentials provider
 * chain applies (IAM role, environment, profile), which is what a real deployment must use. Never put
 * real keys in files or images. A null {@code endpoint} means the real AWS endpoint of the region.
 * {@link #toString()} masks both keys and strips the endpoint to scheme, host and port.
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

    @Override
    public String toString() {
        return "DynamoDbProperties[endpoint=" + SecretMasking.endpoint(endpoint) + ", region=" + region
                + ", accessKeyId=" + SecretMasking.secret(accessKeyId)
                + ", secretAccessKey=" + SecretMasking.secret(secretAccessKey)
                + ", provisioningEnabled=" + provisioningEnabled
                + ", provisioningMaxAttempts=" + provisioningMaxAttempts
                + ", provisioningPollInterval=" + provisioningPollInterval + "]";
    }

    public boolean hasStaticCredentials() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && secretAccessKey != null && !secretAccessKey.isBlank();
    }
}
