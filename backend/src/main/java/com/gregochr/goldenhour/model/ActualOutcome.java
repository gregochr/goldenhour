package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * DTO for recording an actual observed sunrise or sunset outcome.
 *
 * <p>Received as the request body for {@code POST /api/outcome}.
 * All fields except {@code notes}, {@code fierySkyActual}, {@code goldenHourActual} and
 * {@code actualRating} are required.
 *
 * @param locationLat      latitude of the location in decimal degrees
 * @param locationLon      longitude of the location in decimal degrees
 * @param locationName     human-readable location name
 * @param outcomeDate      date the event was observed
 * @param targetType       SUNRISE or SUNSET
 * @param wentOut          whether the photographer went out to shoot
 * @param fierySkyActual   photographer's observed fiery sky score (0–100), or null
 * @param goldenHourActual photographer's observed golden hour score (0–100), or null
 * @param notes            optional free-text observations
 * @param actualRating     photographer's observed 1–5 star rating of the colour, or null; this is the
 *                         figure the calibration gate compares the forecast's star rating against
 */
public record ActualOutcome(
        double locationLat,
        double locationLon,
        String locationName,
        LocalDate outcomeDate,
        TargetType targetType,
        boolean wentOut,
        Integer fierySkyActual,
        Integer goldenHourActual,
        String notes,
        Integer actualRating
) {
}
