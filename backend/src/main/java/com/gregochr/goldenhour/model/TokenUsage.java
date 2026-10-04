package com.gregochr.goldenhour.model;

import com.anthropic.models.messages.Usage;

/**
 * Captures token usage from an Anthropic API call for cost calculation.
 *
 * @param inputTokens                standard input tokens consumed
 * @param outputTokens               output tokens generated
 * @param cacheCreationInputTokens   tokens written to the prompt cache, of either lifetime
 * @param cacheReadInputTokens       tokens read from the prompt cache (discounted)
 * @param cacheCreationOneHourTokens the portion of {@code cacheCreationInputTokens} written with
 *                                   the 1-hour lifetime (billed at 2x input, not 1.25x); a subset
 *                                   of the total, never additional to it. Zero when the API gave
 *                                   no breakdown.
 */
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        long cacheCreationInputTokens,
        long cacheReadInputTokens,
        long cacheCreationOneHourTokens
) {

    /** Empty usage sentinel for failed calls or non-Anthropic services. */
    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, 0);

    /**
     * Constructs a usage with no 1-hour cache-write portion (every cache write is 5-minute).
     *
     * @param inputTokens              standard input tokens consumed
     * @param outputTokens             output tokens generated
     * @param cacheCreationInputTokens tokens written to the prompt cache
     * @param cacheReadInputTokens     tokens read from the prompt cache
     */
    public TokenUsage(long inputTokens, long outputTokens, long cacheCreationInputTokens,
            long cacheReadInputTokens) {
        this(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens, 0);
    }

    /**
     * Canonical constructor; clamps the 1-hour portion into {@code [0, cacheCreationInputTokens]}
     * so it can never exceed the total it is a subset of.
     *
     * @param inputTokens                standard input tokens consumed
     * @param outputTokens               output tokens generated
     * @param cacheCreationInputTokens   tokens written to the prompt cache
     * @param cacheReadInputTokens       tokens read from the prompt cache
     * @param cacheCreationOneHourTokens 1-hour portion of the cache writes
     */
    public TokenUsage {
        cacheCreationOneHourTokens = Math.max(0,
                Math.min(cacheCreationOneHourTokens, cacheCreationInputTokens));
    }

    /**
     * Builds a usage from an SDK response's usage object, including the 1-hour cache-write
     * breakdown when the API reported one.
     *
     * @param usage the SDK usage object
     * @return the usage; an absent breakdown yields a zero 1-hour portion
     */
    public static TokenUsage from(Usage usage) {
        return new TokenUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                oneHourCacheWrite(usage));
    }

    /**
     * Reads the 1-hour cache-write tokens off an SDK usage object.
     *
     * @param usage the SDK usage object
     * @return tokens written with the 1-hour lifetime, or 0 when the API gave no breakdown
     */
    public static long oneHourCacheWrite(Usage usage) {
        return usage.cacheCreation().map(c -> c.ephemeral1hInputTokens()).orElse(0L);
    }

    /**
     * Returns the total token count across all categories.
     *
     * @return sum of input, output, cache creation, and cache read tokens
     */
    public long totalTokens() {
        return inputTokens + outputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }
}
