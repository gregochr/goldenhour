package com.gregochr.goldenhour.model;

/**
 * Claude's evaluation of colour potential for a sunrise or sunset.
 *
 * <p>Returned by {@code EvaluationService} after calling the Anthropic API.
 * All strategies (Haiku, Sonnet, Opus) use the same prompt and return all
 * three scores plus a summary.
 *
 * @param rating              1-5 rating (overall potential)
 * @param fierySkyPotential   dramatic colour potential (0-100)
 * @param goldenHourPotential overall light quality (0-100)
 * @param summary             Claude's plain English explanation
 */
public record SunsetEvaluation(
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary
) {
}
