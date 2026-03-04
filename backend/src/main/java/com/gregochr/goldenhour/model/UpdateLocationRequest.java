package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TideType;

import java.util.Set;

/**
 * Request body for updating location metadata via {@code PUT /api/locations/{id}}.
 *
 * @param name           optional new name (cascades to forecast_evaluation and actual_outcome)
 * @param goldenHourType which solar events to evaluate
 * @param locationType   photography type (LANDSCAPE, SEASCAPE, or WILDLIFE)
 * @param tideTypes      tide preferences (empty set = not coastal; only relevant for SEASCAPE)
 * @param regionId       optional region ID (null to clear, absent to leave unchanged)
 */
public record UpdateLocationRequest(
        String name,
        GoldenHourType goldenHourType,
        LocationType locationType,
        Set<TideType> tideTypes,
        Long regionId
) {
    /** Compact canonical constructor — defensive copy of mutable set. */
    public UpdateLocationRequest {
        tideTypes = tideTypes == null ? Set.of() : Set.copyOf(tideTypes);
    }
}
