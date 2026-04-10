package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideStatisticalSize;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One location x one solar event assessment in the daily briefing.
 *
 * @param locationName    human-readable location name
 * @param solarEventTime  UTC time of the sunrise or sunset
 * @param verdict         GO, MARGINAL, or STANDDOWN
 * @param weather         weather conditions at the observer point
 * @param tide            tide data for coastal locations (all nulls/false for inland)
 * @param flags           human-readable flag strings (e.g. "Sun blocked", "King tide")
 * @param standdownReason primary reason for STANDDOWN verdict, null for GO/MARGINAL
 */
public record BriefingSlot(
        String locationName,
        LocalDateTime solarEventTime,
        Verdict verdict,
        @JsonUnwrapped WeatherConditions weather,
        @JsonUnwrapped TideInfo tide,
        List<String> flags,
        String standdownReason) {

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
     * @param midCloudPercent             mid-level cloud cover (%)
     * @param highCloudPercent            high-level cloud cover (%)
     */
    public record WeatherConditions(
            int lowCloudPercent,
            BigDecimal precipitationMm,
            int visibilityMetres,
            int humidityPercent,
            Double temperatureCelsius,
            Double apparentTemperatureCelsius,
            Integer weatherCode,
            BigDecimal windSpeedMs,
            int midCloudPercent,
            int highCloudPercent) {
    }

    /**
     * Tide data for coastal locations within a briefing slot.
     *
     * @param tideState           HIGH, MID, LOW, or null for inland
     * @param tideAligned         true if tide matches location preference
     * @param nearestHighTideTime UTC time of nearest high tide, or null
     * @param nearestHighTideHeight height of nearest high tide in metres, or null
     * @param isKingTide          true if the nearest high tide exceeds P95 (statistical)
     * @param isSpringTide        true if the nearest high tide exceeds 125% avg (statistical)
     * @param lunarTideType       astronomical tide classification, or null for inland
     * @param lunarPhase          human-readable moon phase name, or null for inland
     * @param moonAtPerigee       true if the moon is near perigee, or null for inland
     */
    public record TideInfo(
            String tideState,
            boolean tideAligned,
            LocalDateTime nearestHighTideTime,
            BigDecimal nearestHighTideHeight,
            boolean isKingTide,
            boolean isSpringTide,
            LunarTideType lunarTideType,
            String lunarPhase,
            Boolean moonAtPerigee) {

        /** Tide info for inland locations with no tide data. */
        public static final TideInfo NONE =
                new TideInfo(null, false, null, null, false, false, null, null, null);

        /**
         * Derives the statistical size classification from the existing boolean flags.
         *
         * @return {@link TideStatisticalSize#EXTRA_EXTRA_HIGH} if {@code isKingTide},
         *         {@link TideStatisticalSize#EXTRA_HIGH} if {@code isSpringTide},
         *         or {@code null} for regular-sized tides
         */
        public TideStatisticalSize statisticalSize() {
            if (isKingTide) {
                return TideStatisticalSize.EXTRA_EXTRA_HIGH;
            }
            if (isSpringTide) {
                return TideStatisticalSize.EXTRA_HIGH;
            }
            return null;
        }
    }
}
