package com.gregochr.goldenhour.client;

/**
 * A fetch of the IMO calendar failed: a transport error, an unexpected HTTP status, or a body the client refuses
 * (oversize, wrong content type, not a PDF).
 */
public class ImoCalendarException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Integer httpStatus;

    /**
     * Constructs the exception.
     *
     * @param message    what went wrong
     * @param httpStatus the HTTP status seen, or null when there was no response
     * @param cause      the underlying error, or null
     */
    public ImoCalendarException(String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /**
     * The HTTP status seen.
     *
     * @return the status, or null when there was no response
     */
    public Integer getHttpStatus() {
        return httpStatus;
    }
}
