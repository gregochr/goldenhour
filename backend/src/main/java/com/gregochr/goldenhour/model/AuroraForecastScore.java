package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;

/**
 * Aurora photography score for a single location, produced by {@code AuroraScorer}.
 *
 * <p>Scores are calculated once per NOTIFY event and cached in {@code AuroraStateCache}
 * until the next CLEAR or NOTIFY.
 *
 * @param location      the scored location
 * @param stars         rating from 1 to 5 (inclusive)
 * @param alertLevel    the alert level that triggered this score
 * @param cloudPercent  average cloud cover across the northern transect (0–100)
 * @param summary       one-line push-notification style summary
 * @param detail        multi-line factor breakdown with ✓/–/✗ icons
 */
public record AuroraForecastScore(
        LocationEntity location,
        int stars,
        AlertLevel alertLevel,
        int cloudPercent,
        String summary,
        String detail) {
}
