package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.service.LocationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for managing forecast locations.
 *
 * <p>Locations are persisted in the database and seeded from {@code application.yml}
 * on startup. New locations can be added at runtime and persist across restarts.
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
     * @param request the location name, latitude, and longitude
     * @return the saved location entity
     * @throws IllegalArgumentException if the name is blank, lat/lon are out of range,
     *                                  or a location with the same name already exists (HTTP 400)
     */
    @PostMapping
    public LocationEntity addLocation(@RequestBody AddLocationRequest request) {
        return locationService.add(request.name(), request.lat(), request.lon());
    }

    /**
     * Resets the consecutive failure counter and disabled reason for a location.
     *
     * <p>Allows ADMIN users to re-enable locations that have been auto-disabled
     * after 3 consecutive forecast failures.
     *
     * @param name the location name to reset (URL-encoded)
     * @return the updated location entity
     * @throws java.util.NoSuchElementException if no location with that name exists (HTTP 404)
     */
    @PutMapping("/{name}/reset-failures")
    public LocationEntity resetLocationFailures(@PathVariable String name) {
        return locationService.resetFailures(name);
    }
}
