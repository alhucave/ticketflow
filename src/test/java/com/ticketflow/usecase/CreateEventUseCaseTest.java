package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventAlreadyExistsException;
import com.ticketflow.domain.exception.InvalidEventException;
import com.ticketflow.domain.model.Event;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CreateEventUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FUTURE = NOW.plusSeconds(3600);
    private static final EventId ID = new EventId("evt-1");

    private final EventRepository events = mock(EventRepository.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private CreateEventUseCase useCase;

    @BeforeEach
    void setUp() {
        when(ids.nextEventId()).thenReturn(ID);
        useCase = new CreateEventUseCase(events, ids, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void execute_validCommand_savesEventOnly() {
        when(events.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(useCase.execute(new CreateEventCommand("Rock", FUTURE, "Arena", 100)))
                .assertNext(e -> assertThat(e).isEqualTo(new Event(ID, "Rock", FUTURE, "Arena", 100)))
                .verifyComplete();

        verify(events).save(new Event(ID, "Rock", FUTURE, "Arena", 100));
        verifyNoMoreInteractions(events);
    }

    @Test
    void execute_blankName_failsWithInvalidEvent() {
        assertInvalid(new CreateEventCommand(" ", FUTURE, "Arena", 10), "name");
        assertInvalid(new CreateEventCommand(null, FUTURE, "Arena", 10), "name");
    }

    @Test
    void execute_blankVenue_failsWithInvalidEvent() {
        assertInvalid(new CreateEventCommand("Rock", FUTURE, "", 10), "venue");
        assertInvalid(new CreateEventCommand("Rock", FUTURE, null, 10), "venue");
    }

    @Test
    void execute_dateNotInFuture_failsWithInvalidEvent() {
        assertInvalid(new CreateEventCommand("Rock", NOW, "Arena", 10), "future");
        assertInvalid(new CreateEventCommand("Rock", NOW.minusSeconds(1), "Arena", 10), "future");
        assertInvalid(new CreateEventCommand("Rock", null, "Arena", 10), "future");
    }

    @Test
    void execute_nonPositiveCapacity_failsWithInvalidEvent() {
        assertInvalid(new CreateEventCommand("Rock", FUTURE, "Arena", 0), "capacity");
        assertInvalid(new CreateEventCommand("Rock", FUTURE, "Arena", -5), "capacity");
    }

    @Test
    void execute_nullCommand_failsWithInvalidEvent() {
        assertInvalid(null, "null");
    }

    @Test
    void execute_saveFails_propagatesError() {
        when(events.save(any())).thenReturn(Mono.error(new EventAlreadyExistsException(ID)));

        StepVerifier.create(useCase.execute(new CreateEventCommand("Rock", FUTURE, "Arena", 10)))
                .expectError(EventAlreadyExistsException.class)
                .verify();
    }

    private void assertInvalid(CreateEventCommand command, String messagePart) {
        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(InvalidEventException.class)
                        .hasMessageContaining(messagePart))
                .verify();
        verifyNoInteractions(events);
    }
}
