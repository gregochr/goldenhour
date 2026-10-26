package com.gregochr.goldenhour.exception;

/**
 * Thrown when an admin change to an area or to sub-region membership cannot be made because of the state it
 * would leave behind: deleting a sub-region that still has members, or a membership write that lost a race.
 *
 * <p>Mapped to HTTP 409 by {@link com.gregochr.goldenhour.controller.GlobalExceptionHandler}; the message says
 * what to do instead.
 */
public class AreaConflictException extends RuntimeException {

    /**
     * Constructs the exception.
     *
     * @param message what is in the way, and what to do about it
     */
    public AreaConflictException(String message) {
        super(message);
    }
}
