package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.SeenTag;
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
 * @param seen           what the reader says they saw, or null when they said nothing (omitted from
 *                       the wire when null)
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
        Integer forecastRating,
        @JsonInclude(JsonInclude.Include.NON_NULL) SeenTag seen
) {

    /**
     * A rating carrying no tag.
     *
     * @param locationId     the location rated
     * @param locationName   the location's name
     * @param eventDate      the event's date
     * @param eventType      SUNRISE or SUNSET
     * @param rating         1–5 stars, or {@code null}
     * @param updatedAt      when the rating was last recorded
     * @param forecastRunAt  the age of the forecast the reader saw, or null
     * @param pipelineRunId  the cycle that forecast came from, or null
     * @param forecastRating the predicted star the reader saw, or null
     */
    public OutcomeRatingDto(Long locationId, String locationName, LocalDate eventDate,
            TargetType eventType, Integer rating, Instant updatedAt, Instant forecastRunAt,
            Long pipelineRunId, Integer forecastRating) {
        this(locationId, locationName, eventDate, eventType, rating, updatedAt, forecastRunAt,
                pipelineRunId, forecastRating, null);
    }
}
