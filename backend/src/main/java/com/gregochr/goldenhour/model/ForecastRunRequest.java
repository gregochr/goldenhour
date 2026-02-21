package com.gregochr.goldenhour.model;

import java.time.LocalDate;

/**
 * Optional request body for {@code POST /api/forecast/run}.
 *
 * <p>Either field may be {@code null}:
 * <ul>
 *   <li>If {@code date} is null, today's UTC date is used.</li>
 *   <li>If {@code location} is null, forecasts are run for all configured locations.</li>
 * </ul>
 *
 * @param date     the date to forecast (null = today)
 * @param location the name of the location to forecast (null = all configured locations)
 */
public record ForecastRunRequest(
        LocalDate date,
        String location) {
}
