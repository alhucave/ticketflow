package com.ticketflow.infrastructure.config;

import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.infrastructure.scheduler.ReservationExpirationScheduler;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the expiration scheduler; only active with {@code ticketflow.expiration.enabled=true}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ticketflow.expiration", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ExpirationProperties.class)
public class ExpirationSchedulerConfig {

    @Bean
    ReservationExpirationScheduler reservationExpirationScheduler(
            ReleaseExpiredReservationsUseCase useCase, ExpirationProperties properties,
            ObjectProvider<OperationalMetrics> metrics) {
        return new ReservationExpirationScheduler(useCase, properties,
                metrics.getIfAvailable(() -> OperationalMetrics.NOOP));
    }
}
