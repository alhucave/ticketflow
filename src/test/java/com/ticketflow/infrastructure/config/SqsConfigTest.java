package com.ticketflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.port.OrderQueuePublisher;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

class SqsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SqsConfig.class);

    private static SqsProperties props(String key, String secret) {
        return new SqsProperties(null, "eu-west-1", key, secret, "orders", null);
    }

    @Test
    void credentialsProvider_staticKeys_usesStaticProvider() {
        var provider = SqsConfig.credentialsProvider(props("k", "s"));
        assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);
        assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("k");
    }

    @Test
    void credentialsProvider_noKeys_usesDefaultChain() {
        assertThat(SqsConfig.credentialsProvider(props(null, null))).isInstanceOf(DefaultCredentialsProvider.class);
        assertThat(SqsConfig.credentialsProvider(props("k", " "))).isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void context_withEndpointAndRegion_buildsClientAndPublisherFromProperties() {
        runner.withPropertyValues(
                        "ticketflow.sqs.endpoint=http://localhost:4577",
                        "ticketflow.sqs.region=eu-west-1",
                        "ticketflow.sqs.access-key-id=test",
                        "ticketflow.sqs.secret-access-key=test",
                        "ticketflow.sqs.orders-queue-name=my-orders")
                .run(context -> {
                    assertThat(context).hasSingleBean(OrderQueuePublisher.class);
                    var cfg = context.getBean(SqsAsyncClient.class).serviceClientConfiguration();
                    assertThat(cfg.region().id()).isEqualTo("eu-west-1");
                    assertThat(cfg.endpointOverride()).contains(URI.create("http://localhost:4577"));
                });
    }

    @Test
    void context_withQueueUrl_buildsPublisher() {
        runner.withPropertyValues("ticketflow.sqs.access-key-id=a", "ticketflow.sqs.secret-access-key=b",
                        "ticketflow.sqs.orders-queue-url=http://localhost:4577/000000000000/orders")
                .run(context -> assertThat(context).hasSingleBean(OrderQueuePublisher.class));
    }

    @Test
    void context_withoutEndpoint_usesDefaultRegionNameAndNoOverride() {
        runner.withPropertyValues("ticketflow.sqs.access-key-id=a", "ticketflow.sqs.secret-access-key=b")
                .run(context -> {
                    var cfg = context.getBean(SqsAsyncClient.class).serviceClientConfiguration();
                    assertThat(cfg.region().id()).isEqualTo("us-east-1");
                    assertThat(cfg.endpointOverride()).isEmpty();
                    assertThat(context.getBean(SqsProperties.class).ordersQueueName()).isEqualTo("orders");
                });
    }
}
