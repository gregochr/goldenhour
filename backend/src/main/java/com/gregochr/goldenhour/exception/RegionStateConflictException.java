package com.gregochr.goldenhour.exception;

/**
 * Thrown when an admin change would leave a switched-on location in a switched-off region: turning a region
 * off while it still has switched-on locations, or switching a location on, adding one, or moving one into a
 * region that is off.
 *
 * <p>Mapped to HTTP 409 by {@link com.gregochr.goldenhour.controller.GlobalExceptionHandler}; the message is a
 * plain sentence the admin UI shows as is.
 */
public class RegionStateConflictException extends RuntimeException {

    /**
     * Constructs the exception.
     *
     * @param message what is in the way, and what to do first
     */
    public RegionStateConflictException(String message) {
        super(message);
    }
}
