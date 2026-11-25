package com.ddf.vodsystem.auth;

/**
 * Thrown when an authenticated user attempts to access a resource they do not own.
 * Mapped to {@code 403 Forbidden}, as opposed to {@link NotAuthenticatedException} ({@code 401}).
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
