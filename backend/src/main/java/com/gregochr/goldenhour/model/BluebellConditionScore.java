package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.BluebellExposure;

/**
 * Bluebell photography condition assessment for a single event slot.
 *
 * <p>Scoring weights vary by {@link BluebellExposure}:
 * WOODLAND prefers soft overcast light; OPEN_FELL prefers golden
 * hour directional light and is more wind-sensitive.</p>
 *
 * @param overall         overall score 1–10
 * @param misty           dream condition — vis &lt; 2 km or temp/dew spread &lt; 2 °C at sunrise
 * @param calm            wind &lt; 8 km/h
 * @param softLight       overcast / high cloud &gt; 60% — good for WOODLAND
 * @param goldenHourLight clear or partly cloudy — good for OPEN_FELL
 * @param postRain        proxy for recent rain (&gt; 0.5 mm precipitation at event time)
 * @param dryNow          precipitation &lt; 0.2 mm at event time — comfortable to shoot
 * @param exposure        WOODLAND or OPEN_FELL
 * @param summary         natural-language summary, e.g. "Misty and still — perfect conditions"
 */
public record BluebellConditionScore(
        int overall,
        boolean misty,
        boolean calm,
        boolean softLight,
        boolean goldenHourLight,
        boolean postRain,
        boolean dryNow,
        BluebellExposure exposure,
        String summary) {

    /**
     * Returns true if conditions are good (score &ge; 6).
     *
     * @return {@code true} when the overall score is at least 6
     */
    public boolean isGood() {
        return overall >= 6;
    }

    /**
     * Returns true if conditions are excellent (score &ge; 8).
     *
     * @return {@code true} when the overall score is at least 8
     */
    public boolean isExcellent() {
        return overall >= 8;
    }

    /**
     * Returns a one-word quality label for the overall score.
     *
     * @return "Excellent", "Good", "Fair", or "Poor"
     */
    public String qualityLabel() {
        if (overall >= 8) {
            return "Excellent";
        }
        if (overall >= 6) {
            return "Good";
        }
        if (overall >= 4) {
            return "Fair";
        }
        return "Poor";
    }
}
