package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * Parameters for a single sunrise or sunset forecast evaluation request.
 *
 * <p>Maps to the query parameters accepted by {@code ForecastController}.
 *
 * @param latitude     latitude of the location in decimal degrees
 * @param longitude    longitude of the location in decimal degrees
 * @param locationName human-readable location name (e.g. "Durham UK")
 * @param date         the date to forecast
 * @param targetType   SUNRISE or SUNSET
 */
public record ForecastRequest(
        double latitude,
        double longitude,
        String locationName,
        LocalDate date,
        TargetType targetType
) {
}
