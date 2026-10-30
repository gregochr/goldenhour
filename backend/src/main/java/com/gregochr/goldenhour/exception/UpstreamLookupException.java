package com.gregochr.goldenhour.exception;

/**
 * An upstream lookup (an Open-Meteo call made on an admin's behalf) failed or returned something unusable.
 * Mapped to HTTP 502 so a client can tell "the lookup found nothing" from "the lookup did not work".
 */
public class UpstreamLookupException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what failed, safe to show to the admin
     */
    public UpstreamLookupException(String message) {
        super(message);
    }
}
