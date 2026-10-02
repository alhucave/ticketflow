package com.ticketflow.usecase;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.port.InventoryRepository;
import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Reports real-time availability of an event, as a one-shot snapshot or as a stream that polls the
 * inventory and emits only when it changes. The inventory always exists when the event exists
 * (they are created atomically), so a missing inventory means an unknown event.
 */
public class GetAvailabilityUseCase {

    private final InventoryRepository inventories;
    private final Duration pollInterval;
    private final Scheduler scheduler;

    public GetAvailabilityUseCase(InventoryRepository inventories, Duration pollInterval, Scheduler scheduler) {
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("Poll interval must be positive: " + pollInterval);
        }
        this.inventories = inventories;
        this.pollInterval = pollInterval;
        this.scheduler = scheduler;
    }

    /** Current availability, or {@link EventNotFoundException} for an unknown event. */
    public Mono<Availability> execute(EventId eventId) {
        return inventories.findByEventId(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)))
                .map(Availability::from);
    }

    /**
     * Emits the current availability immediately, then again whenever it changes (polled every
     * poll interval). Never completes on its own; cancel the subscription to stop polling. Fails
     * with {@link EventNotFoundException} for an unknown event.
     */
    public Flux<Availability> stream(EventId eventId) {
        return Flux.interval(Duration.ZERO, pollInterval, scheduler)
                .onBackpressureDrop()
                .concatMap(tick -> execute(eventId), 1)
                .distinctUntilChanged();
    }
}
