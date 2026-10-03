package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketflow.infrastructure.persistence.DynamoDbTables;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * C12: boots the whole application context (use cases, repositories, adapters, controllers) against a
 * real DynamoDB Local and exercises the events API over HTTP. SQS consumer and scheduler are off and
 * the SQS publisher only resolves its queue lazily, so no queue is needed. Enabled with
 * INCLUDE_INTEGRATION=true.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class EventsApiEndToEndIT {

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.dynamodb.endpoint",
                () -> "http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000));
        registry.add("ticketflow.dynamodb.access-key-id", () -> "test");
        registry.add("ticketflow.dynamodb.secret-access-key", () -> "test");
        registry.add("ticketflow.dynamodb.provisioning-enabled", () -> "true");
        registry.add("ticketflow.sqs.consumer.enabled", () -> "false");
        registry.add("ticketflow.expiration.enabled", () -> "false");
        // The SQS client is built eagerly but never called by the events API; point it nowhere real.
        registry.add("ticketflow.sqs.endpoint", () -> "http://localhost:1");
        registry.add("ticketflow.sqs.access-key-id", () -> "test");
        registry.add("ticketflow.sqs.secret-access-key", () -> "test");
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private DynamoDbAsyncClient dynamo;

    @BeforeEach
    void awaitTableProvisioning() {
        // Provisioning runs asynchronously on ApplicationReadyEvent: wait until the API can read.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(dynamo.listTables().join().tableNames())
                        .contains(DynamoDbTables.EVENTS, DynamoDbTables.INVENTORY));
    }

    private String createBody(String name) {
        return """
                {"name":"%s","startsAt":"%s","venue":"Arena","capacity":120}"""
                .formatted(name, Instant.now().plus(Duration.ofDays(30)));
    }

    private String createEvent(String name) {
        String location = client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBody(name)).exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseHeaders().getFirst("Location");
        assertThat(location).startsWith("/events/");
        return location;
    }

    @Test
    void createGetList_realAdapters_inventoryMatchesCapacity() {
        String location = createEvent("Rock");

        client.get().uri(location).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(location.substring("/events/".length()))
                .jsonPath("$.name").isEqualTo("Rock")
                .jsonPath("$.venue").isEqualTo("Arena")
                .jsonPath("$.capacity").isEqualTo(120)
                .jsonPath("$.inventory.available").isEqualTo(120)
                .jsonPath("$.inventory.reserved").isEqualTo(0)
                .jsonPath("$.inventory.pendingConfirmation").isEqualTo(0)
                .jsonPath("$.inventory.sold").isEqualTo(0)
                .jsonPath("$.inventory.complimentary").isEqualTo(0);

        List<String> ids = client.get().uri("/events").exchange().expectStatus().isOk()
                .expectBodyList(EventResponse.class).returnResult().getResponseBody()
                .stream().map(EventResponse::id).toList();
        assertThat(ids).contains(location.substring("/events/".length()));
    }

    @Test
    void create_sameBodyTwice_createsTwoDistinctEvents() {
        // Ids are generated server-side, so identical payloads are not duplicates: each gets its own id.
        String first = createEvent("Twin");
        String second = createEvent("Twin");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void get_unknownId_returns404Problem() {
        client.get().uri("/events/does-not-exist").exchange().expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:event-not-found");
    }

    @Test
    void create_invalidBody_returns400WithViolations() {
        client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\",\"startsAt\":null,\"venue\":\"Arena\",\"capacity\":0}")
                .exchange().expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.violations.length()").isEqualTo(3)
                .jsonPath("$.violations[?(@.field=='name')]").exists();
    }

    @Test
    void create_pastDate_returns400FromUseCaseRule() {
        client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Old\",\"startsAt\":\"2001-01-01T00:00:00Z\",\"venue\":\"Arena\",\"capacity\":5}")
                .exchange().expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:invalid-event")
                .jsonPath("$.detail").isEqualTo("Event date must be in the future");
    }

    @Test
    void create_malformedJson_returns400() {
        client.post().uri("/events").contentType(MediaType.APPLICATION_JSON).bodyValue("{oops")
                .exchange().expectStatus().isBadRequest();
    }
}
