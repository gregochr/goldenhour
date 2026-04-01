package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Groups aurora-eligible locations by their geographic region within the briefing.
 *
 * @param regionName           display name of the geographic region
 * @param verdict              {@code "GO"} if any dark-sky location is clear,
 *                             {@code "STANDDOWN"} if all cloudy, {@code null} if no dark-sky locations
 * @param clearLocationCount   dark-sky locations that passed cloud triage
 * @param totalDarkSkyLocations total locations with non-null Bortle class in the region
 * @param bestBortleClass      lowest (best) Bortle class in the region, or {@code null} if none
 * @param locations            aurora location slots within this region
 */
public record AuroraRegionSummary(
        String regionName,
        String verdict,
        int clearLocationCount,
        int totalDarkSkyLocations,
        Integer bestBortleClass,
        List<AuroraLocationSlot> locations) {

    /** Defensive compact constructor — prevents exposure of mutable list. */
    public AuroraRegionSummary {
        locations = List.copyOf(locations);
    }
}
