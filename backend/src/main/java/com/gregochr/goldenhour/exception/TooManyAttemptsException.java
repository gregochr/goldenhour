package com.gregochr.goldenhour.exception;

/**
 * Thrown when a sign-in attempt on the Turnstile fallback path exceeds the per-account budget.
 *
 * <p>{@link com.gregochr.goldenhour.controller.AuthController} catches it and answers the same 429 and
 * sentence as every other attempt bound, so the response does not say which bound was reached.
 */
public class TooManyAttemptsException extends RuntimeException {

    /**
     * Constructs the exception.
     *
     * @param message what was refused (logged, never sent to the client)
     */
    public TooManyAttemptsException(String message) {
        super(message);
    }
}
