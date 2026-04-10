package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Groups aurora-eligible locations by their geographic region within the briefing.
 *
 * @param regionName                display name of the geographic region
 * @param verdict                   {@code "GO"} if any dark-sky location is clear,
 *                                  {@code "STANDDOWN"} if all cloudy,
 *                                  {@code null} if no dark-sky locations
 * @param clearLocationCount        dark-sky locations that passed cloud triage
 * @param totalDarkSkyLocations     total locations with non-null Bortle class in the region
 * @param bestBortleClass           lowest (best) Bortle class in the region, or {@code null}
 * @param locations                 aurora location slots within this region
 * @param regionTemperatureCelsius  average temperature across locations in °C, or {@code null}
 * @param regionWindSpeedMs         average wind speed across locations in m/s, or {@code null}
 * @param regionWeatherCode         representative WMO weather code for the region, or {@code null}
 * @param glossHeadline              Claude-generated short headline (~7 words, nullable)
 * @param glossDetail                Claude-generated 2-3 sentence explanation (nullable)
 */
public record AuroraRegionSummary(
        String regionName,
        String verdict,
        int clearLocationCount,
        int totalDarkSkyLocations,
        Integer bestBortleClass,
        List<AuroraLocationSlot> locations,
        Double regionTemperatureCelsius,
        Double regionWindSpeedMs,
        Integer regionWeatherCode,
        String glossHeadline,
        String glossDetail) {

    /** Defensive compact constructor — prevents exposure of mutable list. */
    public AuroraRegionSummary {
        locations = List.copyOf(locations);
    }
}
