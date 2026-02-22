package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * Optional request body for {@code POST /api/forecast/run}.
 *
 * <p>Any field may be {@code null}:
 * <ul>
 *   <li>If {@code date} is null, today's UTC date is used.</li>
 *   <li>If {@code location} is null, forecasts are run for all configured locations.</li>
 *   <li>If {@code targetType} is null, both SUNRISE and SUNSET are evaluated.</li>
 * </ul>
 *
 * @param date       the date to forecast (null = today)
 * @param location   the name of the location to forecast (null = all configured locations)
 * @param targetType SUNRISE or SUNSET (null = both)
 */
public record ForecastRunRequest(
        LocalDate date,
        String location,
        TargetType targetType) {
}
