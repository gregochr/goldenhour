package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Hourly visibility and dew point trend around the solar event.
 *
 * <p>Extracted from the main Open-Meteo forecast response (no extra API call required).
 * Covers T-3h through T+2h so Claude can determine whether mist is forming or clearing.
 *
 * @param slots hourly slots from earliest to latest, relative to the solar event
 */
public record MistTrend(List<MistSlot> slots) {

    /**
     * Canonical constructor: takes an immutable copy of {@code slots}, so a caller's later changes
     * to the list it passed cannot alter this trend. A null list is rejected with a
     * {@link NullPointerException}.
     */
    public MistTrend {
        slots = List.copyOf(slots);
    }

    /**
     * A single hourly slot in the mist trend.
     *
     * @param hoursRelativeToEvent negative = before event, 0 = event hour, positive = after
     * @param visibilityMetres     horizontal viewing distance in metres
     * @param dewPointCelsius      dew point at 2 m above ground in °C
     * @param temperatureCelsius   air temperature at 2 m above ground in °C
     */
    public record MistSlot(
            int hoursRelativeToEvent,
            int visibilityMetres,
            double dewPointCelsius,
            double temperatureCelsius) {
    }
}
