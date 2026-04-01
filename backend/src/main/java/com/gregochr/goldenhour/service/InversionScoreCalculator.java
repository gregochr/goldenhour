package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.WeatherData;

/**
 * Computes a cloud inversion likelihood score (0–10) from atmospheric data.
 *
 * <p>Cloud inversions form when warm air traps cooler, moist air near the surface.
 * For elevated viewpoints overlooking water, this produces a dramatic "sea of clouds"
 * effect. The score is derived from four weather signals:
 * <ul>
 *   <li>Temperature–dew point gap (smaller gap → more moisture → higher score)</li>
 *   <li>Wind speed (light winds favour stable inversions)</li>
 *   <li>Humidity (high humidity increases fog/low cloud likelihood)</li>
 *   <li>Low cloud cover (some low cloud indicates inversion already forming)</li>
 * </ul>
 *
 * <p>Only meaningful for locations with {@code elevation >= 300m} and
 * {@code overlooksWater = true}. The caller gates on those conditions.
 */
public final class InversionScoreCalculator {

    /** Minimum elevation in metres for inversion relevance. */
    public static final int MIN_ELEVATION_METRES = 300;

    private InversionScoreCalculator() {
    }

    /**
     * Computes the inversion score from atmospheric data.
     *
     * @param data the atmospheric data at event time
     * @return a score between 0 (no inversion) and 10 (near-certain inversion), or null
     *         if required weather fields are missing
     */
    public static Double calculate(AtmosphericData data) {
        if (data == null || data.weather() == null || data.cloud() == null) {
            return null;
        }
        WeatherData w = data.weather();
        if (w.dewPointCelsius() == null || data.comfort() == null
                || data.comfort().temperatureCelsius() == null) {
            return null;
        }

        double tempDewGap = data.comfort().temperatureCelsius() - w.dewPointCelsius();
        double windSpeed = w.windSpeedMs().doubleValue();
        int humidity = w.humidityPercent();
        int lowCloud = data.cloud().lowCloudPercent();

        // Temperature-dew point gap: smaller is better (0-4 points)
        double gapScore;
        if (tempDewGap <= 1.0) {
            gapScore = 4.0;
        } else if (tempDewGap <= 2.0) {
            gapScore = 3.0;
        } else if (tempDewGap <= 4.0) {
            gapScore = 2.0;
        } else if (tempDewGap <= 6.0) {
            gapScore = 1.0;
        } else {
            gapScore = 0.0;
        }

        // Wind speed: light winds favour inversions (0-3 points)
        double windScore;
        if (windSpeed <= 1.5) {
            windScore = 3.0;
        } else if (windSpeed <= 3.0) {
            windScore = 2.0;
        } else if (windSpeed <= 5.0) {
            windScore = 1.0;
        } else {
            windScore = 0.0;
        }

        // Humidity: high humidity = more moisture available (0-2 points)
        double humidityScore;
        if (humidity >= 90) {
            humidityScore = 2.0;
        } else if (humidity >= 80) {
            humidityScore = 1.5;
        } else if (humidity >= 70) {
            humidityScore = 1.0;
        } else {
            humidityScore = 0.0;
        }

        // Low cloud presence: moderate low cloud suggests inversion layer (0-1 point)
        // Too much (>70%) means overcast; ideal is 20-60%
        double cloudScore;
        if (lowCloud >= 20 && lowCloud <= 60) {
            cloudScore = 1.0;
        } else if (lowCloud > 60) {
            cloudScore = 0.5;
        } else {
            cloudScore = 0.0;
        }

        return Math.min(10.0, gapScore + windScore + humidityScore + cloudScore);
    }
}
