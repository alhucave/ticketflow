package com.ticketflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.infrastructure.config.DynamoDbConfig;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.KeyType;

/** Runs against DynamoDB Local (same image as docker-compose). Enabled with INCLUDE_INTEGRATION=true. */
@Tag("integration")
class DynamoDbProvisioningIT {

    private static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoDbConfig.class);

    @BeforeAll
    static void start() {
        DYNAMO.start();
    }

    @AfterAll
    static void stop() {
        DYNAMO.stop();
    }

    private static String endpoint() {
        return "http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000);
    }

    private ApplicationContextRunner withProps(String... extra) {
        var base = new String[] {
            "ticketflow.dynamodb.endpoint=" + endpoint(),
            "ticketflow.dynamodb.region=us-east-1",
            "ticketflow.dynamodb.access-key-id=test",
            "ticketflow.dynamodb.secret-access-key=test",
            "ticketflow.dynamodb.provisioning-poll-interval=100ms"};
        return runner.withPropertyValues(base).withPropertyValues(extra);
    }

    private void dropAll(DynamoDbAsyncClient client) {
        client.listTables().join().tableNames().forEach(
                t -> client.deleteTable(DeleteTableRequest.builder().tableName(t).build()).join());
    }

    @Test
    void provision_runTwice_isIdempotentAndCreatesTablesAndGsis() {
        withProps("ticketflow.dynamodb.provisioning-enabled=true").run(context -> {
            var client = context.getBean(DynamoDbAsyncClient.class);
            dropAll(client);
            var provisioner = context.getBean(DynamoDbTableProvisioner.class);

            StepVerifier.create(provisioner.provision()).verifyComplete();
            StepVerifier.create(provisioner.provision()).verifyComplete();

            assertThat(client.listTables().join().tableNames())
                    .containsExactlyInAnyOrder("events", "inventory", "orders", "order_audit");
            var orders = client.describeTable(b -> b.tableName("orders")).join().table();
            assertThat(orders.globalSecondaryIndexes()).extracting(GlobalSecondaryIndexDescription::indexName)
                    .containsExactlyInAnyOrder(DynamoDbTables.ORDERS_BY_IDEMPOTENCY_KEY_INDEX,
                            DynamoDbTables.ORDERS_BY_STATUS_EXPIRY_INDEX);
            var audit = client.describeTable(b -> b.tableName("order_audit")).join().table();
            assertThat(audit.keySchema()).extracting(k -> k.attributeName() + ":" + k.keyType())
                    .containsExactly("orderId:" + KeyType.HASH, "timestamp:" + KeyType.RANGE);
        });
    }

    @Test
    void provision_ordersTableWithoutGsis_addsMissingIndexes() {
        withProps("ticketflow.dynamodb.provisioning-enabled=true").run(context -> {
            var client = context.getBean(DynamoDbAsyncClient.class);
            dropAll(client);
            var ordersDefinition = DynamoDbTables.definitions().stream()
                    .filter(d -> d.tableName().equals("orders")).findFirst().orElseThrow();
            var plain = software.amazon.awssdk.services.dynamodb.model.CreateTableRequest.builder()
                    .tableName("orders")
                    .billingMode(ordersDefinition.billingMode())
                    .keySchema(ordersDefinition.keySchema())
                    .attributeDefinitions(ordersDefinition.attributeDefinitions().stream()
                            .filter(a -> a.attributeName().equals("orderId")).toList())
                    .build();
            client.createTable(plain).join();

            StepVerifier.create(context.getBean(DynamoDbTableProvisioner.class).provision()).verifyComplete();

            var orders = client.describeTable(b -> b.tableName("orders")).join().table();
            assertThat(orders.globalSecondaryIndexes()).extracting(GlobalSecondaryIndexDescription::indexName)
                    .containsExactlyInAnyOrder(DynamoDbTables.ORDERS_BY_IDEMPOTENCY_KEY_INDEX,
                            DynamoDbTables.ORDERS_BY_STATUS_EXPIRY_INDEX);
        });
    }

    @Test
    void context_provisioningDisabled_createsNoTables() {
        withProps("ticketflow.dynamodb.provisioning-enabled=false").run(context -> {
            var client = context.getBean(DynamoDbAsyncClient.class);
            dropAll(client);
            assertThat(context).doesNotHaveBean(DynamoDbTableProvisioner.class);
            assertThat(client.listTables().join().tableNames()).isEmpty();
        });
    }

    @Test
    void context_provisioningEnabledAtStartup_tablesAppearAfterReadyEvent() {
        withProps("ticketflow.dynamodb.provisioning-enabled=true").run(context -> {
            var client = context.getBean(DynamoDbAsyncClient.class);
            dropAll(client);
            context.publishEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(
                    new org.springframework.boot.SpringApplication(), new String[0], context, Duration.ZERO));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(client.listTables().join().tableNames()).hasSize(4));
        });
    }
}
