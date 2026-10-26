package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.SubRegionPicksRequest;
import com.gregochr.goldenhour.model.SubRegionState;
import com.gregochr.goldenhour.service.UserSubRegionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own sub-region picks (sub-regions-plan §4.3, S2).
 *
 * <p>Under {@code /api/user/settings}, which {@code HttpCachingConfig} never filters: the body is the caller's
 * personal picks, and ETag revalidation would persist it to a browser HTTP cache JavaScript cannot evict on
 * logout. Every rule lives in {@link UserSubRegionService}; nothing here but routing.
 */
@RestController
@RequestMapping("/api/user/settings/sub-regions")
@PreAuthorize("isAuthenticated()")
public class UserSubRegionController {

    private final UserSubRegionService subRegionService;

    /**
     * Constructs the controller.
     *
     * @param subRegionService the picks service
     */
    public UserSubRegionController(UserSubRegionService subRegionService) {
        this.subRegionService = subRegionService;
    }

    /**
     * The caller's picks, what they may do next, the options, and (for a reader with a home and no picks) the
     * suggested sub-regions. Any role may read it: Pro and Admin get {@code status: NOT_APPLICABLE}.
     *
     * @param auth the current authentication context
     * @return the state
     */
    @GetMapping
    public SubRegionState getSubRegions(Authentication auth) {
        return subRegionService.getState(auth.getName());
    }

    /**
     * Replaces the caller's picks. Lite readers only; the change rule decides whether it goes ahead.
     *
     * <p>400 for an empty list, more than the limit, a duplicate or an id that is not a sub-region; 403 for
     * Pro and Admin; 409 {@code SUB_REGIONS_LOCKED} with {@code nextChangeOn}; 200 with the new state.
     *
     * @param request the sub-region ids to hold
     * @param auth    the current authentication context
     * @return the new state
     */
    @PutMapping
    @PreAuthorize("hasRole('LITE_USER')")
    public SubRegionState putSubRegions(@RequestBody SubRegionPicksRequest request, Authentication auth) {
        return subRegionService.replacePicks(auth.getName(), request.areaIds());
    }
}
