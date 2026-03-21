package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.BortleEnrichmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Admin-only REST controller for aurora feature management.
 *
 * <p>Provides endpoints for triggering the one-time Bortle enrichment job and
 * resetting the aurora state machine during testing.
 */
@RestController
@RequestMapping("/api/aurora/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AuroraAdminController {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraAdminController.class);

    private final BortleEnrichmentService enrichmentService;
    private final AuroraProperties properties;
    private final AuroraStateCache stateCache;
    private final JobRunService jobRunService;
    private final Executor forecastExecutor;

    /**
     * Constructs the admin controller with enrichment, config, state cache, and job run dependencies.
     *
     * @param enrichmentService enrichment service for populating Bortle classes
     * @param properties        aurora configuration (provides the API key)
     * @param stateCache        aurora state machine
     * @param jobRunService     job run service for tracking enrichment runs
     * @param forecastExecutor  executor for running enrichment asynchronously
     */
    public AuroraAdminController(BortleEnrichmentService enrichmentService,
            AuroraProperties properties,
            AuroraStateCache stateCache,
            JobRunService jobRunService,
            Executor forecastExecutor) {
        this.enrichmentService = enrichmentService;
        this.properties = properties;
        this.stateCache = stateCache;
        this.jobRunService = jobRunService;
        this.forecastExecutor = forecastExecutor;
    }

    /**
     * Triggers the Bortle enrichment job asynchronously, populating the {@code bortle_class}
     * column for all locations that do not yet have a value.
     *
     * <p>Returns 202 Accepted immediately; the enrichment runs in the background.
     * With ~200 locations and a 500 ms inter-call delay, expect ~2 minutes to complete.
     * Safe to re-run — only unenriched locations are processed.
     *
     * @return 202 Accepted with job run ID, or 400 if the API key is not configured
     */
    @PostMapping("/enrich-bortle")
    public ResponseEntity<Map<String, Object>> enrichBortle() {
        String apiKey = properties.getLightPollutionApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "aurora.light-pollution-api-key is not configured"));
        }

        JobRunEntity jobRun = jobRunService.startRun(RunType.LIGHT_POLLUTION, true, null, null);
        LOG.info("Admin triggered Bortle enrichment job (jobRunId={})", jobRun.getId());
        CompletableFuture.runAsync(() -> enrichmentService.enrichAll(apiKey, jobRun), forecastExecutor);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "Light pollution enrichment started",
                        "runType", "LIGHT_POLLUTION",
                        "jobRunId", jobRun.getId()));
    }

    /**
     * Resets the aurora state machine to IDLE and clears all cached scores.
     *
     * <p>Intended for testing and manual recovery after anomalous states.
     *
     * @return confirmation message
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetStateCache() {
        stateCache.reset();
        LOG.info("Admin reset aurora state cache to IDLE");
        return ResponseEntity.ok(Map.of("status", "Aurora state machine reset to IDLE"));
    }
}
