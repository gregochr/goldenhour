package com.gregochr.goldenhour.service.ask;

/**
 * The outcome of one tool call.
 *
 * <p>An error is a result, never an exception: the engine feeds {@code content} back to the model
 * as an error {@code tool_result} so it can correct itself.
 *
 * @param <T>     the typed result of the tool that produced it
 * @param error   true when the call failed and {@code content} is an explanation for the model
 * @param content the JSON the model reads (or the error sentence)
 * @param payload the typed result for tests and callers, or null on an error
 */
public record AskToolResult<T>(boolean error, String content, T payload) {

    /**
     * An error result.
     *
     * @param message the explanation for the model
     * @param <T>     the typed result the failed tool would have returned
     * @return the result
     */
    public static <T> AskToolResult<T> error(String message) {
        return new AskToolResult<>(true, message, null);
    }
}
