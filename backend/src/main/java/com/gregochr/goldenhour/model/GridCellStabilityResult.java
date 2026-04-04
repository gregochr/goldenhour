package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ForecastStability;

/**
 * Stability classification for a single Open-Meteo grid cell.
 *
 * @param gridCellKey          grid cell identifier (e.g. "54.7500,-1.6250")
 * @param gridLat              snapped grid latitude
 * @param gridLng              snapped grid longitude
 * @param stability            the classified stability level
 * @param reason               human-readable explanation of the signals
 * @param evaluationWindowDays convenience — {@code stability.evaluationWindowDays()}
 */
public record GridCellStabilityResult(
        String gridCellKey,
        double gridLat,
        double gridLng,
        ForecastStability stability,
        String reason,
        int evaluationWindowDays) {
}
