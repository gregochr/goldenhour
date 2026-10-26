package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.SubRegionPolicyRequest;
import com.gregochr.goldenhour.model.SubRegionPolicyResponse;
import com.gregochr.goldenhour.service.SubRegionPolicyService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin setting for how many sub-regions a Lite reader may pick (sub-regions-plan D5, S2). ADMIN only;
 * {@code HttpCachingConfig} is an explicit allow-list, so this path is never cached.
 */
@RestController
@RequestMapping("/api/admin/sub-region-policy")
@PreAuthorize("hasRole('ADMIN')")
public class SubRegionPolicyController {

    private final SubRegionPolicyService policyService;

    /**
     * Constructs the controller.
     *
     * @param policyService the policy service
     */
    public SubRegionPolicyController(SubRegionPolicyService policyService) {
        this.policyService = policyService;
    }

    /**
     * The saved limit, the largest limit allowed, and how many Lite readers hold more picks than a limit.
     *
     * @param candidate a limit being considered; the response counts the readers it would leave over their
     *                  limit. Omit it for the saved limit
     * @return the policy
     */
    @GetMapping
    public SubRegionPolicyResponse getPolicy(@RequestParam(required = false) Integer candidate) {
        return policyService.describe(candidate);
    }

    /**
     * Saves a new limit (400 unless it is 1..the number of sub-regions) and answers how many Lite readers now
     * hold more picks than it.
     *
     * @param request the new limit
     * @param auth    the current authentication context
     * @return the saved policy
     */
    @PutMapping
    public SubRegionPolicyResponse putPolicy(@RequestBody SubRegionPolicyRequest request, Authentication auth) {
        return policyService.update(request.liteLimit(), auth.getName());
    }
}
