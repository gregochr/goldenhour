package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.DrivePointCandidate;
import com.gregochr.goldenhour.model.UnroutableLocationDto;
import com.gregochr.goldenhour.service.DrivePointLookupService;
import com.gregochr.goldenhour.service.UnroutableLocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only reads behind a location's drive point (reach-scoped-card-plan R1b, R1c): the places OpenRouteService
 * has no route to, and the lookup the location form uses to choose a drive point. Its own path under
 * {@code /api/admin}, which {@code HttpCachingConfig} never revalidates, so every body is served {@code no-store}.
 */
@RestController
@RequestMapping("/api/admin/drive-times")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDriveTimesController {

    private final UnroutableLocationService unroutableLocationService;
    private final DrivePointLookupService drivePointLookupService;

    /**
     * Lists every enabled location that at least one measured home or region base reads as UNROUTABLE.
     *
     * @return 200 with the locations, in name order (an empty array when none); 403 for a non-admin
     */
    @GetMapping("/unroutable")
    public ResponseEntity<List<UnroutableLocationDto>> unroutable() {
        return ResponseEntity.ok(unroutableLocationService.findUnroutable());
    }

    /**
     * Looks up places an admin can choose as a location's drive point, by village name or postcode.
     *
     * @param q   the village name or postcode typed
     * @param lat the location's latitude, for sorting by distance and the 25 km cut
     * @param lon the location's longitude
     * @return 200 with up to ten candidates, nearest first (an empty array when nothing matches); 400 for a blank
     *         query or an unknown postcode; 403 for a non-admin; 502 when postcodes.io fails
     */
    @GetMapping("/places")
    public ResponseEntity<List<DrivePointCandidate>> places(@RequestParam String q, @RequestParam double lat,
            @RequestParam double lon) {
        return ResponseEntity.ok(drivePointLookupService.search(q, lat, lon));
    }
}
