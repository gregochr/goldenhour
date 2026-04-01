package com.gregochr.goldenhour.client;

/**
 * Thrown when a postcodes.io postcode lookup fails — either because the postcode
 * is invalid or the service is unavailable.
 */
public class PostcodeLookupException extends RuntimeException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public PostcodeLookupException(String message) {
        super(message);
    }
}
