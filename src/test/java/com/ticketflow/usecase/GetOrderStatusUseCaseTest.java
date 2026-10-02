package com.ticketflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketflow.domain.exception.OrderNotFoundException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.IdempotencyKey;
import com.ticketflow.domain.model.Order;
import com.ticketflow.domain.model.OrderId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.domain.model.TicketStatus;
import com.ticketflow.domain.port.OrderRepository;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetOrderStatusUseCaseTest {

    private static final OrderId ID = new OrderId("o-1");
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final GetOrderStatusUseCase useCase = new GetOrderStatusUseCase(orders);

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void execute_existingOrder_returnsViewWithStatus(TicketStatus status) {
        var order = new Order(ID, new EventId("e-1"), new Quantity(3), status, new IdempotencyKey("k"),
                NOW.plusSeconds(600), NOW);
        when(orders.findById(ID)).thenReturn(Mono.just(order));

        StepVerifier.create(useCase.execute(ID))
                .assertNext(view -> {
                    assertThat(view.orderId()).isEqualTo(ID);
                    assertThat(view.eventId()).isEqualTo(new EventId("e-1"));
                    assertThat(view.quantity()).isEqualTo(new Quantity(3));
                    assertThat(view.status()).isEqualTo(status);
                    assertThat(view.reservationExpiresAt()).isEqualTo(NOW.plusSeconds(600));
                    assertThat(view.createdAt()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void execute_unknownOrder_failsWithOrderNotFound() {
        when(orders.findById(ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(ID))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(OrderNotFoundException.class);
                    assertThat(((OrderNotFoundException) e).orderId()).isEqualTo(ID);
                })
                .verify();
    }
}
