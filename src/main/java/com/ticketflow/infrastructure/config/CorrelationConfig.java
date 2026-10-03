package com.ticketflow.infrastructure.config;

import com.ticketflow.infrastructure.web.error.CorrelationContextPropagation;
import org.springframework.context.annotation.Configuration;

/** Enables Reactor context -> MDC propagation of the correlation id before any reactive pipeline is assembled. */
@Configuration(proxyBeanMethods = false)
public class CorrelationConfig {

    public CorrelationConfig() {
        CorrelationContextPropagation.install();
    }
}
