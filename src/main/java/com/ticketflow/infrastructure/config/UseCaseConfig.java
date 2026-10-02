package com.ticketflow.infrastructure.config;

import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.port.EventRepository;
import com.ticketflow.domain.port.IdGenerator;
import com.ticketflow.domain.port.InventoryRepository;
import com.ticketflow.domain.port.OrderFulfillmentRepository;
import com.ticketflow.domain.port.OrderPlacementRepository;
import com.ticketflow.domain.port.OrderQueuePublisher;
import com.ticketflow.domain.port.OrderRepository;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

/** Wires the framework-free use cases and their infrastructure helpers. */
@Configuration
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
            Clock clock) {
        return new ProcessOrderUseCase(orders, fulfillment, placement, clock);
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
            @Value("${ticketflow.reservation.ttl:PT10M}") Duration reservationTtl) {
        return new RequestPurchaseUseCase(placement, orders, queue, clock, reservationTtl);
    }
}
