package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.SimulateWarningRequest;
import com.gregochr.goldenhour.model.WarningDto;
import com.gregochr.goldenhour.model.WarningsAdminStatus;
import com.gregochr.goldenhour.service.warnings.WarningService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only view of the warnings ingest (Operations) and the simulation insert. Served {@code no-store}: it is under
 * {@code /api/admin}, which {@code HttpCachingConfig} never revalidates.
 */
@RestController
@RequestMapping("/api/admin/warnings")
@PreAuthorize("hasRole('ADMIN')")
public class WarningsAdminController {

    private final WarningService warningService;

    /**
     * Creates the controller.
     *
     * @param warningService assembles the status and inserts simulations
     */
    public WarningsAdminController(WarningService warningService) {
        this.warningService = warningService;
    }

    /**
     * Poll status, stored counts, unknown weather types and every warning in force, simulated included.
     *
     * @return the Operations status
     */
    @GetMapping("/status")
    public WarningsAdminStatus status() {
        return warningService.adminStatus();
    }

    /**
     * Inserts one clearly simulated warning (a square around a point); never visible to a non-admin.
     *
     * @param request level, types, headline, duration and centre
     * @return the stored simulated warning
     */
    @PostMapping("/simulate")
    @ResponseStatus(HttpStatus.CREATED)
    public WarningDto simulate(@RequestBody SimulateWarningRequest request) {
        return warningService.simulate(request);
    }
}
