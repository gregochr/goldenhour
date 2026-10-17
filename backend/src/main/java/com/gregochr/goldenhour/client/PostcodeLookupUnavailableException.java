package com.gregochr.goldenhour.client;

/**
 * Thrown when a postcodes.io lookup fails for a reason that says nothing about the postcode — a
 * transport failure, a timeout, a 5xx or a rate limit from the upstream service.
 *
 * <p>Distinct from {@link PostcodeLookupException}, which means the lookup worked and found nothing:
 * a client can tell a reader "that postcode does not exist" (400) from "try again later" (502).
 * Deliberately not a subclass of it, so a handler or {@code catch} for "not found" can never also
 * swallow "unavailable".
 *
 * <p>The message is fixed text. The upstream's own message is logged by the client and never carried
 * here, because a response body built from this exception must not echo the upstream.
 */
public class PostcodeLookupUnavailableException extends RuntimeException {

    /** The only message this exception carries, and the one the API answers with. */
    public static final String MESSAGE = "Postcode lookup is unavailable right now";

    /**
     * Constructs the exception with its fixed message.
     */
    public PostcodeLookupUnavailableException() {
        super(MESSAGE);
    }
}
