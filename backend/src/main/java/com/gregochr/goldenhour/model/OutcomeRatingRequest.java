package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * Request body of {@code PUT /api/outcome/ratings}.
 *
 * @param locationId the location being rated
 * @param eventDate  the date of the sunrise or sunset
 * @param eventType  SUNRISE or SUNSET
 * @param rating     1–5 stars; absent or {@code null} means "didn't see it"
 * @param seen       what the reader says they saw (valley-mist-plan M3). Tri-state: {@code null} (the
 *                   property was not sent) keeps the earlier tag, a choice holding {@code null}
 *                   (an explicit {@code "seen": null}) clears it, a choice holding a tag sets it
 */
public record OutcomeRatingRequest(
        Long locationId,
        LocalDate eventDate,
        TargetType eventType,
        Integer rating,
        SeenChoice seen
) {
}
