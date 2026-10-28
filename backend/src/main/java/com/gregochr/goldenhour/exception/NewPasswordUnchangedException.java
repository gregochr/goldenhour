package com.gregochr.goldenhour.exception;

/**
 * Thrown when a signed-in user asks to change their password to the one they already have.
 *
 * <p>{@link com.gregochr.goldenhour.controller.AuthController} maps it to a 400 with the message as the error
 * body.
 */
public class NewPasswordUnchangedException extends RuntimeException {

    /**
     * Constructs the exception.
     *
     * @param message the client-safe sentence to show
     */
    public NewPasswordUnchangedException(String message) {
        super(message);
    }
}
