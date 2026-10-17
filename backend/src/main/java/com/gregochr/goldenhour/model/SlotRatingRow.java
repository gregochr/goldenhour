package com.gregochr.goldenhour.model;

import java.time.Instant;

/**
 * One user's rating of one slot, joined to their username — the constructor projection behind the
 * admin's "all ratings for this window" read ({@code OutcomeRatingRepository#findAllForSlot}).
 *
 * @param userId        the rating user's id (so the service can mark the caller's own row)
 * @param username      the rating user's username
 * @param rating        1–5 stars, or {@code null} for "didn't see it"
 * @param updatedAt     when the rating was last recorded
 * @param eventTime     the solar event instant stored with the rating
 * @param forecastRunAt the served forecast's submission instant stored with the rating, or null
 * @param pipelineRunId the cycle stored with the rating, or null
 */
public record SlotRatingRow(
        Long userId,
        String username,
        Integer rating,
        Instant updatedAt,
        Instant eventTime,
        Instant forecastRunAt,
        Long pipelineRunId
) {
}
