package com.ticketflow.domain.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PortsContractTest {

    @Test
    void ports_allMethods_returnMonoOrFlux() {
        List<Class<?>> ports = List.of(
                EventRepository.class, InventoryRepository.class, OrderRepository.class, OrderQueuePublisher.class,
                OrderPlacementRepository.class);
        for (Class<?> port : ports) {
            assertThat(port.getDeclaredMethods()).isNotEmpty();
            for (Method m : port.getDeclaredMethods()) {
                assertThat(m.getReturnType())
                        .as("%s.%s", port.getSimpleName(), m.getName())
                        .isIn(Mono.class, Flux.class);
            }
        }
    }
}
