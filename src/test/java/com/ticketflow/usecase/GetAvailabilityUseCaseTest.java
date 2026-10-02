package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Inventory;
import com.ticketflow.domain.port.InventoryRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

class GetAvailabilityUseCaseTest {

    private static final EventId ID = new EventId("evt-1");
    private static final Duration POLL = Duration.ofSeconds(1);

    private final InventoryRepository inventories = mock(InventoryRepository.class);

    private static Inventory inventory(int available, int reserved, int pending, int sold, long version) {
        return new Inventory(ID, available, reserved, pending, sold, 0, 10, version);
    }

    @Test
    void execute_reservedAndPending_areNotAvailable() {
        when(inventories.findByEventId(ID)).thenReturn(Mono.just(inventory(4, 3, 2, 1, 5)));

        StepVerifier.create(new GetAvailabilityUseCase(inventories, POLL, Schedulers.parallel()).execute(ID))
                .expectNext(new Availability(ID, 4, 3, 2, 1, 0, 10))
                .verifyComplete();
    }

    @Test
    void execute_unknownEvent_failsWithEventNotFound() {
        when(inventories.findByEventId(ID)).thenReturn(Mono.empty());

        StepVerifier.create(new GetAvailabilityUseCase(inventories, POLL, Schedulers.parallel()).execute(ID))
                .expectError(EventNotFoundException.class)
                .verify();
    }

    @Test
    void constructor_nonPositiveInterval_isRejected() {
        assertThatThrownBy(() -> new GetAvailabilityUseCase(inventories, Duration.ZERO, Schedulers.parallel()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GetAvailabilityUseCase(inventories, null, Schedulers.parallel()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stream_emitsFirstValueAndOnlyChanges() {
        Inventory first = inventory(10, 0, 0, 0, 0);
        Inventory second = inventory(8, 2, 0, 0, 1);
        when(inventories.findByEventId(ID)).thenReturn(
                Mono.just(first), Mono.just(first), Mono.just(second), Mono.just(second));

        StepVerifier.withVirtualTime(
                        () -> new GetAvailabilityUseCase(inventories, POLL, VirtualTimeScheduler.get()).stream(ID),
                        VirtualTimeScheduler::create, Long.MAX_VALUE)
                .expectNext(Availability.from(first))
                .expectNoEvent(POLL.multipliedBy(2))
                .expectNext(Availability.from(second))
                .expectNoEvent(POLL)
                .thenCancel()
                .verify();
    }

    @Test
    void stream_unknownEvent_failsWithEventNotFound() {
        when(inventories.findByEventId(ID)).thenReturn(Mono.empty());

        StepVerifier.withVirtualTime(
                        () -> new GetAvailabilityUseCase(inventories, POLL, VirtualTimeScheduler.get()).stream(ID),
                        VirtualTimeScheduler::create, Long.MAX_VALUE)
                .expectError(EventNotFoundException.class)
                .verify();
    }

    @Test
    void stream_cancel_stopsPolling() {
        when(inventories.findByEventId(ID)).thenReturn(Mono.just(inventory(10, 0, 0, 0, 0)));
        VirtualTimeScheduler vts = VirtualTimeScheduler.create();
        var useCase = new GetAvailabilityUseCase(inventories, POLL, vts);

        StepVerifier.create(useCase.stream(ID))
                .then(() -> vts.advanceTimeBy(Duration.ZERO))
                .expectNextCount(1)
                .thenCancel()
                .verify();
        vts.advanceTimeBy(Duration.ofSeconds(10));

        verify(inventories, times(1)).findByEventId(ID);
    }
}
