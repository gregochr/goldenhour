package com.gregochr.goldenhour.exception;

/**
 * Thrown in place of a Claude call for a place the run never attempted, because the run was stopped
 * (Claude rejected the API key, so every further call would fail the same way).
 *
 * <p>Carries no stack trace: one is thrown per remaining place, and none of them is an event worth a
 * trace in the log. By the time it is thrown the task has already been published FAILED.
 */
public class RunStoppedException extends RuntimeException {

    /**
     * Constructs the exception.
     *
     * @param message the log-side description
     */
    public RunStoppedException(String message) {
        super(message, null, false, false);
    }
}
