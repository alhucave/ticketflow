package com.ticketflow.infrastructure.web.error;

/**
 * Admin access refused. The message is fixed and says nothing about the configured key or the value
 * supplied. {@code disabled} means no admin key is configured (the route is closed, 403); otherwise
 * the key was missing or wrong (401).
 */
public class AdminAccessDeniedException extends RuntimeException {

    private final boolean disabled;

    private AdminAccessDeniedException(String message, boolean disabled) {
        super(message);
        this.disabled = disabled;
    }

    public static AdminAccessDeniedException disabled() {
        return new AdminAccessDeniedException("Admin access is disabled", true);
    }

    public static AdminAccessDeniedException unauthorized() {
        return new AdminAccessDeniedException("Admin credentials required", false);
    }

    public boolean isDisabled() {
        return disabled;
    }
}
