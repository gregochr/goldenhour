package com.gregochr.goldenhour.model;

/**
 * Claude's evaluation of colour potential for a sunrise or sunset.
 *
 * <p>Returned by {@code EvaluationService} after calling the Anthropic API.
 * The rating is on a 1-5 scale: 1 = poor, 5 = exceptional.
 *
 * @param rating  colour potential score from 1 (poor) to 5 (exceptional)
 * @param summary Claude's plain English explanation of the rating
 */
public record SunsetEvaluation(
        int rating,
        String summary
) {
}
