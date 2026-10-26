package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideType;

import java.util.Set;

/**
 * Request body for adding a new location via {@code POST /api/locations}.
 *
 * @param name               human-readable location identifier (e.g. "Durham UK")
 * @param lat                latitude in decimal degrees
 * @param lon                longitude in decimal degrees
 * @param solarEventTypes    which solar events to evaluate (defaults to [SUNRISE, SUNSET])
 * @param locationTypes      photography types, e.g. [SEASCAPE, WOODLAND] (defaults to [LANDSCAPE]).
 *                           A set, not a scalar — a site can be both a wood and a landscape, and
 *                           the two route to different evaluation lanes
 * @param tideTypes          tide preferences (only relevant for SEASCAPE; empty set = not coastal)
 * @param regionId           optional region ID to associate with the location
 * @param bortleClass        auto-detected Bortle class (nullable)
 * @param skyBrightnessSqm   auto-detected sky brightness in mag/arcsec² (nullable)
 * @param elevationMetres    auto-detected elevation in metres (nullable)
 * @param gridLat            Open-Meteo snapped grid latitude (nullable)
 * @param gridLng            Open-Meteo snapped grid longitude (nullable)
 * @param overlooksWater     manual toggle — location overlooks a body of water
 * @param coastalTidal       manual toggle — location is coastal and tidally influenced
 * @param mistProne          manual toggle — mist forms around this location (valley floor,
 *                           riverside, lake shore); null is read as false
 * @param subRegionId        optional sub-region (an area of kind SUB_REGION) to place the location in; it must
 *                           be in the location's region
 */
public record AddLocationRequest(
        String name,
        double lat,
        double lon,
        Set<SolarEventType> solarEventTypes,
        Set<LocationType> locationTypes,
        Set<TideType> tideTypes,
        Long regionId,
        Integer bortleClass,
        Double skyBrightnessSqm,
        Integer elevationMetres,
        Double gridLat,
        Double gridLng,
        Boolean overlooksWater,
        Boolean coastalTidal,
        Boolean mistProne,
        Long subRegionId
) {
    /**
     * Constructor for callers that predate the sub-region field: {@code subRegionId} reads as absent.
     *
     * @param name             human-readable location identifier
     * @param lat              latitude in decimal degrees
     * @param lon              longitude in decimal degrees
     * @param solarEventTypes  which solar events to evaluate
     * @param locationTypes    photography types
     * @param tideTypes        tide preferences
     * @param regionId         optional region ID
     * @param bortleClass      auto-detected Bortle class (nullable)
     * @param skyBrightnessSqm auto-detected sky brightness (nullable)
     * @param elevationMetres  auto-detected elevation in metres (nullable)
     * @param gridLat          Open-Meteo snapped grid latitude (nullable)
     * @param gridLng          Open-Meteo snapped grid longitude (nullable)
     * @param overlooksWater   manual toggle — overlooks a body of water
     * @param coastalTidal     manual toggle — coastal and tidally influenced
     * @param mistProne        manual toggle — mist forms around this location
     */
    public AddLocationRequest(String name, double lat, double lon,
            Set<SolarEventType> solarEventTypes, Set<LocationType> locationTypes,
            Set<TideType> tideTypes, Long regionId, Integer bortleClass, Double skyBrightnessSqm,
            Integer elevationMetres, Double gridLat, Double gridLng, Boolean overlooksWater,
            Boolean coastalTidal, Boolean mistProne) {
        this(name, lat, lon, solarEventTypes, locationTypes, tideTypes, regionId, bortleClass,
                skyBrightnessSqm, elevationMetres, gridLat, gridLng, overlooksWater, coastalTidal,
                mistProne, null);
    }

    /**
     * Constructor for callers that predate the valley-mist flag: {@code mistProne} reads as absent.
     *
     * @param name             human-readable location identifier
     * @param lat              latitude in decimal degrees
     * @param lon              longitude in decimal degrees
     * @param solarEventTypes  which solar events to evaluate
     * @param locationTypes    photography types
     * @param tideTypes        tide preferences
     * @param regionId         optional region ID
     * @param bortleClass      auto-detected Bortle class (nullable)
     * @param skyBrightnessSqm auto-detected sky brightness (nullable)
     * @param elevationMetres  auto-detected elevation in metres (nullable)
     * @param gridLat          Open-Meteo snapped grid latitude (nullable)
     * @param gridLng          Open-Meteo snapped grid longitude (nullable)
     * @param overlooksWater   manual toggle — overlooks a body of water
     * @param coastalTidal     manual toggle — coastal and tidally influenced
     */
    public AddLocationRequest(String name, double lat, double lon,
            Set<SolarEventType> solarEventTypes, Set<LocationType> locationTypes,
            Set<TideType> tideTypes, Long regionId, Integer bortleClass, Double skyBrightnessSqm,
            Integer elevationMetres, Double gridLat, Double gridLng, Boolean overlooksWater,
            Boolean coastalTidal) {
        this(name, lat, lon, solarEventTypes, locationTypes, tideTypes, regionId, bortleClass,
                skyBrightnessSqm, elevationMetres, gridLat, gridLng, overlooksWater, coastalTidal,
                null, null);
    }

    /** Compact canonical constructor — defensive copy of mutable sets. */
    public AddLocationRequest {
        solarEventTypes = solarEventTypes == null ? Set.of() : Set.copyOf(solarEventTypes);
        locationTypes = locationTypes == null ? Set.of() : Set.copyOf(locationTypes);
        tideTypes = tideTypes == null ? Set.of() : Set.copyOf(tideTypes);
    }
}
