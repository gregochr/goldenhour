package com.gregochr.goldenhour.model;

/**
 * Cloud cover sampled at the solar and antisolar horizon points, 50 km from the observer.
 *
 * <p>The solar horizon is the direction of the sun (west for sunset, east for sunrise).
 * The antisolar horizon is the opposite direction. Sampling at offset points allows
 * directional cloud assessment that the single-point observer data cannot provide.
 *
 * @param solarLowCloudPercent      low cloud (0-3 km) at the solar horizon point
 * @param solarMidCloudPercent      mid cloud (3-8 km) at the solar horizon point
 * @param solarHighCloudPercent     high cloud (8+ km) at the solar horizon point
 * @param antisolarLowCloudPercent  low cloud (0-3 km) at the antisolar horizon point
 * @param antisolarMidCloudPercent  mid cloud (3-8 km) at the antisolar horizon point
 * @param antisolarHighCloudPercent high cloud (8+ km) at the antisolar horizon point
 */
public record DirectionalCloudData(
        int solarLowCloudPercent,
        int solarMidCloudPercent,
        int solarHighCloudPercent,
        int antisolarLowCloudPercent,
        int antisolarMidCloudPercent,
        int antisolarHighCloudPercent) {
}
