package com.ticketflow.infrastructure.config;

import java.net.URI;

/** Helpers so configuration objects can print themselves without ever exposing secrets. */
final class SecretMasking {

    static final String MASK = "****";

    private SecretMasking() {}

    /** {@code null} stays {@code null} (so "not configured" is visible), anything else is hidden. */
    static String secret(String value) {
        return value == null ? null : MASK;
    }

    /** Scheme, host and port only: user-info, path and query of an endpoint may carry credentials or tokens. */
    static String endpoint(URI uri) {
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme() + "://";
        String host = uri.getHost() == null ? "" : uri.getHost();
        return scheme + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }
}
