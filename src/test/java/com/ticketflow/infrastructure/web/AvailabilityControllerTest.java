package com.ticketflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.infrastructure.web.error.ApiExceptionHandler;
import com.ticketflow.usecase.Availability;
import com.ticketflow.usecase.GetAvailabilityUseCase;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/** Contract tests of the availability endpoints with a mocked use case. */
class AvailabilityControllerTest {

    private static final EventId ID = new EventId("evt-1");
    private static final Availability INITIAL = new Availability(ID, 8, 2, 0, 0, 0, 10);
    private static final Availability CHANGED = new Availability(ID, 8, 0, 0, 2, 0, 10);
    private static final AvailabilityResponse INITIAL_DTO = new AvailabilityResponse(8, 2, 0, 0, 0, 10);
    private static final AvailabilityResponse CHANGED_DTO = new AvailabilityResponse(8, 0, 0, 2, 0, 10);

    private final GetAvailabilityUseCase useCase = mock(GetAvailabilityUseCase.class);
    private final VirtualTimeScheduler clock = VirtualTimeScheduler.create();
    private AvailabilityController controller;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        controller = new AvailabilityController(useCase, Duration.ofSeconds(1), Duration.ofSeconds(8), clock);
        client = WebTestClient.bindToController(controller).controllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void get_knownEvent_returnsSnapshot() {
        when(useCase.execute(ID)).thenReturn(Mono.just(INITIAL));

        client.get().uri("/events/evt-1/availability").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.available").isEqualTo(8)
                .jsonPath("$.reserved").isEqualTo(2)
                .jsonPath("$.pendingConfirmation").isEqualTo(0)
                .jsonPath("$.sold").isEqualTo(0)
                .jsonPath("$.complimentary").isEqualTo(0)
                .jsonPath("$.capacity").isEqualTo(10);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"..%2Fsecret", "a%20b", "%3Cb%3E"})
    void getAndStream_malformedPathId_are404WithFixedTextAndNeverEchoInput(String id) {
        for (String path : new String[] {"/events/" + id + "/availability", "/events/" + id + "/availability/stream"}) {
            client.get().uri(path).exchange().expectStatus().isNotFound()
                    .expectBody(String.class).value(body -> assertThat(body)
                            .contains("The requested resource was not found")
                            .doesNotContain("secret").doesNotContain("<b>").doesNotContain(id));
        }
        org.mockito.Mockito.verifyNoInteractions(useCase);
    }

    @Test
    void get_unknownEvent_returns404Problem() {
        when(useCase.execute(any())).thenReturn(Mono.error(new EventNotFoundException(ID)));

        client.get().uri("/events/evt-1/availability").exchange().expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:event-not-found");
    }

    @Test
    void stream_knownEvent_producesServerSentEventsAndStopsOnCancel() {
        Sinks.Many<Availability> source = Sinks.many().unicast().onBackpressureBuffer();
        when(useCase.execute(ID)).thenReturn(Mono.just(INITIAL));
        when(useCase.stream(ID)).thenReturn(source.asFlux().doOnCancel(() -> source.tryEmitComplete()));

        // The use case emits the current value immediately; it is what commits the response.
        source.tryEmitNext(INITIAL);
        Flux<AvailabilityResponse> body = client.get().uri("/events/evt-1/availability/stream")
                .accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(AvailabilityResponse.class).getResponseBody();

        StepVerifier.create(body)
                .expectNext(INITIAL_DTO)
                .then(() -> source.tryEmitNext(CHANGED))
                .expectNext(CHANGED_DTO)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void stream_unknownEvent_returns404JsonProblemBeforeStreaming() {
        when(useCase.execute(any())).thenReturn(Mono.error(new EventNotFoundException(ID)));

        client.get().uri("/events/evt-1/availability/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:event-not-found");
    }

    @Test
    void resilientStream_emitsCurrentValueThenChanges() {
        when(useCase.stream(ID)).thenReturn(Flux.just(INITIAL, CHANGED).concatWith(Flux.never()));

        StepVerifier.create(controller.resilientStream(ID))
                .expectNext(INITIAL_DTO, CHANGED_DTO)
                .thenCancel().verify(Duration.ofSeconds(5));
    }

    @Test
    void resilientStream_transientError_resubscribesWithBackoffAndKeepsEmitting() {
        AtomicInteger subscriptions = new AtomicInteger();
        when(useCase.stream(ID)).thenReturn(Flux.defer(() -> subscriptions.incrementAndGet() <= 2
                ? Flux.<Availability>just(INITIAL).concatWith(Flux.error(new IllegalStateException("dynamo-secret-host")))
                : Flux.just(CHANGED).concatWith(Flux.never())));

        StepVerifier.create(controller.resilientStream(ID))
                .expectNext(INITIAL_DTO)
                .then(() -> assertThat(subscriptions).hasValue(1))
                .then(() -> clock.advanceTimeBy(Duration.ofMillis(400)))   // below the min backoff: still waiting
                .then(() -> assertThat(subscriptions).hasValue(1))
                // Jittered backoff (0.5s..1.5s, then the same after the success): 20s covers both retries.
                .then(() -> clock.advanceTimeBy(Duration.ofSeconds(20)))
                .expectNext(INITIAL_DTO, CHANGED_DTO)
                .thenCancel().verify(Duration.ofSeconds(5));
        assertThat(subscriptions).hasValue(3);
    }

    @Test
    void resilientStream_eventNotFound_endsWithErrorInsteadOfRetrying() {
        AtomicInteger subscriptions = new AtomicInteger();
        when(useCase.stream(ID)).thenReturn(Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.error(new EventNotFoundException(ID));
        }));

        StepVerifier.create(controller.resilientStream(ID))
                .expectError(EventNotFoundException.class)
                .verify(Duration.ofSeconds(5));
        assertThat(subscriptions).hasValue(1);
    }

    @Test
    void stream_transientErrorDoesNotLeakDetailsToClient() {
        AtomicInteger subscriptions = new AtomicInteger();
        when(useCase.execute(ID)).thenReturn(Mono.just(INITIAL));
        when(useCase.stream(ID)).thenReturn(Flux.defer(() -> subscriptions.getAndIncrement() == 0
                ? Flux.<Availability>just(INITIAL).concatWith(Flux.error(new IllegalStateException("dynamo-secret-host")))
                : Flux.just(CHANGED).concatWith(Flux.never())));
        AvailabilityController fast = new AvailabilityController(useCase, Duration.ofMillis(10),
                Duration.ofMillis(20), reactor.core.scheduler.Schedulers.parallel());
        WebTestClient fastClient = WebTestClient.bindToController(fast).controllerAdvice(new ApiExceptionHandler())
                .build();

        Flux<String> raw = fastClient.get().uri("/events/evt-1/availability/stream")
                .accept(MediaType.TEXT_EVENT_STREAM).exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody();

        StepVerifier.create(raw.take(2).collectList())
                .assertNext(events -> assertThat(String.join("", events))
                        .contains("\"sold\":0").contains("\"sold\":2").doesNotContain("dynamo-secret-host"))
                .verifyComplete();
    }
}
