package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One location x one solar event assessment in the daily briefing.
 *
 * @param locationName   human-readable location name
 * @param solarEventTime UTC time of the sunrise or sunset
 * @param verdict        GO, MARGINAL, or STANDDOWN
 * @param weather        weather conditions at the observer point
 * @param tide           tide data for coastal locations (all nulls/false for inland)
 * @param flags          human-readable flag strings (e.g. "Sun blocked", "King tide")
 */
public record BriefingSlot(
        String locationName,
        LocalDateTime solarEventTime,
        Verdict verdict,
        @JsonUnwrapped WeatherConditions weather,
        @JsonUnwrapped TideInfo tide,
        List<String> flags) {

    public BriefingSlot {
        flags = List.copyOf(flags);
    }

    /**
     * Weather conditions at the observer point for a briefing slot.
     *
     * @param lowCloudPercent             low cloud cover (%)
     * @param precipitationMm             precipitation in mm
     * @param visibilityMetres            visibility in metres
     * @param humidityPercent             relative humidity (%)
     * @param temperatureCelsius          temperature in degrees Celsius
     * @param apparentTemperatureCelsius  feels-like temperature in degrees Celsius
     * @param weatherCode                 WMO weather code, or null if unavailable
     * @param windSpeedMs                 wind speed in m/s
     */
    public record WeatherConditions(
            int lowCloudPercent,
            BigDecimal precipitationMm,
            int visibilityMetres,
            int humidityPercent,
            Double temperatureCelsius,
            Double apparentTemperatureCelsius,
            Integer weatherCode,
            BigDecimal windSpeedMs) {
    }

    /**
     * Tide data for coastal locations within a briefing slot.
     *
     * @param tideState           HIGH, MID, LOW, or null for inland
     * @param tideAligned         true if tide matches location preference
     * @param nearestHighTideTime UTC time of nearest high tide, or null
     * @param nearestHighTideHeight height of nearest high tide in metres, or null
     * @param isKingTide          true if the nearest high tide exceeds P95
     * @param isSpringTide        true if the nearest high tide exceeds 125% avg
     */
    public record TideInfo(
            String tideState,
            boolean tideAligned,
            LocalDateTime nearestHighTideTime,
            BigDecimal nearestHighTideHeight,
            boolean isKingTide,
            boolean isSpringTide) {

        /** Tide info for inland locations with no tide data. */
        public static final TideInfo NONE =
                new TideInfo(null, false, null, null, false, false);
    }
}
