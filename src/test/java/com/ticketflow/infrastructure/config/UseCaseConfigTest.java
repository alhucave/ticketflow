package com.ticketflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.InventoryRepository;
import com.ticketflow.domain.port.OrderFulfillmentRepository;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.domain.port.OrderRepository;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.usecase.RequestPurchaseCommand;
import java.time.Duration;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import org.junit.jupiter.api.Test;

class UseCaseConfigTest {

    private final UseCaseConfig config = new UseCaseConfig();
    private final EventRepository events = mock(EventRepository.class);
    private final InventoryRepository inventories = mock(InventoryRepository.class);

    @Test
    void beans_areBuilt() {
        var ids = config.idGenerator();
        assertThat(ids.nextEventId()).isNotEqualTo(ids.nextEventId());
        assertThat(config.clock()).isNotNull();
        assertThat(config.createEventUseCase(events, ids, config.clock())).isNotNull();
        assertThat(config.getEventUseCase(events, inventories)).isNotNull();
        assertThat(config.listEventsUseCase(events)).isNotNull();
        assertThat(config.getOrderStatusUseCase(mock(OrderRepository.class))).isNotNull();
        assertThat(config.processOrderUseCase(mock(OrderRepository.class), mock(OrderFulfillmentRepository.class),
                mock(OrderPlacementRepository.class), config.clock())).isNotNull();
        assertThat(config.issueComplimentaryUseCase(mock(OrderPlacementRepository.class),
                mock(OrderRepository.class), config.clock())).isNotNull();
        assertThat(config.releaseExpiredReservationsUseCase(mock(OrderRepository.class),
                mock(OrderPlacementRepository.class), config.clock(),
                new ExpirationProperties(false, Duration.ofMinutes(1), Duration.ZERO, 2, 10, Duration.ZERO)))
                .isNotNull();
    }

    @Test
    void requestPurchaseUseCase_withPublisher_isBuiltAndUsesIt() {
        var placement = mock(OrderPlacementRepository.class);
        var publisher = mock(OrderQueuePublisher.class);
        org.mockito.Mockito.when(placement.placeReservation(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> Mono.just(i.getArgument(0)));
        org.mockito.Mockito.when(publisher.publish(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        var useCase = config.requestPurchaseUseCase(placement, mock(OrderRepository.class), publisher,
                config.clock(), Duration.ofMinutes(10));

        StepVerifier.create(useCase.execute(new RequestPurchaseCommand(
                        new EventId("e"), new Quantity(1), new IdempotencyKey("k"))))
                .expectNextCount(1).verifyComplete();
    }
}
