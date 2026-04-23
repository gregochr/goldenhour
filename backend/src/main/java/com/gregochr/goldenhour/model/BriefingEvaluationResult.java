package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-location Claude evaluation result from a briefing drill-down forecast run.
 *
 * <p>For triaged locations, {@code rating}, {@code fierySkyPotential}, {@code goldenHourPotential}
 * and {@code summary} are all {@code null} and {@code triageReason} + {@code triageMessage} are
 * populated instead. For Claude-scored locations the triage fields are {@code null}.
 *
 * @param locationName        the location that was evaluated
 * @param rating              1-5 star rating, or null for triage / failure
 * @param fierySkyPotential   fiery sky score 0-100, or null
 * @param goldenHourPotential golden hour score 0-100, or null
 * @param summary             Claude's plain-English explanation, or null
 * @param triageReason        categorised stand-down reason, or null if Claude-scored
 * @param triageMessage       formatted stand-down explanation text, or null
 */
public record BriefingEvaluationResult(
        String locationName,
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary,
        @JsonInclude(JsonInclude.Include.NON_NULL) TriageReason triageReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) String triageMessage
) {

    /**
     * Convenience constructor for Claude-scored results (no triage fields).
     *
     * @param locationName        the location
     * @param rating              1-5 rating
     * @param fierySkyPotential   fiery sky score 0-100
     * @param goldenHourPotential golden hour score 0-100
     * @param summary             Claude's explanation
     */
    public BriefingEvaluationResult(String locationName, Integer rating,
            Integer fierySkyPotential, Integer goldenHourPotential, String summary) {
        this(locationName, rating, fierySkyPotential, goldenHourPotential, summary, null, null);
    }

    /**
     * Returns a copy of this result with the rating replaced. All other fields are preserved.
     *
     * @param newRating the rating to apply (may be {@code null} to mark as unscored)
     * @return a new {@code BriefingEvaluationResult} with the updated rating
     */
    public BriefingEvaluationResult withRating(Integer newRating) {
        return new BriefingEvaluationResult(locationName, newRating, fierySkyPotential,
                goldenHourPotential, summary, triageReason, triageMessage);
    }
}
