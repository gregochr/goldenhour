package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies synoptic-scale forecast stability for a grid cell based on
 * Open-Meteo hourly data.
 *
 * <p>Uses four signals — pressure tendency, precipitation probability,
 * WMO weather codes, and wind gust variance — to determine whether the
 * forecast is reliable enough for extended Claude evaluation.
 *
 * <p>All inputs come from data already fetched during the triage cycle;
 * no additional API calls are made.
 */
@Service
public class ForecastStabilityClassifier {

    /** Minimum hourly array length for a valid 4-day forecast (96 hours). */
    private static final int MIN_HOURS_REQUIRED = 24;

    /** Pressure thresholds (hPa). */
    private static final double DEEP_LOW_THRESHOLD = 990.0;
    private static final double HIGH_PRESSURE_THRESHOLD = 1018.0;
    private static final double RAPID_FALL_HPA = -6.0;
    private static final double MODERATE_FALL_HPA = -3.0;
    private static final double RISING_HPA = 2.0;

    /** Precipitation probability thresholds (%). */
    private static final double HIGH_PRECIP_PROB = 70.0;
    private static final double MODERATE_PRECIP_PROB = 50.0;
    private static final double LOW_PRECIP_PROB = 20.0;
    private static final double HIGH_PRECIP_VARIANCE = 400.0;

    /** Wind gust variance threshold (m/s squared). */
    private static final double HIGH_GUST_VARIANCE = 100.0;

    /** WMO weather codes >= this indicate active weather (rain/showers/snow). */
    private static final int ACTIVE_WEATHER_CODE_MIN = 60;

    /** Score thresholds for classification. */
    private static final int UNSETTLED_THRESHOLD = 4;
    private static final int TRANSITIONAL_THRESHOLD = 2;

    /**
     * Classifies forecast stability for a single grid cell.
     *
     * @param gridCellKey grid cell identifier
     * @param gridLat     snapped grid latitude
     * @param gridLng     snapped grid longitude
     * @param hourly      Open-Meteo hourly data covering the forecast window
     * @return stability result with classification and reason
     */
    public GridCellStabilityResult classify(String gridCellKey, double gridLat,
            double gridLng, OpenMeteoForecastResponse.Hourly hourly) {

        if (hourly == null || hourly.getTime() == null
                || hourly.getTime().size() < MIN_HOURS_REQUIRED) {
            return new GridCellStabilityResult(gridCellKey, gridLat, gridLng,
                    ForecastStability.TRANSITIONAL, "Insufficient hourly data", 1);
        }

        List<String> signals = new ArrayList<>();
        int score = 0;

        score += assessPressure(hourly, signals);
        score += assessPrecipitation(hourly, signals);
        score += assessWeatherCodes(hourly, signals);
        score += assessGustVariance(hourly, signals);

        ForecastStability stability;
        if (score >= UNSETTLED_THRESHOLD) {
            stability = ForecastStability.UNSETTLED;
        } else if (score >= TRANSITIONAL_THRESHOLD) {
            stability = ForecastStability.TRANSITIONAL;
        } else {
            stability = ForecastStability.SETTLED;
        }

        return new GridCellStabilityResult(gridCellKey, gridLat, gridLng,
                stability, String.join("; ", signals), stability.evaluationWindowDays());
    }

    private int assessPressure(OpenMeteoForecastResponse.Hourly hourly, List<String> signals) {
        List<Double> pressures = hourly.getPressureMsl();
        if (pressures == null || pressures.isEmpty()) {
            return 0;
        }

        int score = 0;
        double pressureNow = safeDouble(pressures, 0);
        double pressure24h = safeDouble(pressures, Math.min(24, pressures.size() - 1));
        double delta = pressure24h - pressureNow;

        if (delta < RAPID_FALL_HPA) {
            score += 3;
            signals.add(String.format("Pressure falling %.1f hPa/24h (frontal passage likely)",
                    Math.abs(delta)));
        } else if (delta < MODERATE_FALL_HPA) {
            score += 1;
            signals.add(String.format("Pressure easing %.1f hPa/24h", Math.abs(delta)));
        } else if (delta > RISING_HPA) {
            score -= 1;
            signals.add("Pressure rising — pattern stabilising");
        }

        if (pressureNow < DEEP_LOW_THRESHOLD) {
            score += 2;
            signals.add(String.format("Deep low overhead (%.0f hPa)", pressureNow));
        } else if (pressureNow >= HIGH_PRESSURE_THRESHOLD && delta >= -1.0) {
            score -= 1;
            signals.add(String.format("High pressure dominant (%.0f hPa, steady)", pressureNow));
        }

        return score;
    }

    private int assessPrecipitation(OpenMeteoForecastResponse.Hourly hourly, List<String> signals) {
        List<Integer> probs = hourly.getPrecipitationProbability();
        if (probs == null || probs.isEmpty()) {
            return 0;
        }

        int score = 0;
        int maxHours = Math.min(96, probs.size());
        List<Double> values = probs.subList(0, maxHours).stream()
                .map(v -> v != null ? v.doubleValue() : 0.0)
                .toList();

        double maxProb = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double variance = variance(values);

        if (maxProb > HIGH_PRECIP_PROB && variance > HIGH_PRECIP_VARIANCE) {
            score += 3;
            signals.add(String.format(
                    "High precip probability (max %.0f%%) with high variance — timing uncertain",
                    maxProb));
        } else if (maxProb > MODERATE_PRECIP_PROB) {
            score += 1;
            signals.add(String.format("Significant precip probability (max %.0f%%)", maxProb));
        } else if (maxProb < LOW_PRECIP_PROB) {
            score -= 1;
            signals.add("Low precipitation probability throughout");
        }

        return score;
    }

    private int assessWeatherCodes(OpenMeteoForecastResponse.Hourly hourly, List<String> signals) {
        List<Integer> codes = hourly.getWeatherCode();
        if (codes == null || codes.size() <= 48) {
            return 0;
        }

        int endHour = Math.min(96, codes.size());
        boolean active = codes.subList(48, endHour).stream()
                .anyMatch(c -> c != null && c >= ACTIVE_WEATHER_CODE_MIN);

        if (active) {
            signals.add("Active weather codes (rain/showers) in T+2 to T+3 window");
            return 2;
        }
        return 0;
    }

    private int assessGustVariance(OpenMeteoForecastResponse.Hourly hourly, List<String> signals) {
        List<Double> gusts = hourly.getWindGusts10m();
        if (gusts == null || gusts.isEmpty()) {
            return 0;
        }

        int maxHours = Math.min(72, gusts.size());
        List<Double> values = gusts.subList(0, maxHours).stream()
                .map(v -> v != null ? v : 0.0)
                .toList();

        double gustVariance = variance(values);
        if (gustVariance > HIGH_GUST_VARIANCE) {
            signals.add("High wind gust variance — frontal activity likely");
            return 1;
        }
        return 0;
    }

    private double safeDouble(List<Double> list, int index) {
        if (index >= list.size()) {
            return list.get(list.size() - 1) != null ? list.get(list.size() - 1) : 0.0;
        }
        Double val = list.get(index);
        return val != null ? val : 0.0;
    }

    /**
     * Population variance of the given values.
     *
     * @param values list of doubles
     * @return variance
     */
    double variance(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0.0);
    }
}
