package com.gregochr.goldenhour.exception;

/**
 * Thrown when Claude answered a forecast evaluation but the reply cannot be used: it was cut short
 * (the {@code max_tokens} or context-window stop reasons) or it carried no text at all.
 *
 * <p>A distinct type (still an {@link IllegalStateException}, which is what these were before) so the
 * failure can be told apart by class rather than by its message text.
 */
public class ClaudeReplyUnreadableException extends IllegalStateException {

    /**
     * Constructs the exception.
     *
     * @param message the log-side description (never published to the admin panel)
     */
    public ClaudeReplyUnreadableException(String message) {
        super(message);
    }
}
