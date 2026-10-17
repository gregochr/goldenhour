package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One reader's own rating of a past sunrise or sunset, as served by {@code GET /api/outcome/ratings}
 * and returned by {@code PUT /api/outcome/ratings}.
 *
 * @param locationId     the location rated
 * @param locationName   the location's name
 * @param eventDate      the event's date
 * @param eventType      SUNRISE or SUNSET
 * @param rating         1–5 stars, or {@code null} for "didn't see it"
 * @param updatedAt      when the rating was last recorded
 * @param forecastRunAt  the age of the forecast the reader saw — its submission instant — or null
 * @param pipelineRunId  the cycle that forecast came from, or null (an ad-hoc run, a legacy row)
 * @param forecastRating the predicted star the reader saw, or null when none was served or it is not
 *                       recoverable
 */
public record OutcomeRatingDto(
        Long locationId,
        String locationName,
        LocalDate eventDate,
        TargetType eventType,
        Integer rating,
        Instant updatedAt,
        Instant forecastRunAt,
        Long pipelineRunId,
        Integer forecastRating
) {
}
