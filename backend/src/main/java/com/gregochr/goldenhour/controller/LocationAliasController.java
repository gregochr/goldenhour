package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.LocationAliasesRequest;
import com.gregochr.goldenhour.service.LocationAliasService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for the other names of a location ("The Heugh"). Kept apart from {@link LocationController}
 * and never routed through {@code PUT /api/locations/{id}}, so saving a location cannot rewrite its aliases.
 */
@RestController
@RequestMapping("/api/locations")
public class LocationAliasController {

    private final LocationAliasService locationAliasService;

    /**
     * Constructs the controller.
     *
     * @param locationAliasService the service owning location aliases
     */
    public LocationAliasController(LocationAliasService locationAliasService) {
        this.locationAliasService = locationAliasService;
    }

    /**
     * Returns every location's aliases.
     *
     * @return location id to aliases; locations with none are absent
     */
    @GetMapping("/aliases")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<Long, List<String>> getAliases() {
        return locationAliasService.findAll();
    }

    /**
     * Replaces one location's aliases.
     *
     * @param id      the location's primary key
     * @param request the aliases
     * @return the saved aliases
     * @throws java.util.NoSuchElementException if no such location exists (HTTP 404)
     */
    @PutMapping("/{id}/aliases")
    @PreAuthorize("hasRole('ADMIN')")
    public List<String> setAliases(@PathVariable Long id, @RequestBody LocationAliasesRequest request) {
        return locationAliasService.replace(id, request.aliases());
    }
}
