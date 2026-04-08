package com.gregochr.goldenhour.exception;

/**
 * Thrown when a user attempts to register but the early-access cap has been reached.
 *
 * <p>Mapped to HTTP 403 by {@link com.gregochr.goldenhour.controller.GlobalExceptionHandler}.
 */
public class RegistrationClosedException extends RuntimeException {

    /**
     * Constructs a new exception with the default message.
     */
    public RegistrationClosedException() {
        super("Early access is currently full");
    }
}
