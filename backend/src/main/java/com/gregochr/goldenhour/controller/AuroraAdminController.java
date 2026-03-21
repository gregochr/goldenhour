package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.BortleEnrichmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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

    /**
     * Constructs the admin controller with enrichment, config, and state cache dependencies.
     *
     * @param enrichmentService enrichment service for populating Bortle classes
     * @param properties        aurora configuration (provides the API key)
     * @param stateCache        aurora state machine
     */
    public AuroraAdminController(BortleEnrichmentService enrichmentService,
            AuroraProperties properties,
            AuroraStateCache stateCache) {
        this.enrichmentService = enrichmentService;
        this.properties = properties;
        this.stateCache = stateCache;
    }

    /**
     * Triggers the Bortle enrichment job, populating the {@code bortle_class} column for
     * all locations that do not yet have a value.
     *
     * <p>Runs synchronously in the request thread. With ~200 locations and a 500 ms
     * inter-call delay, expect ~2 minutes. Safe to re-run — only unenriched locations
     * are processed.
     *
     * @return a summary of the enrichment results
     */
    @PostMapping("/enrich-bortle")
    public ResponseEntity<Map<String, Object>> enrichBortle() {
        String apiKey = properties.getLightPollutionApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "aurora.light-pollution-api-key is not configured"));
        }

        LOG.info("Admin triggered Bortle enrichment job");
        BortleEnrichmentService.EnrichmentResult result = enrichmentService.enrichAll(apiKey);

        return ResponseEntity.ok(Map.of(
                "enriched", result.enriched(),
                "failed", result.failed().size(),
                "failedLocations", result.failed()
        ));
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
