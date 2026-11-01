package com.gregochr.goldenhour.exception;

/**
 * Thrown when an admin pauses, resumes or triggers a scheduled job whose feature flag is off
 * ({@code DISABLED_BY_CONFIG}): the request is understood but the job's state forbids it.
 *
 * <p>An {@link IllegalStateException} so existing callers and tests that catch that type still do; mapped to
 * HTTP 409 by {@link com.gregochr.goldenhour.controller.GlobalExceptionHandler}, not the catch-all 500.
 */
public class JobDisabledByConfigException extends IllegalStateException {

    /**
     * Constructs the exception.
     *
     * @param message names the job and the config key that switches it off
     */
    public JobDisabledByConfigException(String message) {
        super(message);
    }
}
