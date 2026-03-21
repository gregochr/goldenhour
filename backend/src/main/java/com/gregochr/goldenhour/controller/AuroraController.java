package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraStatusResponse;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for aurora photography forecasting.
 *
 * <p>All endpoints are gated to {@code ADMIN} and {@code PRO_USER} roles.
 * Free-tier ({@code LITE_USER}) users receive 403 and should not see any
 * aurora UI. The AuroraWatch UK API is non-commercial — resolve licensing
 * with Lancaster University before opening PRO as a paid tier.
 */
@RestController
@RequestMapping("/api/aurora")
@PreAuthorize("hasAnyRole('ADMIN', 'PRO_USER')")
public class AuroraController {

    private final AuroraStateCache stateCache;

    /**
     * Constructs the controller with the aurora state cache.
     *
     * @param stateCache the aurora state machine and score cache
     */
    public AuroraController(AuroraStateCache stateCache) {
        this.stateCache = stateCache;
    }

    /**
     * Returns the current AuroraWatch alert level and state machine state.
     *
     * <p>Returns 403 for {@code LITE_USER}. When no status has been fetched yet
     * (e.g., immediately after startup before the first poll), returns 204 No Content.
     *
     * @return the current aurora status, or 204 if not yet available
     */
    @GetMapping("/status")
    public ResponseEntity<AuroraStatusResponse> getStatus() {
        var level = stateCache.getCurrentLevel();
        if (level == null && !stateCache.isActive()) {
            // State machine is IDLE and no level has been set yet — return a default GREEN status
            return ResponseEntity.ok(new AuroraStatusResponse(
                    com.gregochr.goldenhour.entity.AlertLevel.GREEN,
                    com.gregochr.goldenhour.entity.AlertLevel.GREEN.hexColour(),
                    com.gregochr.goldenhour.entity.AlertLevel.GREEN.description(),
                    "unknown",
                    false,
                    0,
                    null));
        }
        var effectiveLevel = level != null ? level : com.gregochr.goldenhour.entity.AlertLevel.GREEN;
        return ResponseEntity.ok(new AuroraStatusResponse(
                effectiveLevel,
                effectiveLevel.hexColour(),
                effectiveLevel.description(),
                "AuroraWatch UK",
                stateCache.isActive(),
                stateCache.getCachedScores().size(),
                null));
    }

    /**
     * Returns scored aurora-eligible locations filtered by Bortle class and minimum star rating.
     *
     * <p>Only populated when the state machine is in the ACTIVE state (amber or red alert
     * in progress). Returns an empty list when IDLE.
     *
     * @param maxBortle  maximum Bortle class to include (default 4; relax to 5 for red alerts)
     * @param minStars   minimum star rating to include (default 1)
     * @return filtered list of aurora forecast scores
     */
    @GetMapping("/locations")
    public List<AuroraForecastScore> getLocations(
            @RequestParam(defaultValue = "4") int maxBortle,
            @RequestParam(defaultValue = "1") int minStars) {
        return stateCache.getCachedScores().stream()
                .filter(s -> s.location().getBortleClass() == null
                        || s.location().getBortleClass() <= maxBortle)
                .filter(s -> s.stars() >= minStars)
                .toList();
    }
}
