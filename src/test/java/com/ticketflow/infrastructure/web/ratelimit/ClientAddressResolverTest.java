package com.ticketflow.infrastructure.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ClientAddressResolverTest {

    private static MockServerWebExchange exchange(String remote, String... forwardedLines) throws Exception {
        var request = MockServerHttpRequest.get("/orders");
        if (remote != null) {
            request.remoteAddress(new InetSocketAddress(InetAddress.getByName(remote), 4711));
        }
        for (String line : forwardedLines) {
            request.header("X-Forwarded-For", line);
        }
        return MockServerWebExchange.from(request);
    }

    @Test
    void resolve_default_usesTheSocketAddressAndIgnoresForwardedFor() throws Exception {
        var resolver = new ClientAddressResolver(false);
        assertThat(resolver.resolve(exchange("10.1.2.3", "6.6.6.6"))).isEqualTo("10.1.2.3");
    }

    @Test
    void resolve_noRemoteAddress_isOneSharedUnknownBucket() throws Exception {
        assertThat(new ClientAddressResolver(false).resolve(exchange(null))).isEqualTo("unknown");
        assertThat(new ClientAddressResolver(true).resolve(exchange(null, "garbage"))).isEqualTo("unknown");
    }

    @Test
    void resolve_trusted_usesTheLastEntryAddedByTheProxyNotTheSpoofableFirst() throws Exception {
        var resolver = new ClientAddressResolver(true);
        assertThat(resolver.resolve(exchange("10.0.0.1", "6.6.6.6, 203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(exchange("10.0.0.1", "6.6.6.6", "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(exchange("10.0.0.1", "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void resolve_trustedButHeaderAbsent_fallsBackToTheSocketAddress() throws Exception {
        assertThat(new ClientAddressResolver(true).resolve(exchange("10.0.0.1"))).isEqualTo("10.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-ip", "evil.example.com", "ab.cd", "999.1.1.1", "1.2.3", "1.2.3.4.5", "1.2.3.4;x",
            "::zz", "gggg::1", "1.2.3.4, ", "[::1]", "fe80::1%eth0", "1:2:3:4:5:6:7:8:9"})
    void resolve_trustedButInvalidValue_fallsBackToTheSocketAddressNeverUsesIt(String value) throws Exception {
        assertThat(new ClientAddressResolver(true).resolve(exchange("10.0.0.1", value))).isEqualTo("10.0.0.1");
    }

    @Test
    void resolve_ipv6_isCanonicalized() throws Exception {
        var resolver = new ClientAddressResolver(true);
        String canonical = resolver.resolve(exchange("10.0.0.1", "2001:db8::1"));
        assertThat(canonical).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(resolver.resolve(exchange("10.0.0.1", "2001:0DB8:0:0:0:0:0:1"))).isEqualTo(canonical);
    }

    @Test
    void parseIp_neverResolvesNames() {
        // Would block on DNS if handed to InetAddress as a host name; must be rejected syntactically.
        assertThat(ClientAddressResolver.parseIp("localhost")).isEmpty();
        assertThat(ClientAddressResolver.parseIp("1.2.3.4")).contains("1.2.3.4");
        assertThat(ClientAddressResolver.parseIp("::1")).contains("0:0:0:0:0:0:0:1");
    }
}
