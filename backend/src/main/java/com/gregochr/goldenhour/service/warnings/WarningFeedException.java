package com.gregochr.goldenhour.service.warnings;

/**
 * A weather-warning feed or detail document that could not be read. Thrown only by {@link WarningFeedParser}; the
 * poller turns it into a skipped-and-counted entry (detail) or a failed poll (whole feed).
 */
public class WarningFeedException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what could not be read (never the document itself)
     */
    public WarningFeedException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param message what could not be read
     * @param cause   the underlying parse failure
     */
    public WarningFeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
