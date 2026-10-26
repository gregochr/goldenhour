package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.AuroraAlertResponse;
import com.gregochr.goldenhour.service.aurora.AuroraAlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The role-free aurora alert read behind the Lite banner.
 *
 * <p>Deliberately its own controller with no role {@code @PreAuthorize}: {@link AuroraController}'s
 * class-level PRO/ADMIN gate would otherwise reach it. Any authenticated role may read it
 * ({@code /api/**} is authenticated in {@code SecurityConfig}); it reveals only the alert level and the
 * night, never a Kp, count, rating or location, and the aurora forecast itself stays Pro.
 */
@RestController
@RequestMapping("/api/aurora/alert")
public class AuroraAlertController {

    private final AuroraAlertService alertService;

    /**
     * Constructs the controller.
     *
     * @param alertService computes the role-free alert
     */
    public AuroraAlertController(AuroraAlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * Returns the aurora alert level, trigger, overcast flag and current night.
     *
     * @return the role-free alert
     */
    @GetMapping
    public ResponseEntity<AuroraAlertResponse> getAlert() {
        return ResponseEntity.ok(alertService.liteAlert());
    }
}
