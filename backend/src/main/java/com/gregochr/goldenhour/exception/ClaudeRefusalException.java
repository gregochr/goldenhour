package com.gregochr.goldenhour.exception;

/**
 * Thrown when Claude answered a forecast evaluation with {@code stop_reason=refusal}: the model
 * declined to evaluate the place.
 *
 * <p>A distinct type (still an {@link IllegalStateException}, which is what it was before) so the
 * failure can be told apart from other unusable replies by class rather than by its message text.
 */
public class ClaudeRefusalException extends IllegalStateException {

    /**
     * Constructs the exception.
     *
     * @param message the log-side description (never published to the admin panel)
     */
    public ClaudeRefusalException(String message) {
        super(message);
    }
}
