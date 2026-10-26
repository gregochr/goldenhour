package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.SubRegionPicksRequest;
import com.gregochr.goldenhour.model.SubRegionState;
import com.gregochr.goldenhour.service.UserSubRegionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin tools for one reader's sub-region picks (sub-regions-plan §4.3, S2): see them, set them, lift the
 * lock. ADMIN only, like the rest of {@code /api/users}.
 */
@RestController
@RequestMapping("/api/users/{id}/sub-regions")
@PreAuthorize("hasRole('ADMIN')")
public class UserSubRegionAdminController {

    private final UserSubRegionService subRegionService;

    /**
     * Constructs the controller.
     *
     * @param subRegionService the picks service
     */
    public UserSubRegionAdminController(UserSubRegionService subRegionService) {
        this.subRegionService = subRegionService;
    }

    /**
     * A reader's picks and standing (no options). 404 if there is no such user.
     *
     * @param id the user's primary key
     * @return the state
     */
    @GetMapping
    public SubRegionState getSubRegions(@PathVariable Long id) {
        return subRegionService.getStateForAdmin(id);
    }

    /**
     * Sets a reader's picks, bypassing the change rule and clearing their lock. 400 for an id that is not a
     * sub-region, a duplicate, or more than the limit; an empty list clears the picks; 404 if there is no such
     * user.
     *
     * @param id      the user's primary key
     * @param request the sub-region ids to hold
     * @return the new state
     */
    @PutMapping
    public SubRegionState putSubRegions(@PathVariable Long id, @RequestBody SubRegionPicksRequest request) {
        return subRegionService.adminReplacePicks(id, request.areaIds());
    }

    /**
     * Lifts a reader's lock, so their next change is allowed. 404 if there is no such user.
     *
     * @param id the user's primary key
     * @return 204
     */
    @DeleteMapping("/lock")
    public ResponseEntity<Void> clearLock(@PathVariable Long id) {
        subRegionService.clearLock(id);
        return ResponseEntity.noContent().build();
    }
}
