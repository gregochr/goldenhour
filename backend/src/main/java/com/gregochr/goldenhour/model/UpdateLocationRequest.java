package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideType;

import java.util.Set;

/**
 * Request body for updating location metadata via {@code PUT /api/locations/{id}}.
 *
 * @param name             optional new name (cascades to forecast_evaluation and actual_outcome)
 * @param solarEventTypes  which solar events to evaluate (null = don't change)
 * @param locationType     photography type (LANDSCAPE, SEASCAPE, or WILDLIFE)
 * @param tideTypes        tide preferences (empty set = not coastal; only relevant for SEASCAPE)
 * @param regionId         optional region ID (null to clear, absent to leave unchanged)
 */
public record UpdateLocationRequest(
        String name,
        Set<SolarEventType> solarEventTypes,
        LocationType locationType,
        Set<TideType> tideTypes,
        Long regionId
) {
    /** Compact canonical constructor — defensive copy of mutable sets. */
    public UpdateLocationRequest {
        solarEventTypes = solarEventTypes == null ? null : Set.copyOf(solarEventTypes);
        tideTypes = tideTypes == null ? Set.of() : Set.copyOf(tideTypes);
    }
}
