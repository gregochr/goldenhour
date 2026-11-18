package com.gregochr.goldenhour.exception;

/**
 * Thrown when a change would leave a reader with more favourites than the cap (25, places plus areas).
 *
 * <p>Mapped to HTTP 409 by {@link com.gregochr.goldenhour.controller.GlobalExceptionHandler}.
 */
public class FavouritesLimitException extends RuntimeException {

    /**
     * Constructs the exception.
     *
     * @param message what to tell the reader
     */
    public FavouritesLimitException(String message) {
        super(message);
    }
}
