package com.ticketflow.infrastructure.web;

import com.ticketflow.infrastructure.messaging.SqsTestQueues;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Real DynamoDB Local + LocalStack SQS shared by the hardening end-to-end tests of one JVM (started once,
 * reaped by Testcontainers when the JVM ends). Each test class creates its own queue.
 */
public final class E2eContainers {

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0")).withServices("sqs");
    private static SqsAsyncClient sqs;

    private E2eContainers() {}

    public static synchronized SqsAsyncClient start() {
        if (sqs == null) {
            DYNAMO.start();
            LOCALSTACK.start();
            sqs = SqsAsyncClient.builder().endpointOverride(LOCALSTACK.getEndpoint()).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .build();
        }
        return sqs;
    }

    public static SqsTestQueues.Queues newQueue(String prefix) {
        return SqsTestQueues.create(start(), prefix + "-" + UUID.randomUUID());
    }

    /**
     * Budgets so large that no test is ever limited: the end-to-end tests of other features send bursts and many
     * wrong admin keys from one address. Only {@code RateLimitEndToEndIT} runs with small budgets.
     */
    public static void generousRateLimits(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.rate-limit.capacity", () -> "1000000");
        registry.add("ticketflow.rate-limit.refill-per-second", () -> "100000");
        registry.add("ticketflow.rate-limit.admin-failure-capacity", () -> "1000000");
    }

    /** Points the application at the containers and at {@code queueName}; consumer and scheduler are off. */
    public static void register(DynamicPropertyRegistry registry, String queueName) {
        registry.add("ticketflow.dynamodb.endpoint",
                () -> "http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000));
        registry.add("ticketflow.dynamodb.access-key-id", () -> "test");
        registry.add("ticketflow.dynamodb.secret-access-key", () -> "test");
        registry.add("ticketflow.dynamodb.provisioning-enabled", () -> "true");
        registry.add("ticketflow.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("ticketflow.sqs.access-key-id", () -> "test");
        registry.add("ticketflow.sqs.secret-access-key", () -> "test");
        registry.add("ticketflow.sqs.orders-queue-name", () -> queueName);
        registry.add("ticketflow.sqs.consumer.enabled", () -> "false");
        registry.add("ticketflow.expiration.enabled", () -> "false");
    }
}
