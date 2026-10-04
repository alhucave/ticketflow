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
import com.ticketflow.usecase.BusinessMetrics;
import com.ticketflow.usecase.RequestPurchaseCommand;
import java.time.Duration;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import com.ticketflow.usecase.RequestPurchaseUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
                mock(OrderPlacementRepository.class), config.clock(), BusinessMetrics.NOOP)).isNotNull();
        assertThat(config.issueComplimentaryUseCase(mock(OrderPlacementRepository.class),
                mock(OrderRepository.class), config.clock(), BusinessMetrics.NOOP)).isNotNull();
        assertThat(config.releaseExpiredReservationsUseCase(mock(OrderRepository.class),
                mock(OrderPlacementRepository.class), config.clock(),
                new ExpirationProperties(false, Duration.ofMinutes(1), Duration.ZERO, 2, 10, Duration.ZERO),
                BusinessMetrics.NOOP))
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
                config.clock(), Duration.ofMinutes(10), BusinessMetrics.NOOP);

        StepVerifier.create(useCase.execute(new RequestPurchaseCommand(
                        new EventId("e"), new Quantity(1), new IdempotencyKey("k"))))
                .expectNextCount(1).verifyComplete();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(UseCaseConfig.class)
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(EventRepository.class, () -> mock(EventRepository.class))
                .withBean(InventoryRepository.class, () -> mock(InventoryRepository.class))
                .withBean(OrderRepository.class, () -> mock(OrderRepository.class))
                .withBean(OrderPlacementRepository.class, () -> mock(OrderPlacementRepository.class))
                .withBean(OrderFulfillmentRepository.class, () -> mock(OrderFulfillmentRepository.class))
                .withBean(OrderQueuePublisher.class, () -> mock(OrderQueuePublisher.class))
                .withBean(BusinessMetrics.class, () -> BusinessMetrics.NOOP);
    }

    @Test
    void context_defaultAndMaximumTtl_start() {
        runner().run(context -> assertThat(context).hasNotFailed().hasSingleBean(RequestPurchaseUseCase.class));
        runner().withPropertyValues("ticketflow.reservation.ttl=PT10M")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RequestPurchaseUseCase.class));
        runner().withPropertyValues("ticketflow.reservation.ttl=PT1S")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RequestPurchaseUseCase.class));
    }

    @Test
    void context_ttlAboveMaximum_refusesToStartWithClearMessage() {
        for (String tooLong : new String[] {"PT10M1S", "PT2H"}) {
            runner().withPropertyValues("ticketflow.reservation.ttl=" + tooLong).run(context -> {
                assertThat(context).hasFailed();
                assertThat(rootMessages(context.getStartupFailure()))
                        .contains("ticketflow.reservation.ttl")
                        .contains(tooLong)
                        .contains("PT10M");
            });
        }
    }

    @Test
    void context_nonPositiveTtl_refusesToStart() {
        for (String bad : new String[] {"PT0S", "-PT1S"}) {
            runner().withPropertyValues("ticketflow.reservation.ttl=" + bad).run(context -> {
                assertThat(context).hasFailed();
                assertThat(rootMessages(context.getStartupFailure())).contains("ticketflow.reservation.ttl");
            });
        }
    }

    private static String rootMessages(Throwable failure) {
        var all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        return all.toString();
    }
}
