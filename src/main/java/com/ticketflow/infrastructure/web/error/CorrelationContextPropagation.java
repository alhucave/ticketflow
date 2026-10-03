package com.ticketflow.infrastructure.web.error;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;

/**
 * Bridges the Reactor context key {@link CorrelationId#KEY} to the SLF4J MDC.
 *
 * <p>Mechanism: a {@link ThreadLocalAccessor} for that key is registered in the micrometer
 * {@code ContextRegistry} and Reactor's automatic context propagation is enabled
 * ({@code Hooks.enableAutomaticContextPropagation()}). Reactor then restores the MDC value around every
 * signal delivered to a subscriber whose context carries the key and clears it afterwards, whichever
 * thread (Netty event loop, parallel scheduler, AWS SDK callback) runs the continuation, so the value
 * never leaks to the next request handled by the same thread.
 */
public final class CorrelationContextPropagation {

    private static final ThreadLocalAccessor<String> MDC_ACCESSOR = new ThreadLocalAccessor<>() {
        @Override
        public Object key() {
            return CorrelationId.KEY;
        }

        @Override
        public String getValue() {
            return MDC.get(CorrelationId.KEY);
        }

        @Override
        public void setValue(String value) {
            MDC.put(CorrelationId.KEY, value);
        }

        @Override
        public void setValue() {
            MDC.remove(CorrelationId.KEY);
        }
    };

    private CorrelationContextPropagation() {}

    /** Idempotent: safe to call from several application contexts in the same JVM. */
    public static synchronized void install() {
        ContextRegistry registry = ContextRegistry.getInstance();
        if (registry.getThreadLocalAccessors().stream().noneMatch(a -> CorrelationId.KEY.equals(a.key()))) {
            registry.registerThreadLocalAccessor(MDC_ACCESSOR);
        }
        Hooks.enableAutomaticContextPropagation();
    }
}
