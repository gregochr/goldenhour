package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.AuroraNightsResponse;
import com.gregochr.goldenhour.service.aurora.AuroraNightsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The role-free "which aurora nights exist" read.
 *
 * <p>Deliberately its own controller with no role {@code @PreAuthorize}: {@link AuroraController}'s
 * class-level PRO/ADMIN gate would otherwise reach it. Any authenticated role may read it
 * ({@code /api/**} is authenticated in {@code SecurityConfig}); it reveals only dates, never a rating,
 * location or summary, and the aurora forecast itself stays Pro and server-enforced.
 */
@RestController
@RequestMapping("/api/aurora/nights")
public class AuroraNightsController {

    private final AuroraNightsService nightsService;

    /**
     * Constructs the controller.
     *
     * @param nightsService computes the offered nights
     */
    public AuroraNightsController(AuroraNightsService nightsService) {
        this.nightsService = nightsService;
    }

    /**
     * Returns the aurora nights a Pro reader would be offered a row for, and when the current one ends.
     *
     * @return the nights, ascending and distinct, with {@code currentNightEndsAt}
     */
    @GetMapping
    public ResponseEntity<AuroraNightsResponse> getNights() {
        return ResponseEntity.ok(nightsService.offeredNights());
    }
}
