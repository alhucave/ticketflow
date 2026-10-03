package com.ticketflow.infrastructure.web.error;

import com.ticketflow.infrastructure.persistence.DynamoDbOrderRepository;
import java.util.concurrent.TimeoutException;
import reactor.core.Exceptions;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * Recognises infrastructure failures that say "the dependency is momentarily unavailable" (throttling,
 * timeouts, connection errors, retries already exhausted) as opposed to business outcomes or bugs.
 * Used after every business mapping, so a domain exception is never reclassified as an outage.
 */
final class TransientFailures {

    private TransientFailures() {}

    static boolean isTransient(Throwable error) {
        return switch (error) {
            case TimeoutException ignored -> true;
            case SdkClientException ignored -> true;
            case AwsServiceException aws -> aws.isThrottlingException() || aws.statusCode() == 429
                    || aws.statusCode() >= 500 || DynamoDbOrderRepository.isTransient(aws);
            default -> Exceptions.isRetryExhausted(error);
        };
    }
}
