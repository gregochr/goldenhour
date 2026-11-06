package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.UnroutableLocationDto;
import com.gregochr.goldenhour.service.UnroutableLocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only read of the places OpenRouteService has no route to (reach-scoped-card-plan R1b). Its own path under
 * {@code /api/admin}, which {@code HttpCachingConfig} never revalidates, so the body is served {@code no-store}.
 */
@RestController
@RequestMapping("/api/admin/drive-times")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDriveTimesController {

    private final UnroutableLocationService unroutableLocationService;

    /**
     * Lists every enabled location that at least one measured home or region base reads as UNROUTABLE.
     *
     * @return 200 with the locations, in name order (an empty array when none); 403 for a non-admin
     */
    @GetMapping("/unroutable")
    public ResponseEntity<List<UnroutableLocationDto>> unroutable() {
        return ResponseEntity.ok(unroutableLocationService.findUnroutable());
    }
}
