package com.gregochr.goldenhour.client;

/**
 * Thrown when a postcodes.io lookup worked but found nothing — the postcode is not one the service
 * knows, or no postcode lies near the point a reverse lookup asked about.
 *
 * <p>The API answers it as a 400 ({@code GlobalExceptionHandler}). A lookup that did not work at all
 * (transport failure, 5xx, rate limit) is a {@link PostcodeLookupUnavailableException} instead.
 *
 * <p>⚠️ The handler returns this exception's message as the response body, so a message must be
 * fixed text and never carry the upstream's text or the caller's input.
 */
public class PostcodeLookupException extends RuntimeException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message — fixed text, because it is returned to the caller
     */
    public PostcodeLookupException(String message) {
        super(message);
    }
}
