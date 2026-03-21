package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Aggregate of all live NOAA SWPC data used for aurora alerting.
 *
 * <p>Each field is sourced from a different NOAA endpoint, cached independently
 * according to that endpoint's update frequency.
 *
 * @param recentKp        last several Kp readings (3-hourly, oldest first)
 * @param kpForecast      3-day Kp forecast windows (oldest first)
 * @param ovation         OVATION aurora probability nowcast at UK latitude (~55°N)
 * @param recentSolarWind recent solar wind measurements (1-minute cadence, oldest first)
 * @param activeAlerts    active NOAA G-scale watches, warnings, and alerts
 */
public record SpaceWeatherData(
        List<KpReading> recentKp,
        List<KpForecast> kpForecast,
        OvationReading ovation,
        List<SolarWindReading> recentSolarWind,
        List<SpaceWeatherAlert> activeAlerts) {

    /**
     * Compact constructor that defensively copies all mutable lists.
     */
    public SpaceWeatherData {
        recentKp = List.copyOf(recentKp);
        kpForecast = List.copyOf(kpForecast);
        recentSolarWind = List.copyOf(recentSolarWind);
        activeAlerts = List.copyOf(activeAlerts);
    }
}
