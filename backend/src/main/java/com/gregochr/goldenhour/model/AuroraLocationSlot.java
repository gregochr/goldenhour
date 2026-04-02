package com.gregochr.goldenhour.model;

/**
 * A single aurora-eligible location within the briefing's aurora section,
 * derived from the cached aurora forecast scores after cloud triage.
 *
 * @param locationName         human-readable location name
 * @param bortleClass          Bortle dark-sky class (1 = darkest, 9 = most light-polluted),
 *                             or {@code null} if not yet enriched
 * @param clear                {@code true} if the location passed cloud triage
 *                             (average transect cloud cover below the overcast threshold)
 * @param cloudPercent         average cloud cover across the northward transect (0–100)
 * @param temperatureCelsius   temperature at the location in °C, or {@code null} if unavailable
 * @param windSpeedMs          wind speed at the location in m/s, or {@code null} if unavailable
 * @param weatherCode          WMO weather code at the location, or {@code null} if unavailable
 */
public record AuroraLocationSlot(
        String locationName,
        Integer bortleClass,
        boolean clear,
        int cloudPercent,
        Double temperatureCelsius,
        Double windSpeedMs,
        Integer weatherCode) {
}
