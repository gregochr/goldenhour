package com.gregochr.goldenhour.exception;

/**
 * Thrown when an area name, area alias or location alias clashes with another name Ask understands.
 *
 * <p>Mapped to HTTP 409 by {@link com.gregochr.goldenhour.controller.GlobalExceptionHandler}; the message names
 * the clashing name.
 */
public class NameClashException extends RuntimeException {

    /**
     * Constructs the exception.
     *
     * @param message what clashed with what
     */
    public NameClashException(String message) {
        super(message);
    }
}
