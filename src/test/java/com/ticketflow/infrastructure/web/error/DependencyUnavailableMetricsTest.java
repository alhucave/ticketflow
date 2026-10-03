package com.ticketflow.infrastructure.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.domain.exception.EventNotFoundException;
import com.ticketflow.domain.exception.InsufficientInventoryException;
import com.ticketflow.domain.model.EventId;
import com.ticketflow.domain.model.Quantity;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.core.exception.SdkClientException;

/** Only genuine dependency outages (503 "service-unavailable") are counted, never business outcomes. */
class DependencyUnavailableMetricsTest {

    private final AtomicInteger unavailable = new AtomicInteger();
    private final OperationalMetrics metrics = new OperationalMetrics() {
        @Override
        public void dependencyUnavailable() {
            unavailable.incrementAndGet();
        }
    };

    @Test
    void translate_transientInfrastructureFailures_countOneUnavailablePerResponse() {
        var timeout = ApiExceptionHandler.translate(new TimeoutException("slow"), "c-1", metrics);
        var sdk = ApiExceptionHandler.translate(SdkClientException.create("no route"), "c-2", metrics);

        assertThat(timeout.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(sdk.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable).hasValue(2);
    }

    @Test
    void translate_businessAndClientErrors_areNotCounted() {
        ApiExceptionHandler.translate(new InsufficientInventoryException(new EventId("e"), new Quantity(1)), "c",
                metrics);
        ApiExceptionHandler.translate(new EventNotFoundException(new EventId("e")), "c", metrics);
        ApiExceptionHandler.translate(new IllegalStateException("bug"), "c", metrics);

        assertThat(unavailable).hasValue(0);
    }

    @Test
    void translate_withoutMetrics_stillWorks() {
        var response = ApiExceptionHandler.translate(new TimeoutException("slow"), "c-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void handler_withMetrics_countsThroughTheControllerAdviceEntryPoint() {
        var handler = new ApiExceptionHandler(metrics);
        var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/x"));

        assertThat(handler.handle(new TimeoutException("slow"), exchange).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable).hasValue(1);
    }
}
