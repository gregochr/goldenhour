package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AddRegionRequest;
import com.gregochr.goldenhour.model.SetRegionBaseRequest;
import com.gregochr.goldenhour.model.UpdateRegionRequest;
import com.gregochr.goldenhour.service.RegionDriveDurationService;
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
    private final RegionDriveDurationService regionDriveDurationService;

    /**
     * Constructs a {@code RegionController}.
     *
     * @param regionService              the service managing persisted regions
     * @param regionDriveDurationService the shared region-base drive-time matrix
     */
    public RegionController(RegionService regionService,
            RegionDriveDurationService regionDriveDurationService) {
        this.regionService = regionService;
        this.regionDriveDurationService = regionDriveDurationService;
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
     * Returns the shared drive-time matrix — every region base to every location, in minutes.
     *
     * <p><b>Bearer, no role gate</b>, by inheritance from {@code SecurityConfig}'s
     * {@code /api/**} to {@code .authenticated()}. Stated rather than left implicit because this
     * project has documented endpoints as ADMIN that the code never enforced: the Plan tab's origin
     * move is ungated for the pilot (plan §9.6), so gating this would break the feature for every
     * non-admin.
     *
     * <p><b>It is user-independent, which is the whole reason it exists.</b> "How far is this from
     * Keswick" is the same answer for every reader, so it may ride the ETag-revalidated shared
     * payload ({@code HttpCachingConfig}); "how far is this from your house" may not, and stays on
     * the never-cached {@code /api/user/settings/reach}. The two must never be merged on the
     * client either — a shared cache holding a per-user figure is the failure the seam exists to
     * prevent.
     *
     * <p>A region with no stored rows is simply absent from the map. That is the ordinary state
     * before the first nightly sweep and immediately after a base moves; the client reads an
     * absent drive time as <em>unknown</em>, never as far.
     *
     * @return region id to (location id to whole minutes)
     */
    @GetMapping("/drive-times")
    public Map<Long, Map<Long, Integer>> getRegionDriveTimes() {
        return regionDriveDurationService.getMatrix();
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
     * Sets or clears a region's base town.
     *
     * <p>Its own endpoint rather than three more fields on {@link UpdateRegionRequest} — see
     * {@link SetRegionBaseRequest} for why a rename must not be able to clear a base.
     *
     * @param id      the region primary key
     * @param request the new base, or all-null to clear it
     * @return the updated region entity
     * @throws java.util.NoSuchElementException if no region with that ID exists (HTTP 404)
     * @throws IllegalArgumentException         if the base is partial or out of range (HTTP 400)
     */
    @PutMapping("/{id}/base")
    @PreAuthorize("hasRole('ADMIN')")
    public RegionEntity setRegionBase(@PathVariable Long id,
            @RequestBody SetRegionBaseRequest request) {
        return regionService.setBase(id, request);
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
