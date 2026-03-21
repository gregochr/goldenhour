package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Request body for the aurora forecast run endpoint.
 *
 * <p>Each date represents the evening of that calendar day extending into the early morning
 * of the following day (the dark window from nautical dusk to nautical dawn).
 *
 * @param nights the dates to generate aurora forecasts for (e.g. [2026-03-21, 2026-03-22])
 */
public record AuroraForecastRunRequest(List<LocalDate> nights) {

    /** Compact constructor that defensively copies the mutable list. */
    public AuroraForecastRunRequest {
        nights = nights == null ? List.of() : List.copyOf(nights);
    }
}
