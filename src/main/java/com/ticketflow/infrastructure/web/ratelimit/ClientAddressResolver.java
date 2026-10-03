package com.ticketflow.infrastructure.web.ratelimit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.web.server.ServerWebExchange;

/**
 * Identifies the client of a request for rate limiting: the remote (socket) address.
 *
 * <p>{@code X-Forwarded-For} is honoured ONLY when {@code trustForwardedFor} is enabled, i.e. the
 * application sits behind exactly one reverse proxy that appends the address of its peer. In that case
 * the LAST entry is used (the one added by the trusted proxy; earlier entries are client-controlled and
 * spoofable) and it must be a literal IP address; anything else falls back to the socket address. If
 * the application is reachable without that proxy, never enable it: any client could then choose its
 * own bucket.
 */
public final class ClientAddressResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final String UNKNOWN = "unknown";

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private final boolean trustForwardedFor;

    public ClientAddressResolver(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public String resolve(ServerWebExchange exchange) {
        String remote = remoteAddress(exchange.getRequest().getRemoteAddress());
        if (!trustForwardedFor) {
            return remote;
        }
        List<String> lines = exchange.getRequest().getHeaders().get(FORWARDED_FOR);
        if (lines == null || lines.isEmpty()) {
            return remote;
        }
        String[] entries = String.join(",", lines).split(",");
        return parseIp(entries[entries.length - 1].trim()).orElse(remote);
    }

    private static String remoteAddress(InetSocketAddress address) {
        return address == null || address.getAddress() == null ? UNKNOWN : address.getAddress().getHostAddress();
    }

    /**
     * Strict literal parsing in canonical form; never resolves names (a candidate must be a dotted-quad
     * or contain a colon, so no DNS lookup can ever be triggered by client input).
     */
    static Optional<String> parseIp(String candidate) {
        var v4 = IPV4.matcher(candidate);
        if (v4.matches()) {
            for (int group = 1; group <= 4; group++) {
                if (Integer.parseInt(v4.group(group)) > 255) {
                    return Optional.empty();
                }
            }
            return literal(candidate);
        }
        if (candidate.indexOf(':') >= 0 && IPV6_CHARS.matcher(candidate).matches()) {
            return literal(candidate);
        }
        return Optional.empty();
    }

    private static Optional<String> literal(String text) {
        try {
            return Optional.of(InetAddress.getByName(text).getHostAddress());
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}
