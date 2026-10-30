package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.model.LocationDto;
import com.gregochr.goldenhour.model.LocationDtoMapper;
import com.gregochr.goldenhour.model.LocationEnrichmentResult;
import com.gregochr.goldenhour.model.UpdateLocationRequest;
import com.gregochr.goldenhour.model.ValleyFloorCandidates;
import com.gregochr.goldenhour.model.ValleyFloorCell;
import com.gregochr.goldenhour.service.LocationEnrichmentService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.SubRegionMembershipService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing forecast locations.
 *
 * <p>Locations are persisted in the database and managed exclusively via this API.
 */
@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationService locationService;
    private final LocationEnrichmentService locationEnrichmentService;
    private final LocationDtoMapper locationDtoMapper;
    private final SubRegionMembershipService subRegionMembership;

    /**
     * Constructs a {@code LocationController}.
     *
     * @param locationService           the service managing persisted locations
     * @param locationEnrichmentService the service for enriching location metadata
     * @param locationDtoMapper         maps persisted locations to their API projection
     * @param subRegionMembership       reads each location's sub-region for the roster, in one query
     */
    public LocationController(LocationService locationService,
            LocationEnrichmentService locationEnrichmentService,
            LocationDtoMapper locationDtoMapper,
            SubRegionMembershipService subRegionMembership) {
        this.locationService = locationService;
        this.locationEnrichmentService = locationEnrichmentService;
        this.locationDtoMapper = locationDtoMapper;
        this.subRegionMembership = subRegionMembership;
    }

    /**
     * Returns all persisted locations ordered alphabetically by name, each labelled with its sub-region
     * ({@code subRegionId} / {@code subRegionName}, null when it has none). Role-free facts, so the body
     * stays ETag-safe.
     *
     * @return list of location DTOs
     */
    @GetMapping
    public List<LocationDto> getLocations() {
        return locationDtoMapper.toDtoList(locationService.findAll(),
                subRegionMembership.subRegionsByLocation());
    }

    /**
     * Adds a new location to the persisted set.
     *
     * <p>ADMIN-only, like every other mutation on this controller. Creation is emphatically not a
     * per-user action: {@code LocationEntity.enabled} defaults to {@code true}, so a new row joins
     * the global forecast roster immediately, and a coastal one makes {@code LocationService.add}
     * call {@code tideService.fetchAndStoreTideExtremes} — a billable WorldTides request — before
     * it returns. Until 2026-08-26 this mapping carried no {@code @PreAuthorize} while its five
     * siblings all did, so {@code SecurityConfig}'s {@code /api/**} → {@code .authenticated()}
     * fallback let any LITE account expand the roster and spend on tides, one uniquely named
     * location at a time.
     *
     * @param request the location name, coordinates, and metadata
     * @return the saved location entity
     * @throws IllegalArgumentException if the name is blank, lat/lon are out of range,
     *                                  or a location with the same name already exists (HTTP 400)
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public LocationEntity addLocation(@RequestBody AddLocationRequest request) {
        return locationService.add(request);
    }

    /**
     * Updates metadata for an existing location.
     *
     * @param id      the location primary key
     * @param request the updated metadata (solarEventTypes, locationType, tideType)
     * @return the updated location entity
     * @throws java.util.NoSuchElementException if no location with that ID exists (HTTP 404)
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public LocationEntity updateLocation(@PathVariable Long id,
            @RequestBody UpdateLocationRequest request) {
        return locationService.update(id, request);
    }

    /**
     * Toggles the enabled state of a location.
     *
     * @param id   the location primary key
     * @param body map containing {@code enabled} boolean
     * @return the updated location entity
     * @throws java.util.NoSuchElementException if no location with that ID exists (HTTP 404)
     */
    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public LocationEntity setLocationEnabled(@PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", true);
        return locationService.setEnabled(id, enabled);
    }

    /**
     * Resets the consecutive failure counter and disabled reason for a location.
     *
     * @param name the location name to reset (as query parameter)
     * @return the updated location entity
     * @throws java.util.NoSuchElementException if no location with that name exists (HTTP 404)
     */
    @PutMapping("/reset-failures")
    @PreAuthorize("hasRole('ADMIN')")
    public LocationEntity resetLocationFailures(@RequestParam String name) {
        return locationService.resetFailures(name);
    }

    /**
     * Returns a summary of Open-Meteo grid cell groupings for enabled locations.
     *
     * @return grid cell statistics including total locations, distinct cells, and largest group
     */
    @GetMapping("/grid-cells")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> getGridCellSummary() {
        return locationService.getGridCellSummary();
    }

    /**
     * Enriches a location with auto-detected metadata (bortle, SQM, elevation, grid cell).
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return enrichment result with nullable fields for any failed API source
     */
    @GetMapping("/enrich")
    @PreAuthorize("hasRole('ADMIN')")
    public LocationEnrichmentResult enrichLocation(@RequestParam double lat,
            @RequestParam double lon) {
        return locationEnrichmentService.enrich(lat, lon);
    }

    /**
     * Suggests valley-floor points below a location: the lowest sampled point in each compass sector within
     * about 5 km, kept only when at least 30 m below the location's own DEM sample.
     *
     * @param lat latitude in decimal degrees (the editor's edited value)
     * @param lon longitude in decimal degrees
     * @return the site's DEM height and the qualifying candidates, lowest first (possibly empty)
     * @throws IllegalArgumentException if the coordinates are out of range (HTTP 400)
     * @throws com.gregochr.goldenhour.exception.UpstreamLookupException if the elevation lookup fails (HTTP 502)
     */
    @GetMapping("/valley-floor-candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public ValleyFloorCandidates valleyFloorCandidates(@RequestParam double lat, @RequestParam double lon) {
        return locationEnrichmentService.valleyFloorCandidates(lat, lon);
    }

    /**
     * Shows the Open-Meteo model cell a chosen valley-floor point resolves to when requested at that
     * height. Read-only; nothing is stored.
     *
     * @param lat       the floor point's latitude
     * @param lon       the floor point's longitude
     * @param elevation the floor height in metres
     * @return the snapped cell and the surface height the response refers to
     * @throws IllegalArgumentException if an input is out of range (HTTP 400)
     * @throws com.gregochr.goldenhour.exception.UpstreamLookupException if the lookup fails (HTTP 502)
     */
    @GetMapping("/valley-floor-cell")
    @PreAuthorize("hasRole('ADMIN')")
    public ValleyFloorCell valleyFloorCell(@RequestParam double lat, @RequestParam double lon,
            @RequestParam double elevation) {
        return locationEnrichmentService.valleyFloorCell(lat, lon, elevation);
    }

}
