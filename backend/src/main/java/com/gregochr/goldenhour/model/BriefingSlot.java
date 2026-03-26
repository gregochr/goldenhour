package com.gregochr.goldenhour.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One location x one solar event assessment in the daily briefing.
 *
 * @param locationName        human-readable location name
 * @param solarEventTime      UTC time of the sunrise or sunset
 * @param verdict             GO, MARGINAL, or STANDDOWN
 * @param lowCloudPercent     observer-point low cloud cover (%)
 * @param precipitationMm     precipitation in mm
 * @param visibilityMetres    visibility in metres
 * @param humidityPercent     relative humidity (%)
 * @param temperatureCelsius           temperature in degrees Celsius
 * @param apparentTemperatureCelsius  feels-like temperature in degrees Celsius
 * @param weatherCode                 WMO weather code, or null if unavailable
 * @param windSpeedMs                 wind speed in m/s
 * @param tideState                   HIGH, MID, LOW, or null for inland
 * @param tideAligned         true if tide matches location preference
 * @param nearestHighTideTime UTC time of nearest high tide, or null
 * @param nearestHighTideHeight height of nearest high tide in metres, or null
 * @param isKingTide          true if the nearest high tide exceeds P95
 * @param isSpringTide        true if the nearest high tide exceeds 125% avg
 * @param flags               human-readable flag strings (e.g. "Sun blocked", "King tide")
 */
public record BriefingSlot(
        String locationName,
        LocalDateTime solarEventTime,
        Verdict verdict,
        int lowCloudPercent,
        BigDecimal precipitationMm,
        int visibilityMetres,
        int humidityPercent,
        Double temperatureCelsius,
        Double apparentTemperatureCelsius,
        Integer weatherCode,
        BigDecimal windSpeedMs,
        String tideState,
        boolean tideAligned,
        LocalDateTime nearestHighTideTime,
        BigDecimal nearestHighTideHeight,
        boolean isKingTide,
        boolean isSpringTide,
        List<String> flags) {
}
