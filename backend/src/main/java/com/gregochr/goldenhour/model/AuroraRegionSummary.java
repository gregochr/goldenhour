package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Groups aurora-eligible locations by their geographic region within the briefing.
 *
 * @param regionName  display name of the geographic region
 * @param locations   aurora location slots within this region
 */
public record AuroraRegionSummary(
        String regionName,
        List<AuroraLocationSlot> locations) {

    /** Defensive compact constructor — prevents exposure of mutable list. */
    public AuroraRegionSummary {
        locations = List.copyOf(locations);
    }
}
