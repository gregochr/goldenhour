package com.gregochr.goldenhour.service.runs;

/**
 * A Runs request the server refuses as malformed: a date in the future or before the first run, a month outside
 * the same range, or a run id that is not one. The controller answers it with 400 and the message.
 */
public class RunsRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the request, safe to show an administrator
     */
    public RunsRequestException(String message) {
        super(message);
    }
}
