package com.ticketflow.infrastructure.web.error;

import com.ticketflow.infrastructure.persistence.DynamoDbOrderRepository;
import java.util.concurrent.TimeoutException;
import reactor.core.Exceptions;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Recognises infrastructure failures that say "the dependency is momentarily unavailable" (throttling,
 * timeouts, connection errors, retries already exhausted) as opposed to business outcomes or bugs.
 * Used after every business mapping, so a domain exception is never reclassified as an outage.
 *
 * <p>A DynamoDB {@link ResourceNotFoundException} ("Cannot do operations on a non-existent table") counts as
 * transient on purpose (F-034, DP-038): the application accepts traffic before the startup provisioning has
 * created the tables, so a missing table is, in practice, the expected "not ready yet" condition. The adapters
 * never raise it for a missing item (an absent item is an empty result), so it can only mean a missing table.
 * A table that stays missing is not hidden: readiness stays DOWN and every such response logs the full exception.
 */
final class TransientFailures {

    private TransientFailures() {}

    static boolean isTransient(Throwable error) {
        return switch (error) {
            case TimeoutException ignored -> true;
            case SdkClientException ignored -> true;
            case ResourceNotFoundException ignored -> true;
            case AwsServiceException aws -> aws.isThrottlingException() || aws.statusCode() == 429
                    || aws.statusCode() >= 500 || DynamoDbOrderRepository.isTransient(aws);
            default -> Exceptions.isRetryExhausted(error);
        };
    }
}
