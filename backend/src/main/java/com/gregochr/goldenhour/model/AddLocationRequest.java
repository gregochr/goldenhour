package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TideType;

/**
 * Request body for adding a new location via {@code POST /api/locations}.
 *
 * @param name            human-readable location identifier (e.g. "Durham UK")
 * @param lat             latitude in decimal degrees
 * @param lon             longitude in decimal degrees
 * @param goldenHourType  which solar events to evaluate (defaults to BOTH_TIMES)
 * @param locationType    photography type (LANDSCAPE, SEASCAPE, or WILDLIFE)
 * @param tideType        tide preference (only relevant for SEASCAPE)
 * @param regionId        optional region ID to associate with the location
 */
public record AddLocationRequest(
        String name,
        double lat,
        double lon,
        GoldenHourType goldenHourType,
        LocationType locationType,
        TideType tideType,
        Long regionId
) {
}
