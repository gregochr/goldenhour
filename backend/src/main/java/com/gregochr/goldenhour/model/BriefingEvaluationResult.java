package com.gregochr.goldenhour.model;

/**
 * Per-location Claude evaluation result from a briefing drill-down forecast run.
 *
 * @param locationName        the location that was evaluated
 * @param rating              1-5 star rating, or null on failure
 * @param fierySkyPotential   fiery sky score 0-100, or null
 * @param goldenHourPotential golden hour score 0-100, or null
 * @param summary             Claude's plain-English explanation, or null
 */
public record BriefingEvaluationResult(
        String locationName,
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary
) {
}
