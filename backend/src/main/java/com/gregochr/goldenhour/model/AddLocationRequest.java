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
 * @param locationType       photography type (LANDSCAPE, SEASCAPE, or WILDLIFE)
 * @param tideTypes          tide preferences (only relevant for SEASCAPE; empty set = not coastal)
 * @param regionId           optional region ID to associate with the location
 * @param bortleClass        auto-detected Bortle class (nullable)
 * @param skyBrightnessSqm   auto-detected sky brightness in mag/arcsec² (nullable)
 * @param elevationMetres    auto-detected elevation in metres (nullable)
 * @param gridLat            Open-Meteo snapped grid latitude (nullable)
 * @param gridLng            Open-Meteo snapped grid longitude (nullable)
 * @param overlooksWater     manual toggle — location overlooks a body of water
 * @param coastalTidal       manual toggle — location is coastal and tidally influenced
 */
public record AddLocationRequest(
        String name,
        double lat,
        double lon,
        Set<SolarEventType> solarEventTypes,
        LocationType locationType,
        Set<TideType> tideTypes,
        Long regionId,
        Integer bortleClass,
        Double skyBrightnessSqm,
        Integer elevationMetres,
        Double gridLat,
        Double gridLng,
        Boolean overlooksWater,
        Boolean coastalTidal
) {
    /** Compact canonical constructor — defensive copy of mutable sets. */
    public AddLocationRequest {
        solarEventTypes = solarEventTypes == null ? Set.of() : Set.copyOf(solarEventTypes);
        tideTypes = tideTypes == null ? Set.of() : Set.copyOf(tideTypes);
    }
}
