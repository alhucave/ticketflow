package com.ticketflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.InventoryRepository;
import com.ticketflow.domain.port.OrderFulfillmentRepository;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.domain.port.OrderRepository;
import com.ticketflow.infrastructure.scheduler.ReservationExpirationScheduler;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ExpirationConfigTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(UseCaseConfig.class, ExpirationSchedulerConfig.class)
                .withBean(OrderRepository.class, () -> mock(OrderRepository.class))
                .withBean(OrderPlacementRepository.class, () -> mock(OrderPlacementRepository.class))
                // ports needed by the other use cases declared in UseCaseConfig
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(EventRepository.class, () -> mock(EventRepository.class))
                .withBean(InventoryRepository.class, () -> mock(InventoryRepository.class))
                .withBean(OrderFulfillmentRepository.class, () -> mock(OrderFulfillmentRepository.class))
                .withBean(OrderQueuePublisher.class, () -> mock(OrderQueuePublisher.class))
                .withBean(com.ticketflow.usecase.BusinessMetrics.class, () -> com.ticketflow.usecase.BusinessMetrics.NOOP);
    }

    @Test
    void properties_defaults_schedulerEnabledAndOneMinuteInterval() {
        var defaults = new ExpirationProperties(true, Duration.parse("PT1M"), Duration.ofSeconds(10), 4, 500,
                Duration.ofSeconds(20));
        runner().run(context -> {
            assertThat(context).hasSingleBean(ExpirationProperties.class);
            assertThat(context.getBean(ExpirationProperties.class)).isEqualTo(defaults);
            // Property absent: the guard (matchIfMissing) and the record default agree, the scheduler exists.
            assertThat(context).hasSingleBean(ReservationExpirationScheduler.class);
            assertThat(context.getBean(ReservationExpirationScheduler.class).isRunning()).isTrue();
            assertThat(context).hasSingleBean(ReleaseExpiredReservationsUseCase.class);
        });
    }

    @Test
    void context_disabledExplicitly_noSchedulerButUseCaseRemains() {
        runner().withPropertyValues("ticketflow.expiration.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ReservationExpirationScheduler.class);
            assertThat(context).hasSingleBean(ReleaseExpiredReservationsUseCase.class);
            assertThat(context.getBean(ExpirationProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void context_enabled_createsSchedulerWithBoundSettings() {
        runner().withPropertyValues("ticketflow.expiration.enabled=true", "ticketflow.expiration.interval=PT30S",
                "ticketflow.expiration.initial-delay=PT0S", "ticketflow.expiration.concurrency=2",
                "ticketflow.expiration.max-per-sweep=7").run(context -> {
            assertThat(context).hasSingleBean(ReservationExpirationScheduler.class);
            var props = context.getBean(ExpirationProperties.class);
            assertThat(props.interval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.concurrency()).isEqualTo(2);
            assertThat(props.maxPerSweep()).isEqualTo(7);
            context.getBean(ReservationExpirationScheduler.class).stop();
        });
    }

    @Test
    void properties_invalidValues_rejected() {
        var ok = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new ExpirationProperties(true, Duration.ZERO, ok, 1, 1, ok))
                .hasMessageContaining("interval");
        assertThatThrownBy(() -> new ExpirationProperties(true, ok, ok.negated(), 1, 1, ok))
                .hasMessageContaining("initial-delay");
        assertThatThrownBy(() -> new ExpirationProperties(true, ok, ok, 0, 1, ok))
                .hasMessageContaining("concurrency");
        assertThatThrownBy(() -> new ExpirationProperties(true, ok, ok, 1, 0, ok))
                .hasMessageContaining("max-per-sweep");
        assertThatThrownBy(() -> new ExpirationProperties(true, ok, ok, 1, 1, ok.negated()))
                .hasMessageContaining("shutdown-timeout");
    }
}
