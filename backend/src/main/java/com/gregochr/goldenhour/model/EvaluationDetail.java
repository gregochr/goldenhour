package com.gregochr.goldenhour.model;

/**
 * Captures the full detail of a Claude evaluation, including the prompt sent
 * and raw response text, for model comparison and debugging.
 *
 * @param evaluation   the parsed evaluation result
 * @param promptSent   the exact user message sent to Claude
 * @param rawResponse  the raw text response from Claude (before parsing)
 * @param durationMs   how long the Claude API call took in milliseconds
 */
public record EvaluationDetail(
        SunsetEvaluation evaluation,
        String promptSent,
        String rawResponse,
        long durationMs
) {
}
