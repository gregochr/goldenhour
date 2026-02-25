package com.gregochr.goldenhour.model;

/**
 * Claude's evaluation of colour potential for a sunrise or sunset.
 *
 * <p>Returned by {@code EvaluationService} after calling the Anthropic API.
 * Which fields are populated depends on the model used:
 * <ul>
 *   <li><b>Haiku</b>: {@code rating} (1–5) is non-null; score fields are null.</li>
 *   <li><b>Sonnet</b>: {@code fierySkyPotential} and {@code goldenHourPotential}
 *       (0–100 each) are non-null; {@code rating} is null.</li>
 * </ul>
 *
 * @param rating              Haiku 1–5 rating; null for Sonnet evaluations
 * @param fierySkyPotential  dramatic colour potential (0–100); null for Haiku evaluations
 * @param goldenHourPotential overall light quality (0–100); null for Haiku evaluations
 * @param summary            Claude's plain English explanation
 */
public record SunsetEvaluation(
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary
) {
}
