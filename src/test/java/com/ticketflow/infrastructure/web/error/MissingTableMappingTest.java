package com.ticketflow.infrastructure.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;

/**
 * F-034 / DP-038: a missing DynamoDB table (the startup window before provisioning ends) is a temporary condition
 * answered 503 + Retry-After and counted as a dependency outage; the full exception is logged; any other 4xx
 * DynamoDB error (a bug, not an outage) is still the generic 500.
 */
class MissingTableMappingTest {

    private final AtomicInteger unavailable = new AtomicInteger();
    private final OperationalMetrics metrics = new OperationalMetrics() {
        @Override
        public void dependencyUnavailable() {
            unavailable.incrementAndGet();
        }
    };
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private static ResourceNotFoundException missingTable() {
        return (ResourceNotFoundException) ResourceNotFoundException.builder()
                .message("Cannot do operations on a non-existent table").statusCode(400).build();
    }

    @Test
    void isTransient_resourceNotFound_isTransientButOtherClientErrorsAreNot() {
        assertThat(TransientFailures.isTransient(missingTable())).isTrue();
        assertThat(TransientFailures.isTransient(ResourceInUseException.builder().statusCode(400).build())).isFalse();
        assertThat(TransientFailures.isTransient(AwsServiceException.builder().statusCode(400).build())).isFalse();
    }

    @Test
    void translate_missingTable_is503WithRetryAfterAndNothingInternal() {
        var response = ApiExceptionHandler.translate(missingTable(), "corr-1", metrics);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        var problem = response.getBody();
        assertThat(problem.getType().toString()).isEqualTo("urn:ticketflow:problem:service-unavailable");
        assertThat(problem.getDetail()).doesNotContain("table", "non-existent", "DynamoDb");
        assertThat(unavailable).hasValue(1);
    }

    @Test
    void translate_missingTable_logsTheFullException() {
        ApiExceptionHandler.translate(missingTable(), "corr-1", metrics);

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(ResourceNotFoundException.class.getName());
            assertThat(event.getThrowableProxy().getMessage()).contains("non-existent table");
        });
    }

    @Test
    void translate_otherDynamoDbClientError_staysGeneric500AndIsNotCounted() {
        var response = ApiExceptionHandler.translate(
                ResourceInUseException.builder().message("bad").statusCode(400).build(), "corr-2", metrics);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unavailable).hasValue(0);
    }
}
