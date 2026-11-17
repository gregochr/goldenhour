package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.WarningsResponse;
import com.gregochr.goldenhour.service.warnings.WarningService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Met Office weather warnings in force, for every signed-in role. Not an admin path, so a rewound admin sees the
 * warnings as they stood at the rewound instant. Simulated warnings never appear here, for anyone.
 */
@RestController
@RequestMapping("/api/warnings")
public class WarningsController {

    private final WarningService warningService;

    /**
     * Creates the controller.
     *
     * @param warningService assembles the response
     */
    public WarningsController(WarningService warningService) {
        this.warningService = warningService;
    }

    /**
     * The warnings in force that cover at least one enabled location.
     *
     * @return the warnings with {@code lastCheckedAt} and {@code stale}
     */
    @GetMapping
    public WarningsResponse getWarnings() {
        return warningService.served();
    }
}
