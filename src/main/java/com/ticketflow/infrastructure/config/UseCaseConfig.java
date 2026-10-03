package com.ticketflow.infrastructure.config;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.IdGenerator;
import com.ticketflow.domain.port.InventoryRepository;
import com.ticketflow.domain.port.OrderFulfillmentRepository;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.domain.port.OrderRepository;
import com.ticketflow.usecase.BusinessMetrics;
import com.ticketflow.usecase.IssueComplimentaryUseCase;
import com.ticketflow.usecase.ReleaseExpiredReservationsUseCase;
import com.ticketflow.usecase.RequestPurchaseUseCase;
import com.ticketflow.usecase.CreateEventUseCase;
import com.ticketflow.usecase.GetAvailabilityUseCase;
import com.ticketflow.usecase.GetEventUseCase;
import com.ticketflow.usecase.GetOrderStatusUseCase;
import com.ticketflow.usecase.ListEventsUseCase;
import com.ticketflow.usecase.ProcessOrderUseCase;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

/** Wires the framework-free use cases and their infrastructure helpers. */
@Configuration
@EnableConfigurationProperties(ExpirationProperties.class)
public class UseCaseConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator() {
        return EventId::generate;
    }

    @Bean
    CreateEventUseCase createEventUseCase(EventRepository events, IdGenerator ids, Clock clock) {
        return new CreateEventUseCase(events, ids, clock);
    }

    @Bean
    GetEventUseCase getEventUseCase(EventRepository events, InventoryRepository inventories) {
        return new GetEventUseCase(events, inventories);
    }

    @Bean
    GetOrderStatusUseCase getOrderStatusUseCase(OrderRepository orders) {
        return new GetOrderStatusUseCase(orders);
    }

    @Bean
    ProcessOrderUseCase processOrderUseCase(
            OrderRepository orders, OrderFulfillmentRepository fulfillment, OrderPlacementRepository placement,
            Clock clock, BusinessMetrics metrics) {
        return new ProcessOrderUseCase(orders, fulfillment, placement, clock, metrics);
    }

    @Bean
    ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase(
            OrderRepository orders, OrderPlacementRepository placement, Clock clock,
            ExpirationProperties expiration, BusinessMetrics metrics) {
        return new ReleaseExpiredReservationsUseCase(
                orders, placement, clock, expiration.concurrency(), expiration.maxPerSweep(), metrics);
    }

    @Bean
    ListEventsUseCase listEventsUseCase(EventRepository events) {
        return new ListEventsUseCase(events);
    }

    @Bean
    GetAvailabilityUseCase getAvailabilityUseCase(
            InventoryRepository inventories,
            @Value("${ticketflow.availability.poll-interval:1s}") Duration pollInterval) {
        return new GetAvailabilityUseCase(inventories, pollInterval, Schedulers.parallel());
    }

    @Bean
    RequestPurchaseUseCase requestPurchaseUseCase(
            OrderPlacementRepository placement,
            OrderRepository orders,
            OrderQueuePublisher queue,
            Clock clock,
            @Value("${ticketflow.reservation.ttl:PT10M}") Duration reservationTtl,
            BusinessMetrics metrics) {
        return new RequestPurchaseUseCase(placement, orders, queue, clock, reservationTtl, metrics);
    }

    @Bean
    IssueComplimentaryUseCase issueComplimentaryUseCase(
            OrderPlacementRepository placement, OrderRepository orders, Clock clock, BusinessMetrics metrics) {
        return new IssueComplimentaryUseCase(placement, orders, clock, metrics);
    }
}
