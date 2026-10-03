package com.ticketflow.infrastructure.web;

import com.ticketflow.infrastructure.web.error.AdminAccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Checks the admin API key. Secure by default: with no key configured (null or blank) every admin
 * request is refused ({@link AdminAccessDeniedException#disabled()}), never allowed. The comparison is
 * constant time: both values are hashed to fixed-length digests and compared with
 * {@link MessageDigest#isEqual}, so neither content nor length of the secret leaks through timing.
 * The key and the supplied value are never logged or included in errors.
 */
public final class AdminKeyGuard {

    private final byte[] configuredDigest;

    public AdminKeyGuard(String configuredKey) {
        this.configuredDigest = configuredKey == null || configuredKey.isBlank() ? null : digest(configuredKey);
    }

    public boolean enabled() {
        return configuredDigest != null;
    }

    /**
     * @throws AdminAccessDeniedException disabled (403) when no key is configured, unauthorized (401)
     *         when the supplied key is missing or wrong
     */
    public void check(String suppliedKey) {
        if (configuredDigest == null) {
            throw AdminAccessDeniedException.disabled();
        }
        // Always hash and compare, even when the header is missing, to keep the work uniform.
        byte[] supplied = digest(suppliedKey == null ? "" : suppliedKey);
        boolean equal = MessageDigest.isEqual(configuredDigest, supplied);
        if (suppliedKey == null || !equal) {
            throw AdminAccessDeniedException.unauthorized();
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
