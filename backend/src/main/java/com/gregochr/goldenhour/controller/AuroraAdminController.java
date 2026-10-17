package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.AuroraSimulationRequest;
import com.gregochr.goldenhour.model.AuroraSimulationResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraPollOutcome;
import com.gregochr.goldenhour.service.aurora.AuroraPollingJob;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Admin-only REST controller for aurora feature management.
 *
 * <p>Provides endpoints for triggering the one-time Bortle enrichment job, running an aurora
 * polling cycle on demand, and resetting or simulating the aurora state machine during testing.
 */
@RestController
@RequestMapping("/api/aurora/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AuroraAdminController {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraAdminController.class);

    private final BortleEnrichmentService enrichmentService;
    private final AuroraProperties properties;
    private final AuroraStateCache stateCache;
    private final AuroraPollingJob pollingJob;
    private final JobRunService jobRunService;
    private final Executor forecastExecutor;
    private final LocationRepository locationRepository;

    /**
     * Constructs the admin controller with all aurora management dependencies.
     *
     * @param enrichmentService  enrichment service for populating Bortle classes
     * @param properties         aurora configuration (provides the API key and thresholds)
     * @param stateCache         aurora state machine
     * @param pollingJob         the aurora polling job, whose cycle the manual run executes
     * @param jobRunService      job run service for tracking enrichment runs
     * @param forecastExecutor   executor for running enrichment asynchronously
     * @param locationRepository location data access for counting eligible locations
     */
    public AuroraAdminController(BortleEnrichmentService enrichmentService,
            AuroraProperties properties,
            AuroraStateCache stateCache,
            AuroraPollingJob pollingJob,
            JobRunService jobRunService,
            Executor forecastExecutor,
            LocationRepository locationRepository) {
        this.enrichmentService = enrichmentService;
        this.properties = properties;
        this.stateCache = stateCache;
        this.pollingJob = pollingJob;
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
     * Runs one aurora polling cycle now and waits for it, on the request thread: the same cycle the
     * {@code aurora_polling} schedule runs, through {@link AuroraPollingJob#runCycleIfIdle()}. In
     * daylight that is the forecast for tonight; after dark, the higher of the forecast for the rest
     * of tonight and the conditions now. It scores eligible locations if the alert level warrants it.
     * One daylight run is different: the first after a night that ended while an alert was held for
     * its reading makes the CLEAR that night deferred, reading nothing, and reports the night's held
     * level and trigger with {@code dark: false}.
     *
     * <p>It used to call the orchestrator's real-time path directly, with that path's own six-hour
     * horizon and no guard. So a manual run could CLEAR a heads-up that the next scheduled poll would
     * NOTIFY again, and pay for again, and it could run at the same moment as a scheduled cycle. In
     * daylight it no longer reaches the real-time path at all, so apart from that one deferred CLEAR
     * it cannot clear a stale alert: {@code POST /reset} does that.
     *
     * <p>Refused with 409 while a cycle is already running from any route. A cycle that has to score
     * waits for triage and a Claude call, retries included. A proxy that times the request out does
     * not stop the cycle, and a retry answers 409 until it finishes. Unlike the schedule, it runs
     * whether or not {@code aurora.enabled} is set, as it always has.
     *
     * @return 200 with the level the cycle derived, the state machine's action, the signal the level
     *         came from, and whether a night poll held the active alert rather than end it on an
     *         estimate ({@code held}: action NONE, level below MODERATE, alert still standing), or 409
     *         Conflict if a cycle is already running. {@code level} and {@code trigger} are null only
     *         if reading NOAA threw; the client fails open, so an outage reads as the last data
     *         cached, or as quiet on a cold start
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> triggerRun() {
        Optional<AuroraPollOutcome> ran = pollingJob.runCycleIfIdle();
        if (ran.isEmpty()) {
            LOG.warn("Admin aurora cycle refused — a cycle is already running");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "An aurora cycle is already running"));
        }
        AuroraPollOutcome outcome = ran.get();
        LOG.info("Admin triggered aurora cycle — dark={} level={} action={} trigger={} held={}",
                outcome.dark(), outcome.level(), outcome.action(), outcome.trigger(), outcome.held());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "Aurora cycle complete");
        body.put("dark", outcome.dark());
        body.put("level", outcome.level() == null ? null : outcome.level().name());
        body.put("action", outcome.action().name());
        body.put("trigger", outcome.trigger() == null ? null : outcome.trigger().name());
        body.put("held", outcome.held());
        return ResponseEntity.ok(body);
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
     * <p>While the {@code aurora_polling} job runs, the next real reading the state machine
     * evaluates ends the simulation: a quiet one clears it, an alert replaces it as a new, real
     * alert. After dark that is the next poll — within five minutes by default, so a forecast run
     * meant to use the simulated values has to be started before then. By day it is a forecast that
     * tonight reaches the alert threshold. The Clear endpoint ends it at once.
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
     * Clears the active aurora simulation, resetting the state machine to IDLE — and does nothing
     * if no simulation is running.
     *
     * <p>Unlike {@link #resetStateCache()}, this touches only a simulation. A real reading can end
     * one before the admin's screen shows it, and if that reading was an alert the machine now holds
     * a real one, which a Clear sent from the stale screen must not wipe for every Pro user. So when
     * nothing is simulated it answers 200 with a status saying nothing was cleared, rather than an
     * error, since the state the admin asked for — no simulation — already holds.
     *
     * @return confirmation message, saying whether a simulation was cleared
     */
    @PostMapping("/simulate/clear")
    public ResponseEntity<Map<String, String>> clearSimulation() {
        if (!stateCache.endSimulation()) {
            LOG.info("Admin asked to clear an aurora simulation, but none is running — nothing cleared");
            return ResponseEntity.ok(Map.of("status", "No aurora simulation was running — nothing cleared"));
        }
        LOG.info("Admin cleared aurora simulation — state machine reset to IDLE");
        return ResponseEntity.ok(Map.of("status", "Aurora simulation cleared"));
    }
}
