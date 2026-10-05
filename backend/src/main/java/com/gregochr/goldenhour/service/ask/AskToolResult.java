package com.gregochr.goldenhour.service.ask;

/**
 * The outcome of one tool call.
 *
 * <p>An error is a result, never an exception: the engine feeds {@code content} back to the model
 * as an error {@code tool_result} so it can correct itself.
 *
 * @param error   true when the call failed and {@code content} is an explanation for the model
 * @param content the JSON the model reads (or the error sentence)
 * @param payload the typed result for tests and callers, or null on an error
 */
public record AskToolResult(boolean error, String content, Object payload) {

    /**
     * An error result.
     *
     * @param message the explanation for the model
     * @return the result
     */
    public static AskToolResult error(String message) {
        return new AskToolResult(true, message, null);
    }
}
