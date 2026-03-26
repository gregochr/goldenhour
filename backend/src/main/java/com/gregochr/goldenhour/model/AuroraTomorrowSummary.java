package com.gregochr.goldenhour.model;

/**
 * Tomorrow night's aurora forecast, derived from NOAA's 3-day Kp forecast.
 * Does not include per-location triage data — cloud cover and visibility are not
 * reliably forecastable 24 hours ahead.
 *
 * @param peakKp  peak forecast Kp during tomorrow night's dark window
 * @param label   human-readable label: {@code "Quiet"} if Kp &lt; 3,
 *                {@code "Worth watching"} if Kp &ge; 4,
 *                {@code "Potentially strong"} if Kp &ge; 6
 */
public record AuroraTomorrowSummary(double peakKp, String label) {
}
