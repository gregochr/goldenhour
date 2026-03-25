package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.service.BriefingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the daily briefing — a zero-Claude-cost pre-flight check
 * that reports weather and tide conditions across all enabled colour locations.
 *
 * <p>Serves the cached briefing result. The cache is populated by the scheduled
 * briefing refresh job every 2 hours.
 */
@RestController
@RequestMapping("/api/briefing")
public class BriefingController {

    private final BriefingService briefingService;

    /**
     * Constructs a {@code BriefingController}.
     *
     * @param briefingService the service that manages the briefing cache
     */
    public BriefingController(BriefingService briefingService) {
        this.briefingService = briefingService;
    }

    /**
     * Returns the most recent daily briefing, or 204 No Content if no briefing
     * has been generated yet.
     *
     * @return the cached briefing response
     */
    @GetMapping
    public ResponseEntity<DailyBriefingResponse> getBriefing() {
        DailyBriefingResponse briefing = briefingService.getCachedBriefing();
        if (briefing == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(briefing);
    }
}
