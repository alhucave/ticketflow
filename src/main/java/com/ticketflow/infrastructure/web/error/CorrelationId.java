package com.ticketflow.infrastructure.web.error;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.server.ServerWebExchange;

/**
 * Correlation id rules: a client-supplied {@code X-Correlation-Id} is accepted only when it is 1-64
 * characters of {@code [A-Za-z0-9._-]}; anything else (blank, too long, CR/LF, other characters) is
 * ignored and replaced by a generated UUID, so unsafe input is never echoed, logged or forwarded.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    /** Reactor context key, MDC key and exchange attribute name; also read by the SQS publisher. */
    public static final String KEY = "correlationId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {}

    /** @return the supplied value when it is safe, otherwise a new random id */
    public static String resolve(String supplied) {
        return supplied != null && SAFE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
    }

    /** The id of the current exchange (set by {@link CorrelationIdWebFilter}); generated if the filter did not run. */
    public static String of(ServerWebExchange exchange) {
        return (String) exchange.getAttributes().computeIfAbsent(KEY, k -> UUID.randomUUID().toString());
    }
}
