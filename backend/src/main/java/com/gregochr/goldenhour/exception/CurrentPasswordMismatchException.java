package com.gregochr.goldenhour.exception;

/**
 * Thrown when a signed-in user asks to change their password but the current password they supplied does
 * not match the stored hash.
 *
 * <p>{@link com.gregochr.goldenhour.controller.AuthController} maps it to a 400, never a 401: the client's
 * axios interceptor refreshes and retries a 401, which would check the password twice and re-send an
 * already-spent Turnstile token.
 */
public class CurrentPasswordMismatchException extends RuntimeException {

    /**
     * Constructs the exception.
     *
     * @param message the client-safe sentence to show
     */
    public CurrentPasswordMismatchException(String message) {
        super(message);
    }
}
