package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TideType;

/**
 * Request body for updating location metadata via {@code PUT /api/locations/{id}}.
 *
 * @param name           optional new name (cascades to forecast_evaluation and actual_outcome)
 * @param goldenHourType which solar events to evaluate
 * @param locationType   photography type (LANDSCAPE, SEASCAPE, or WILDLIFE)
 * @param tideType       tide preference (only relevant for SEASCAPE)
 * @param regionId       optional region ID (null to clear, absent to leave unchanged)
 */
public record UpdateLocationRequest(
        String name,
        GoldenHourType goldenHourType,
        LocationType locationType,
        TideType tideType,
        Long regionId
) {
}
