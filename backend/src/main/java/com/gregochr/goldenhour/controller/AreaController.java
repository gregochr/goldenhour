package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.AreaDto;
import com.gregochr.goldenhour.model.AreaLocationsRequest;
import com.gregochr.goldenhour.model.AreaRequest;
import com.gregochr.goldenhour.service.AreaService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin endpoints for the named areas Ask understands ("Hadrian's Wall").
 *
 * <p>Every mapping is ADMIN-only. The service owns every rule and write; nothing here but routing.
 * {@code HttpCachingConfig} is an explicit allowlist, so these paths are never cached.
 */
@RestController
@RequestMapping("/api/areas")
public class AreaController {

    private final AreaService areaService;

    /**
     * Constructs the controller.
     *
     * @param areaService the service owning the areas
     */
    public AreaController(AreaService areaService) {
        this.areaService = areaService;
    }

    /**
     * Lists every named area.
     *
     * @return the areas, by name
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<AreaDto> getAreas() {
        return areaService.findAll();
    }

    /**
     * Creates a named area with no members.
     *
     * @param request the name and aliases
     * @return the new area (HTTP 201)
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AreaDto createArea(@RequestBody AreaRequest request) {
        return areaService.create(request);
    }

    /**
     * Renames an area and replaces its aliases; members are untouched.
     *
     * @param id      the area's primary key
     * @param request the name and aliases
     * @return the updated area
     * @throws java.util.NoSuchElementException if no such area exists (HTTP 404)
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AreaDto updateArea(@PathVariable Long id, @RequestBody AreaRequest request) {
        return areaService.update(id, request);
    }

    /**
     * Replaces an area's membership.
     *
     * @param id      the area's primary key
     * @param request the member location ids
     * @return the updated area
     * @throws java.util.NoSuchElementException if no such area exists (HTTP 404)
     */
    @PutMapping("/{id}/locations")
    @PreAuthorize("hasRole('ADMIN')")
    public AreaDto setAreaLocations(@PathVariable Long id, @RequestBody AreaLocationsRequest request) {
        return areaService.setLocations(id, request);
    }

    /**
     * Deletes an area.
     *
     * @param id the area's primary key
     * @throws java.util.NoSuchElementException if no such area exists (HTTP 404)
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteArea(@PathVariable Long id) {
        areaService.delete(id);
    }
}
