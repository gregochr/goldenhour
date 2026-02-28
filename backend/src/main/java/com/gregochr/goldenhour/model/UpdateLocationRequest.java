package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TideType;

/**
 * Request body for updating location metadata via {@code PUT /api/locations/{id}}.
 *
 * <p>Name and coordinates are immutable after creation (name is FK across
 * forecast_evaluation and actual_outcome).
 *
 * @param goldenHourType which solar events to evaluate
 * @param locationType   photography type (LANDSCAPE, SEASCAPE, or WILDLIFE)
 * @param tideType       tide preference (only relevant for SEASCAPE)
 */
public record UpdateLocationRequest(
        GoldenHourType goldenHourType,
        LocationType locationType,
        TideType tideType
) {
}
