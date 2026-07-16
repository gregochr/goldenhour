package com.gregochr.goldenhour.exception;

/**
 * Thrown when an authentication attempt is rejected — bad credentials, a disabled account, or an
 * invalid, expired, or revoked refresh token.
 *
 * <p>The message is deliberately client-safe (no internal detail); callers map this exception to
 * a 401 response with the message as the error body.
 */
public class InvalidCredentialsException extends RuntimeException {

    /**
     * Creates the exception with a client-safe error message.
     *
     * @param message the reason the authentication attempt was rejected
     */
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
