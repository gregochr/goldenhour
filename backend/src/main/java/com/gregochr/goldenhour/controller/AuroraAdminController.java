package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.AuroraSimulationRequest;
import com.gregochr.goldenhour.model.AuroraSimulationResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.BortleEnrichmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final AuroraOrchestrator orchestrator;
    private final JobRunService jobRunService;
    private final Executor forecastExecutor;
    private final LocationRepository locationRepository;

    /**
     * Constructs the admin controller with all aurora management dependencies.
     *
     * @param enrichmentService  enrichment service for populating Bortle classes
     * @param properties         aurora configuration (provides the API key and thresholds)
     * @param stateCache         aurora state machine
     * @param orchestrator       aurora orchestrator for on-demand NOAA poll cycles
     * @param jobRunService      job run service for tracking enrichment runs
     * @param forecastExecutor   executor for running enrichment asynchronously
     * @param locationRepository location data access for counting eligible locations
     */
    public AuroraAdminController(BortleEnrichmentService enrichmentService,
            AuroraProperties properties,
            AuroraStateCache stateCache,
            AuroraOrchestrator orchestrator,
            JobRunService jobRunService,
            Executor forecastExecutor,
            LocationRepository locationRepository) {
        this.enrichmentService = enrichmentService;
        this.properties = properties;
        this.stateCache = stateCache;
        this.orchestrator = orchestrator;
        this.jobRunService = jobRunService;
        this.forecastExecutor = forecastExecutor;
        this.locationRepository = locationRepository;
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
     * Triggers an immediate aurora orchestration cycle, fetching live NOAA SWPC data
     * and scoring eligible locations if the alert level warrants it.
     *
     * @return the state machine action taken
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> triggerRun() {
        AuroraStateCache.Action action = orchestrator.run();
        LOG.info("Admin triggered aurora orchestration cycle — action={}", action);
        return ResponseEntity.ok(Map.of("status", "Aurora cycle complete", "action", action.name()));
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

    /**
     * Activates aurora simulation mode by injecting fake NOAA space weather data into the
     * state machine without triggering any Claude API calls.
     *
     * <p>After calling this endpoint:
     * <ul>
     *   <li>The aurora banner appears in the UI with a "(SIMULATED)" indicator.</li>
     *   <li>The Aurora forecast type becomes available in the Forecast Runs UI.</li>
     *   <li>The Admin can run a manual Aurora Forecast Run to generate Claude-scored
     *       location results using real weather data + the simulated geomagnetic values.</li>
     * </ul>
     *
     * <p>The real NOAA polling job continues independently and will overwrite this simulated
     * state when a real geomagnetic event is detected or simulation is cleared.
     *
     * @param request simulated Kp, OVATION, Bz, and G-scale values
     * @return derived alert level and instructions for next steps
     */
    @PostMapping("/simulate")
    public ResponseEntity<AuroraSimulationResponse> simulateAurora(
            @RequestBody AuroraSimulationRequest request) {
        AlertLevel level = AlertLevel.fromKp(request.kp());
        AuroraStateCache.SimulatedNoaaData simData = new AuroraStateCache.SimulatedNoaaData(
                request.kp(), request.ovationProbability(), request.bzNanoTesla(), request.gScale());
        stateCache.activateSimulation(level, simData);

        int bortleThreshold = (level == AlertLevel.STRONG)
                ? properties.getBortleThreshold().getStrong()
                : properties.getBortleThreshold().getModerate();
        int eligibleLocations = locationRepository
                .findByBortleClassLessThanEqualAndEnabledTrue(bortleThreshold).size();

        String gScaleInfo = request.gScale() != null ? " (" + request.gScale() + ")" : "";
        String message = String.format(
                "Simulation active — Kp %.0f%s. Use Forecast Runs → Aurora to generate scores.",
                request.kp(), gScaleInfo);

        LOG.info("Admin activated aurora simulation — kp={}, level={}, gScale={}",
                request.kp(), level, request.gScale());
        return ResponseEntity.ok(new AuroraSimulationResponse(level, message, eligibleLocations));
    }

    /**
     * Clears the active aurora simulation, resetting the state machine to IDLE.
     *
     * <p>The banner disappears and the aurora UI deactivates. Equivalent to
     * {@link #resetStateCache()} but semantically distinct for simulation lifecycle management.
     *
     * @return confirmation message
     */
    @PostMapping("/simulate/clear")
    public ResponseEntity<Map<String, String>> clearSimulation() {
        stateCache.reset();
        LOG.info("Admin cleared aurora simulation — state machine reset to IDLE");
        return ResponseEntity.ok(Map.of("status", "Aurora simulation cleared"));
    }
}
