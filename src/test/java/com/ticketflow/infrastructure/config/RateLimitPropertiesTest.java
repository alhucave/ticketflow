package com.ticketflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RateLimitPropertiesTest {

    @Test
    void defaults_areOnAndSafe() {
        var properties = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("ticketflow.rate-limit", RateLimitProperties.class);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.trustForwardedFor()).isFalse();
        assertThat(properties.capacity()).isEqualTo(20);
        assertThat(properties.refillPerSecond()).isEqualTo(1d);
        assertThat(properties.adminFailureCapacity()).isEqualTo(5);
        assertThat(properties.maxClients()).isEqualTo(10_000);
        assertThat(properties.idleTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void bind_overridesAreApplied() {
        var source = new MapConfigurationPropertySource(java.util.Map.of(
                "ticketflow.rate-limit.enabled", "false", "ticketflow.rate-limit.capacity", "7",
                "ticketflow.rate-limit.refill-per-second", "0.5", "ticketflow.rate-limit.trust-forwarded-for", "true"));
        var properties = new Binder(source).bindOrCreate("ticketflow.rate-limit", RateLimitProperties.class);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.capacity()).isEqualTo(7);
        assertThat(properties.refillPerSecond()).isEqualTo(0.5);
        assertThat(properties.trustForwardedFor()).isTrue();
    }

    @Test
    void constructor_invalidValues_areRejected() {
        Duration ttl = Duration.ofMinutes(1);
        assertThatThrownBy(() -> new RateLimitProperties(true, 0, 1, 5, 1, 10, ttl, false))
                .hasMessageContaining("capacity");
        assertThatThrownBy(() -> new RateLimitProperties(true, 1, 0, 5, 1, 10, ttl, false))
                .hasMessageContaining("refill-per-second");
        assertThatThrownBy(() -> new RateLimitProperties(true, 1, 1, 0, 1, 10, ttl, false))
                .hasMessageContaining("admin-failure-capacity");
        assertThatThrownBy(() -> new RateLimitProperties(true, 1, 1, 5, 0, 10, ttl, false))
                .hasMessageContaining("admin-failure-refill-per-second");
        assertThatThrownBy(() -> new RateLimitProperties(true, 1, 1, 5, 1, 0, ttl, false))
                .hasMessageContaining("max-clients");
        assertThatThrownBy(() -> new RateLimitProperties(true, 1, 1, 5, 1, 10, Duration.ZERO, false))
                .hasMessageContaining("idle-ttl");
    }
}
