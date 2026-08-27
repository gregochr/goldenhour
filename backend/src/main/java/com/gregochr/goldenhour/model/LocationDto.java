package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideType;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * API projection of a persisted location, served by {@code GET /api/locations}.
 *
 * <p>Field-for-field mirror of what {@code LocationEntity} itself used to serialise as, including
 * the computed {@code woodlandOnly} property — this DTO is a pure decoupling of the wire format
 * from the JPA entity, not a payload change. Adding a persistence column no longer silently adds an
 * API field; a field must be added here deliberately.
 *
 * @param id                         database primary key
 * @param name                       human-readable location name
 * @param lat                        latitude in decimal degrees
 * @param lon                        longitude in decimal degrees
 * @param solarEventType             which solar events are worth photographing here
 * @param tideType                   the photographer's tide preferences for this location
 * @param locationType               photography type tags (e.g. SEASCAPE, LANDSCAPE)
 * @param region                     the geographic region this location belongs to, or {@code null}
 * @param enabled                    whether this location is enabled for forecast runs
 * @param createdAt                  UTC timestamp when this location was created
 * @param consecutiveFailures        number of consecutive forecast failures
 * @param lastFailureAt              UTC timestamp of the most recent forecast failure, or {@code null}
 * @param disabledReason             reason this location was disabled, or {@code null}
 * @param bortleClass                Bortle dark-sky class (1 = darkest, 8 = city sky), or {@code null}
 * @param skyBrightnessSqm           Sky Quality Meter value, or {@code null}
 * @param shoreNormalBearingDegrees  compass bearing of the outward shore-normal, or {@code null}
 * @param effectiveFetchMetres       open-water fetch distance for dominant storm winds, or {@code null}
 * @param avgShelfDepthMetres        representative water depth over the fetch, or {@code null}
 * @param coastalTidal               whether this location has meaningful tidal surge exposure
 * @param elevationMetres            elevation above sea level in metres, or {@code null}
 * @param overlooksWater             whether this location overlooks water from elevation
 * @param gridLat                    snapped Open-Meteo grid latitude, or {@code null}
 * @param gridLng                    snapped Open-Meteo grid longitude, or {@code null}
 * @param bluebellExposure           bluebell exposure type, or {@code null}
 * @param woodlandOnly               true when this location's only photographic subject is under canopy
 */
public record LocationDto(
        Long id,
        String name,
        double lat,
        double lon,
        Set<SolarEventType> solarEventType,
        Set<TideType> tideType,
        Set<LocationType> locationType,
        LocationRegionDto region,
        boolean enabled,
        LocalDateTime createdAt,
        Integer consecutiveFailures,
        LocalDateTime lastFailureAt,
        String disabledReason,
        Integer bortleClass,
        Double skyBrightnessSqm,
        Double shoreNormalBearingDegrees,
        Double effectiveFetchMetres,
        Double avgShelfDepthMetres,
        boolean coastalTidal,
        Integer elevationMetres,
        boolean overlooksWater,
        Double gridLat,
        Double gridLng,
        BluebellExposure bluebellExposure,
        boolean woodlandOnly) {
}
