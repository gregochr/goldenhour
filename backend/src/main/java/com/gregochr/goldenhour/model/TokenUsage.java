package com.gregochr.goldenhour.model;

/**
 * Captures token usage from an Anthropic API call for cost calculation.
 *
 * @param inputTokens               standard input tokens consumed
 * @param outputTokens              output tokens generated
 * @param cacheCreationInputTokens  tokens written to the prompt cache (5-minute ephemeral)
 * @param cacheReadInputTokens      tokens read from the prompt cache (discounted)
 */
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        long cacheCreationInputTokens,
        long cacheReadInputTokens
) {

    /** Empty usage sentinel for failed calls or non-Anthropic services. */
    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, 0);

    /**
     * Returns the total token count across all categories.
     *
     * @return sum of input, output, cache creation, and cache read tokens
     */
    public long totalTokens() {
        return inputTokens + outputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }
}
