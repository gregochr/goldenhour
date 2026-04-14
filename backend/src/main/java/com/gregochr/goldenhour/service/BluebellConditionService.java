package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores bluebell photography conditions from event-time atmospheric data.
 *
 * <p>Reads from the already-fetched {@link AtmosphericData} snapshot; makes no
 * external API calls. Scoring weights differ by {@link BluebellExposure}: WOODLAND
 * prefers soft overcast light and mist; OPEN_FELL prefers calm wind and golden
 * hour directional light.
 */
@Service
public class BluebellConditionService {

    /** Wind speed threshold for "calm" conditions in km/h. */
    static final double WIND_CALM_THRESHOLD_KMH = 8.0;

    /** Visibility threshold below which mist is flagged, in metres. */
    static final double MIST_VISIBILITY_THRESHOLD_M = 2000.0;

    /** Temperature–dew-point spread below which mist is flagged, in °C. */
    static final double MIST_DEWPOINT_SPREAD_THRESHOLD_C = 2.0;

    /** Average cloud cover threshold above which "soft light" is flagged. */
    static final double SOFT_LIGHT_CLOUD_THRESHOLD = 60.0;

    /** Average cloud cover threshold below which "golden hour light" is flagged. */
    static final double GOLDEN_HOUR_CLOUD_THRESHOLD = 40.0;

    /** Precipitation proxy threshold for "post-rain freshness", in mm. */
    static final double POST_RAIN_THRESHOLD_MM = 0.5;

    /** Precipitation threshold below which conditions are "dry now", in mm. */
    static final double DRY_NOW_THRESHOLD_MM = 0.2;

    /** Conversion factor from m/s to km/h. */
    private static final double MS_TO_KMH = 3.6;

    /**
     * Scores bluebell photography conditions for the given atmospheric data.
     *
     * @param data     event-time atmospheric snapshot (from the forecast evaluation pipeline)
     * @param exposure WOODLAND or OPEN_FELL
     * @return the bluebell condition score
     */
    public BluebellConditionScore score(AtmosphericData data, BluebellExposure exposure) {
        double windKmh = data.weather().windSpeedMs() != null
                ? data.weather().windSpeedMs().doubleValue() * MS_TO_KMH : 0.0;
        int visibilityM = data.weather().visibilityMetres();
        Double dewPoint = data.weather().dewPointCelsius();
        Double temperature = data.comfort().temperatureCelsius();
        double precipMm = data.weather().precipitationMm() != null
                ? data.weather().precipitationMm().doubleValue() : 0.0;

        int lowCloud = data.cloud().lowCloudPercent();
        int midCloud = data.cloud().midCloudPercent();
        int highCloud = data.cloud().highCloudPercent();
        double avgCloud = (lowCloud + midCloud + highCloud) / 3.0;

        // Compute condition flags
        boolean mistyByVisibility = visibilityM < MIST_VISIBILITY_THRESHOLD_M;
        boolean mistyByDewPoint = dewPoint != null && temperature != null
                && (temperature - dewPoint) < MIST_DEWPOINT_SPREAD_THRESHOLD_C;
        boolean misty = mistyByVisibility || mistyByDewPoint;

        boolean calm = windKmh < WIND_CALM_THRESHOLD_KMH;
        boolean softLight = avgCloud > SOFT_LIGHT_CLOUD_THRESHOLD;
        boolean goldenHourLight = avgCloud < GOLDEN_HOUR_CLOUD_THRESHOLD;
        boolean postRain = precipMm >= POST_RAIN_THRESHOLD_MM;
        boolean dryNow = precipMm < DRY_NOW_THRESHOLD_MM;

        int overall = calculateScore(misty, calm, softLight, goldenHourLight,
                postRain, dryNow, exposure);
        String summary = buildSummary(misty, calm, softLight, goldenHourLight,
                postRain, dryNow, exposure);

        return new BluebellConditionScore(overall, misty, calm, softLight, goldenHourLight,
                postRain, dryNow, exposure, summary);
    }

    /**
     * Calculates the overall bluebell score using exposure-aware weighting.
     *
     * @param misty           mist flag
     * @param calm            calm wind flag
     * @param softLight       soft/overcast light flag
     * @param goldenHourLight golden hour / clear light flag
     * @param postRain        post-rain freshness flag
     * @param dryNow          dry at event time flag
     * @param exposure        WOODLAND or OPEN_FELL
     * @return score 0–10
     */
    int calculateScore(boolean misty, boolean calm, boolean softLight,
            boolean goldenHourLight, boolean postRain, boolean dryNow,
            BluebellExposure exposure) {
        double score = 0;

        if (exposure == BluebellExposure.WOODLAND) {
            if (misty) {
                score += 3.0; // dream condition in woodland
            }
            if (calm) {
                score += 2.0; // canopy helps, but still good
            }
            if (softLight) {
                score += 2.5; // diffused light is best for woodland
            }
            if (postRain) {
                score += 1.5; // freshens flowers and ground cover
            }
            if (dryNow) {
                score += 1.0; // comfortable to shoot
            }
        } else {
            // OPEN_FELL: calm wind is critical, golden light works beautifully
            if (misty) {
                score += 3.0; // mist + lake backdrop = extraordinary
            }
            if (calm) {
                score += 3.0; // critical — no canopy shelter on open fell
            }
            if (goldenHourLight) {
                score += 2.0; // directional low sun on open fell
            }
            if (postRain) {
                score += 1.0; // freshens flowers
            }
            if (dryNow) {
                score += 1.0; // comfort
            }
        }

        return (int) Math.min(10, Math.round(score));
    }

    /**
     * Builds a natural-language summary from the condition flags.
     *
     * @param misty           mist flag
     * @param calm            calm wind flag
     * @param softLight       soft/overcast light flag
     * @param goldenHourLight golden hour / clear light flag
     * @param postRain        post-rain freshness flag
     * @param dryNow          dry at event time flag
     * @param exposure        WOODLAND or OPEN_FELL
     * @return plain English summary sentence fragment
     */
    String buildSummary(boolean misty, boolean calm, boolean softLight,
            boolean goldenHourLight, boolean postRain, boolean dryNow,
            BluebellExposure exposure) {
        if (misty && calm) {
            return "Misty and still — perfect conditions";
        }

        List<String> parts = new ArrayList<>();

        if (misty) {
            parts.add("Misty morning");
        }
        if (calm) {
            parts.add("still air");
        } else {
            parts.add("breezy — flowers may blur");
        }

        if (exposure == BluebellExposure.WOODLAND && softLight) {
            parts.add("soft diffused light");
        } else if (exposure == BluebellExposure.OPEN_FELL && goldenHourLight) {
            parts.add("golden hour light");
        }

        if (postRain && dryNow) {
            parts.add("post-rain freshness");
        } else if (!dryNow) {
            parts.add("rain expected during shooting window");
        }

        return String.join(", ", parts);
    }
}
