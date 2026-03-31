package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.BriefingModelTestResultEntity;
import com.gregochr.goldenhour.entity.BriefingModelTestRunEntity;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.service.BriefingModelTestService;
import com.gregochr.goldenhour.service.BriefingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the daily briefing — a zero-Claude-cost pre-flight check
 * that reports weather and tide conditions across all enabled colour locations.
 *
 * <p>Serves the cached briefing result. The cache is populated by the scheduled
 * briefing refresh job every 2 hours. Also provides admin endpoints for briefing
 * model comparison testing.
 */
@RestController
@RequestMapping("/api/briefing")
public class BriefingController {

    private final BriefingService briefingService;
    private final BriefingModelTestService briefingModelTestService;

    /**
     * Constructs a {@code BriefingController}.
     *
     * @param briefingService          the service that manages the briefing cache
     * @param briefingModelTestService the service that runs briefing model comparisons
     */
    public BriefingController(BriefingService briefingService,
            BriefingModelTestService briefingModelTestService) {
        this.briefingService = briefingService;
        this.briefingModelTestService = briefingModelTestService;
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

    /**
     * Triggers an immediate briefing refresh. Admin-only.
     *
     * @return accepted status message
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> runBriefing() {
        briefingService.refreshBriefing();
        return ResponseEntity.ok(Map.of("status", "Briefing refresh complete."));
    }

    /**
     * Runs a briefing model comparison test — calls Haiku, Sonnet, and Opus with the
     * same rollup data from the current cached briefing. Admin-only.
     *
     * @return the completed test run entity with summary stats
     */
    @PostMapping("/compare-models")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BriefingModelTestRunEntity> runComparison() {
        return ResponseEntity.ok(briefingModelTestService.runComparison());
    }

    /**
     * Returns the 20 most recent briefing model test runs. Admin-only.
     *
     * @return recent test runs, newest first
     */
    @GetMapping("/compare-models/runs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<BriefingModelTestRunEntity>> getComparisonRuns() {
        return ResponseEntity.ok(briefingModelTestService.getRecentRuns());
    }

    /**
     * Returns the results for a specific briefing model test run. Admin-only.
     *
     * @param runId the test run ID
     * @return results ordered by evaluation model
     */
    @GetMapping("/compare-models/results")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<BriefingModelTestResultEntity>> getComparisonResults(
            @RequestParam Long runId) {
        return ResponseEntity.ok(briefingModelTestService.getResults(runId));
    }
}
