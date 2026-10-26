package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideType;

import java.util.Set;

/**
 * Request body for updating location metadata via {@code PUT /api/locations/{id}}.
 *
 * <p><strong>{@code locationTypes} is a set, not a scalar.</strong> The entity has always stored
 * {@code Set<LocationType>}, but this record carried a single value and {@code LocationService}
 * expanded it with {@code Set.of(...)} — a full replace. Editing a location's name therefore
 * silently reduced its type set to one arbitrary element (arbitrary because the set arrives from a
 * {@code HashSet}, whose iteration order is not stable). A wood typed {@code [LANDSCAPE, WOODLAND]}
 * lost {@code WOODLAND} and went back to being scored against a sky it cannot see.
 *
 * @param name             optional new name (cascades to forecast_evaluation)
 * @param lat              optional new latitude (null = don't change)
 * @param lon              optional new longitude (null = don't change)
 * @param solarEventTypes  which solar events to evaluate (null = don't change)
 * @param locationTypes    photography types, e.g. [SEASCAPE, WOODLAND] (null = don't change;
 *                         empty is rejected — a location with no type is not a state the
 *                         evaluation lanes can route)
 * @param tideTypes        tide preferences (null = don't change; only retained when SEASCAPE)
 * @param regionId         optional region ID (null to clear, absent to leave unchanged)
 * @param mistProne        whether mist forms around this location; the only way to set the flag
 *                         on an EXISTING location (null = don't change)
 * @param subRegionId      the sub-region (an area of kind SUB_REGION) to move the location into; it must be
 *                         in the location's region, as changed by this same request (null = don't change)
 */
public record UpdateLocationRequest(
        String name,
        Double lat,
        Double lon,
        Set<SolarEventType> solarEventTypes,
        Set<LocationType> locationTypes,
        Set<TideType> tideTypes,
        Long regionId,
        Boolean mistProne,
        Long subRegionId
) {
    /**
     * Constructor for callers that predate the sub-region field: {@code subRegionId} reads as
     * "don't change".
     *
     * @param name            optional new name
     * @param lat             optional new latitude
     * @param lon             optional new longitude
     * @param solarEventTypes which solar events to evaluate (null = don't change)
     * @param locationTypes   photography types (null = don't change)
     * @param tideTypes       tide preferences (null = don't change)
     * @param regionId        optional region ID
     * @param mistProne       whether mist forms around this location (null = don't change)
     */
    public UpdateLocationRequest(String name, Double lat, Double lon,
            Set<SolarEventType> solarEventTypes, Set<LocationType> locationTypes,
            Set<TideType> tideTypes, Long regionId, Boolean mistProne) {
        this(name, lat, lon, solarEventTypes, locationTypes, tideTypes, regionId, mistProne, null);
    }

    /**
     * Constructor for callers that predate the valley-mist flag: {@code mistProne} reads as
     * "don't change".
     *
     * @param name            optional new name
     * @param lat             optional new latitude
     * @param lon             optional new longitude
     * @param solarEventTypes which solar events to evaluate (null = don't change)
     * @param locationTypes   photography types (null = don't change)
     * @param tideTypes       tide preferences (null = don't change)
     * @param regionId        optional region ID
     */
    public UpdateLocationRequest(String name, Double lat, Double lon,
            Set<SolarEventType> solarEventTypes, Set<LocationType> locationTypes,
            Set<TideType> tideTypes, Long regionId) {
        this(name, lat, lon, solarEventTypes, locationTypes, tideTypes, regionId, null, null);
    }

    /**
     * Compact canonical constructor — defensive copy of mutable sets.
     *
     * <p>Null is preserved rather than coerced to an empty set, because on this record null is
     * meaningful: it is how a partial update says "leave this alone". {@code tideTypes} used to be
     * coerced to {@code Set.of()} here, which made {@code request.tideTypes() != null} in
     * {@code LocationService} permanently true — so a caller that simply omitted the field had the
     * tide preferences of a coastal location wiped rather than left untouched.
     */
    public UpdateLocationRequest {
        solarEventTypes = solarEventTypes == null ? null : Set.copyOf(solarEventTypes);
        locationTypes = locationTypes == null ? null : Set.copyOf(locationTypes);
        tideTypes = tideTypes == null ? null : Set.copyOf(tideTypes);
    }
}
