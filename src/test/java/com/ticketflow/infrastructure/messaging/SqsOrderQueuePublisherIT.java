package com.ticketflow.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.infrastructure.config.SqsConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.json.JsonMapper;

/** Publisher against a real SQS (LocalStack), configured purely through properties. */
@Tag("integration")
class SqsOrderQueuePublisherIT {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    // 2026.x images require LOCALSTACK_AUTH_TOKEN; 4.14.0 is the last that runs without one.
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0")).withServices("sqs");

    private static SqsAsyncClient sqs;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SqsConfig.class)
            .withPropertyValues(
                    "ticketflow.sqs.endpoint=" + endpoint(),
                    "ticketflow.sqs.region=" + LOCALSTACK.getRegion(),
                    "ticketflow.sqs.access-key-id=" + LOCALSTACK.getAccessKey(),
                    "ticketflow.sqs.secret-access-key=" + LOCALSTACK.getSecretKey());

    private static String endpoint() {
        return LOCALSTACK.getEndpoint().toString();
    }

    @BeforeAll
    static void start() {
        LOCALSTACK.start();
        sqs = SqsAsyncClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(software.amazon.awssdk.regions.Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    @AfterAll
    static void stop() {
        sqs.close();
        LOCALSTACK.stop();
    }

    private static Order order() {
        return new Order(OrderId.generate(), new EventId("event-" + UUID.randomUUID()), new Quantity(3),
                TicketStatus.RESERVED, new IdempotencyKey(UUID.randomUUID().toString()),
                NOW.plusSeconds(600), NOW);
    }

    private static String createQueue(String name) {
        return sqs.createQueue(CreateQueueRequest.builder().queueName(name).build()).join().queueUrl();
    }

    static Message receiveOne(SqsAsyncClient client, String queueUrl) {
        var response = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                .waitTimeSeconds(10).maxNumberOfMessages(1).messageAttributeNames("All").build()).join();
        assertThat(response.messages()).hasSize(1);
        return response.messages().get(0);
    }

    @Test
    void publish_toExistingQueue_messageArrivesWithBodyAndAttributes() {
        var queueName = "orders-" + UUID.randomUUID();
        var queueUrl = createQueue(queueName);
        var order = order();

        runner.withPropertyValues("ticketflow.sqs.orders-queue-name=" + queueName).run(context -> {
            var publisher = context.getBean(OrderQueuePublisher.class);
            StepVerifier.create(publisher.publish(order)
                            .contextWrite(ctx -> ctx.put(SqsOrderQueuePublisher.CORRELATION_ID_CONTEXT_KEY, "corr-1")))
                    .verifyComplete();
        });

        var message = receiveOne(sqs, queueUrl);
        var body = JsonMapper.builder().build().readTree(message.body());
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("orderId").asString()).isEqualTo(order.id().value());
        assertThat(body.size()).isEqualTo(2);
        var attrs = message.messageAttributes();
        assertThat(attrs.get("eventId").stringValue()).isEqualTo(order.eventId().value());
        assertThat(attrs.get("orderId").stringValue()).isEqualTo(order.id().value());
        assertThat(attrs.get("messageVersion").stringValue()).isEqualTo("1");
        assertThat(attrs.get("correlationId").stringValue()).isEqualTo("corr-1");
    }

    @Test
    void publish_withQueueUrlFromConfiguration_messageArrives() {
        var queueUrl = createQueue("orders-url-" + UUID.randomUUID());
        var order = order();

        runner.withPropertyValues("ticketflow.sqs.orders-queue-url=" + queueUrl).run(context ->
                StepVerifier.create(context.getBean(OrderQueuePublisher.class).publish(order)).verifyComplete());

        assertThat(receiveOne(sqs, queueUrl).body()).contains(order.id().value());
    }

    @Test
    void publish_toMissingQueue_failsClearlyAndNothingIsCreated() {
        var missing = "missing-" + UUID.randomUUID();

        runner.withPropertyValues("ticketflow.sqs.orders-queue-name=" + missing).run(context ->
                StepVerifier.create(context.getBean(OrderQueuePublisher.class).publish(order()))
                        .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(OrderQueueNotFoundException.class)
                                .hasMessageContaining(missing))
                        .verify(Duration.ofSeconds(30)));

        assertThat(sqs.listQueues().join().queueUrls()).noneMatch(url -> url.endsWith(missing));
    }

    @Test
    void publish_queueCreatedAfterFirstFailure_isPickedUpWithoutRestart() {
        var name = "late-" + UUID.randomUUID();

        runner.withPropertyValues("ticketflow.sqs.orders-queue-name=" + name).run(context -> {
            var publisher = context.getBean(OrderQueuePublisher.class);
            StepVerifier.create(publisher.publish(order())).expectError(OrderQueueNotFoundException.class)
                    .verify(Duration.ofSeconds(30));
            createQueue(name);
            StepVerifier.create(publisher.publish(order())).verifyComplete();
        });
    }
}
