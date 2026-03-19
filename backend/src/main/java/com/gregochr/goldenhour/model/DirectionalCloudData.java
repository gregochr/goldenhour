package com.gregochr.goldenhour.model;

/**
 * Cloud cover sampled at the solar and antisolar horizon points, 113 km from the observer.
 *
 * <p>The solar horizon is the direction of the sun (west for sunset, east for sunrise).
 * The antisolar horizon is the opposite direction. Sampling at offset points allows
 * directional cloud assessment that the single-point observer data cannot provide.
 *
 * <p>{@code farSolarLowCloudPercent} is sampled at 226 km (2× the horizon distance) along
 * the solar azimuth. Comparing it to {@code solarLowCloudPercent} reveals whether high solar
 * horizon low cloud is a thin strip (drops sharply beyond the horizon) or an extensive blanket
 * (stays high). May be {@code null} if the additional fetch fails.
 *
 * @param solarLowCloudPercent      low cloud (0-3 km) at the solar horizon point (113 km)
 * @param solarMidCloudPercent      mid cloud (3-8 km) at the solar horizon point (113 km)
 * @param solarHighCloudPercent     high cloud (8+ km) at the solar horizon point (113 km)
 * @param antisolarLowCloudPercent  low cloud (0-3 km) at the antisolar horizon point (113 km)
 * @param antisolarMidCloudPercent  mid cloud (3-8 km) at the antisolar horizon point (113 km)
 * @param antisolarHighCloudPercent high cloud (8+ km) at the antisolar horizon point (113 km)
 * @param farSolarLowCloudPercent   low cloud (0-3 km) at 226 km along the solar azimuth,
 *                                  or {@code null} if unavailable
 */
public record DirectionalCloudData(
        int solarLowCloudPercent,
        int solarMidCloudPercent,
        int solarHighCloudPercent,
        int antisolarLowCloudPercent,
        int antisolarMidCloudPercent,
        int antisolarHighCloudPercent,
        Integer farSolarLowCloudPercent) {
}
