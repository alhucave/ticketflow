package com.ticketflow.infrastructure.web;

import com.ticketflow.testsupport.TestWebClients;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.InvalidEventException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.infrastructure.web.error.ApiExceptionHandler;
import com.ticketflow.usecase.CreateEventCommand;
import com.ticketflow.usecase.CreateEventUseCase;
import com.ticketflow.usecase.EventDetails;
import com.ticketflow.usecase.GetEventUseCase;
import com.ticketflow.usecase.ListEventsUseCase;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Contract tests of the events API with mocked use cases (real controller, advice and validation). */
class EventControllerTest {

    private static final Instant STARTS_AT = Instant.parse("2030-01-01T20:00:00Z");
    private static final EventId ID = new EventId("evt-1");
    private static final Event EVENT = new Event(ID, "Rock", STARTS_AT, "Arena", 100);

    private final CreateEventUseCase create = mock(CreateEventUseCase.class);
    private final GetEventUseCase get = mock(GetEventUseCase.class);
    private final ListEventsUseCase list = mock(ListEventsUseCase.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = TestWebClients.build(WebTestClient.bindToController(new EventController(create, get, list))
                .controllerAdvice(new ApiExceptionHandler())
                .validator(validator()));
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    private WebTestClient.ResponseSpec post(String body) {
        return client.post().uri("/events").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static String body(String name, String startsAt, String venue, String capacity) {
        return """
                {"name":%s,"startsAt":%s,"venue":%s,"capacity":%s}"""
                .formatted(name, startsAt, venue, capacity);
    }

    private static String validBody() {
        return body("\"Rock\"", "\"2030-01-01T20:00:00Z\"", "\"Arena\"", "100");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"..%2F..%2Fetc%2Fpasswd", "a%20b", "%3Cb%3E",
            "caf%C3%A9", "a%00b"})
    void get_malformedPathId_is404WithFixedTextAndNeverEchoesInput(String id) {
        client.get().uri("/events/" + id).exchange().expectStatus().isNotFound()
                .expectBody(String.class).value(body -> {
                    org.assertj.core.api.Assertions.assertThat(body).contains("not-found")
                            .contains("The requested resource was not found")
                            .doesNotContain("passwd").doesNotContain("<b>").doesNotContain(id);
                });
        Mockito.verifyNoInteractions(get);
    }

    @Test
    void create_validBody_returns201WithLocationAndBody() {
        when(create.execute(any())).thenReturn(Mono.just(EVENT));

        post(validBody()).expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/events/evt-1")
                .expectBody()
                .jsonPath("$.id").isEqualTo("evt-1")
                .jsonPath("$.name").isEqualTo("Rock")
                .jsonPath("$.startsAt").isEqualTo("2030-01-01T20:00:00Z")
                .jsonPath("$.venue").isEqualTo("Arena")
                .jsonPath("$.capacity").isEqualTo(100);

        ArgumentCaptor<CreateEventCommand> captor = ArgumentCaptor.forClass(CreateEventCommand.class);
        Mockito.verify(create).execute(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new CreateEventCommand("Rock", STARTS_AT, "Arena", 100));
    }

    @Test
    void create_invalidFields_returns400ProblemWithViolations() {
        post(body("\" \"", "null", "\"" + "v".repeat(201) + "\"", "0")).expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:validation-error")
                .jsonPath("$.title").isEqualTo("Validation failed")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.violations.length()").isEqualTo(4)
                .jsonPath("$.violations[?(@.field=='name')]").exists()
                .jsonPath("$.violations[?(@.field=='startsAt')]").exists()
                .jsonPath("$.violations[?(@.field=='venue')]").exists()
                .jsonPath("$.violations[?(@.field=='capacity')]").exists();
        verifyNoInteractions(create);
    }

    @Test
    void create_capacityAboveMax_returns400() {
        post(body("\"Rock\"", "\"2030-01-01T20:00:00Z\"", "\"Arena\"", "1000001")).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.violations[0].field").isEqualTo("capacity");
    }

    @Test
    void create_missingCapacityAndNameTooLong_returnsViolations() {
        post("{\"name\":\"" + "n".repeat(201) + "\",\"startsAt\":\"2030-01-01T20:00:00Z\",\"venue\":\"Arena\"}")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.violations[?(@.field=='capacity')]").exists()
                .jsonPath("$.violations[?(@.field=='name')]").exists();
    }

    @Test
    void create_malformedJson_returns400Problem() {
        post("{not json").expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:malformed-request")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.violations").doesNotExist();
        verifyNoInteractions(create);
    }

    @Test
    void create_badInstantFormat_returns400Problem() {
        post(body("\"Rock\"", "\"tomorrow\"", "\"Arena\"", "10")).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.type").isEqualTo("urn:ticketflow:problem:malformed-request");
    }

    @Test
    void create_invalidEventFromUseCase_returns400WithDomainMessage() {
        when(create.execute(any())).thenReturn(Mono.error(new InvalidEventException("Event date must be in the future")));

        post(validBody()).expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:invalid-event")
                .jsonPath("$.detail").isEqualTo("Event date must be in the future");
    }

    @Test
    void create_eventAlreadyExists_returns409() {
        when(create.execute(any())).thenReturn(Mono.error(new EventAlreadyExistsException(ID)));

        post(validBody()).expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:event-already-exists")
                .jsonPath("$.status").isEqualTo(409);
    }

    @Test
    void get_existingEvent_returns200WithInventory() {
        when(get.execute(ID)).thenReturn(Mono.just(
                new EventDetails(EVENT, new Inventory(ID, 90, 2, 3, 4, 1, 100, 7))));

        client.get().uri("/events/evt-1").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("evt-1")
                .jsonPath("$.capacity").isEqualTo(100)
                .jsonPath("$.inventory.available").isEqualTo(90)
                .jsonPath("$.inventory.reserved").isEqualTo(2)
                .jsonPath("$.inventory.pendingConfirmation").isEqualTo(3)
                .jsonPath("$.inventory.sold").isEqualTo(4)
                .jsonPath("$.inventory.complimentary").isEqualTo(1)
                .jsonPath("$.inventory.version").doesNotExist();
    }

    @Test
    void get_unknownId_returns404Problem() {
        when(get.execute(new EventId("nope"))).thenReturn(Mono.error(new EventNotFoundException(new EventId("nope"))));

        client.get().uri("/events/nope").exchange().expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:ticketflow:problem:event-not-found")
                .jsonPath("$.title").isEqualTo("Event not found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Event not found: nope");
    }

    @Test
    void list_events_returnsArrayWithoutInventory() {
        Event other = new Event(new EventId("evt-2"), "Jazz", STARTS_AT, "Hall", 50);
        when(list.execute()).thenReturn(Flux.just(EVENT, other));

        client.get().uri("/events").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo("evt-1")
                .jsonPath("$[1].name").isEqualTo("Jazz")
                .jsonPath("$[0].inventory").doesNotExist();
    }

    @Test
    void list_noEvents_returnsEmptyArray() {
        when(list.execute()).thenReturn(Flux.empty());

        client.get().uri("/events").exchange().expectStatus().isOk()
                .expectBody().json("[]");
    }
}
