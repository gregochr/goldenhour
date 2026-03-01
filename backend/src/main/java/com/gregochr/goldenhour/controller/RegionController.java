package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AddRegionRequest;
import com.gregochr.goldenhour.model.UpdateRegionRequest;
import com.gregochr.goldenhour.service.RegionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing geographic regions.
 *
 * <p>Regions group forecast locations by area. All mutation endpoints require ADMIN role.
 */
@RestController
@RequestMapping("/api/regions")
public class RegionController {

    private final RegionService regionService;

    /**
     * Constructs a {@code RegionController}.
     *
     * @param regionService the service managing persisted regions
     */
    public RegionController(RegionService regionService) {
        this.regionService = regionService;
    }

    /**
     * Returns all persisted regions ordered alphabetically by name.
     *
     * @return list of region entities
     */
    @GetMapping
    public List<RegionEntity> getRegions() {
        return regionService.findAll();
    }

    /**
     * Adds a new region.
     *
     * @param request the region name
     * @return the saved region entity
     * @throws IllegalArgumentException if the name is blank or already exists (HTTP 400)
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public RegionEntity addRegion(@RequestBody AddRegionRequest request) {
        return regionService.add(request);
    }

    /**
     * Updates the name of an existing region.
     *
     * @param id      the region primary key
     * @param request the new name
     * @return the updated region entity
     * @throws java.util.NoSuchElementException if no region with that ID exists (HTTP 404)
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public RegionEntity updateRegion(@PathVariable Long id,
            @RequestBody UpdateRegionRequest request) {
        return regionService.update(id, request);
    }

    /**
     * Toggles the enabled state of a region.
     *
     * @param id   the region primary key
     * @param body map containing {@code enabled} boolean
     * @return the updated region entity
     * @throws java.util.NoSuchElementException if no region with that ID exists (HTTP 404)
     */
    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public RegionEntity setRegionEnabled(@PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", true);
        return regionService.setEnabled(id, enabled);
    }
}
