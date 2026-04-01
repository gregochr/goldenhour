package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.model.UpdateLocationRequest;
import com.gregochr.goldenhour.service.LocationService;
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

    /**
     * Constructs a {@code LocationController}.
     *
     * @param locationService the service managing persisted locations
     */
    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    /**
     * Returns all persisted locations ordered alphabetically by name.
     *
     * @return list of location entities
     */
    @GetMapping
    public List<LocationEntity> getLocations() {
        return locationService.findAll();
    }

    /**
     * Adds a new location to the persisted set.
     *
     * @param request the location name, coordinates, and metadata
     * @return the saved location entity
     * @throws IllegalArgumentException if the name is blank, lat/lon are out of range,
     *                                  or a location with the same name already exists (HTTP 400)
     */
    @PostMapping
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

}
