package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.NlcSightingResponse;
import com.gregochr.goldenhour.service.NlcSightingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the noctilucent-cloud (NLC) sighting signal.
 *
 * <p>Gated to {@code ADMIN} and {@code PRO_USER} roles, mirroring aurora: free-tier (LITE) users
 * receive {@code 403}, which the client maps to "no banner". The signal is a reactive community
 * report, never a forecast — see {@link NlcSightingService}.
 */
@RestController
@RequestMapping("/api/nlc")
@PreAuthorize("hasAnyRole('ADMIN', 'PRO_USER')")
public class NlcController {

    private final NlcSightingService sightingService;

    /**
     * Constructs the controller.
     *
     * @param sightingService the NLC sighting gate/service
     */
    public NlcController(NlcSightingService sightingService) {
        this.sightingService = sightingService;
    }

    /**
     * Returns the current NLC sighting signal, or an inactive response when there is nothing to show.
     *
     * @return the sighting signal (200 OK); {@code {"active": false}} when no fresh sighting applies
     */
    @GetMapping("/sighting")
    public ResponseEntity<NlcSightingResponse> getSighting() {
        return ResponseEntity.ok(sightingService.currentSighting());
    }
}
