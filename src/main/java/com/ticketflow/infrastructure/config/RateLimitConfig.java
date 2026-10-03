package com.ticketflow.infrastructure.config;

import com.github.benmanes.caffeine.cache.Ticker;
import com.ticketflow.infrastructure.observability.OperationalMetrics;
import com.ticketflow.infrastructure.web.ratelimit.ClientAddressResolver;
import com.ticketflow.infrastructure.web.ratelimit.ClientRateLimiter;
import com.ticketflow.infrastructure.web.ratelimit.RateLimitWebFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the per-client rate limiters (write routes and failed admin-key attempts) and their filter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    public static final String WRITE_LIMITER = "writeRateLimiter";
    public static final String ADMIN_FAILURE_LIMITER = "adminFailureRateLimiter";

    @Bean
    ClientAddressResolver clientAddressResolver(RateLimitProperties properties) {
        return new ClientAddressResolver(properties.trustForwardedFor());
    }

    @Bean(WRITE_LIMITER)
    ClientRateLimiter writeRateLimiter(RateLimitProperties properties) {
        return ClientRateLimiter.create(properties.enabled(), properties.capacity(), properties.refillPerSecond(),
                properties.maxClients(), properties.idleTtl(), Ticker.systemTicker());
    }

    @Bean(ADMIN_FAILURE_LIMITER)
    ClientRateLimiter adminFailureRateLimiter(RateLimitProperties properties) {
        return ClientRateLimiter.create(properties.enabled(), properties.adminFailureCapacity(),
                properties.adminFailureRefillPerSecond(), properties.maxClients(), properties.idleTtl(),
                Ticker.systemTicker());
    }

    @Bean
    RateLimitWebFilter rateLimitWebFilter(@Qualifier(WRITE_LIMITER) ClientRateLimiter limiter,
                                          ClientAddressResolver clients,
                                          ObjectProvider<OperationalMetrics> metrics) {
        return new RateLimitWebFilter(limiter, clients, metrics.getIfAvailable(() -> OperationalMetrics.NOOP));
    }
}
