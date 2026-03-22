package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraStatusResponse;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * REST controller for aurora photography forecasting.
 *
 * <p>All non-admin endpoints are gated to {@code ADMIN} and {@code PRO_USER} roles.
 */
@RestController
@RequestMapping("/api/aurora")
@PreAuthorize("hasAnyRole('ADMIN', 'PRO_USER')")
public class AuroraController {

    private static final String DATA_SOURCE = "NOAA SWPC";

    private final AuroraStateCache stateCache;
    private final NoaaSwpcClient noaaClient;

    /**
     * Constructs the controller.
     *
     * @param stateCache the aurora state machine and score cache
     * @param noaaClient NOAA SWPC client for enriching the status response
     */
    public AuroraController(AuroraStateCache stateCache, NoaaSwpcClient noaaClient) {
        this.stateCache = stateCache;
        this.noaaClient = noaaClient;
    }

    /**
     * Returns the current alert level and state machine state derived from NOAA SWPC data.
     *
     * <p>Includes the latest Kp and OVATION probability to give the frontend raw signal
     * data alongside the derived level.
     *
     * @return current aurora status
     */
    @GetMapping("/status")
    public ResponseEntity<AuroraStatusResponse> getStatus() {
        AlertLevel level = stateCache.getCurrentLevel();
        if (level == null) {
            level = AlertLevel.QUIET;
        }

        Double kp = null;
        Double ovation = null;
        Double bz = null;
        ZonedDateTime updatedAt = null;

        if (stateCache.isSimulated()) {
            // Return simulated NOAA values — no live API call needed
            AuroraStateCache.SimulatedNoaaData simData = stateCache.getSimulatedData();
            kp = simData.kp();
            ovation = simData.ovationProbability();
            bz = simData.bzNanoTesla();
            updatedAt = ZonedDateTime.now(ZoneOffset.UTC);
        } else {
            try {
                List<KpReading> recentKp = noaaClient.fetchKp();
                if (!recentKp.isEmpty()) {
                    KpReading latest = recentKp.get(recentKp.size() - 1);
                    kp = latest.kp();
                    updatedAt = latest.timestamp();
                }
                OvationReading ovationReading = noaaClient.fetchOvation();
                if (ovationReading != null) {
                    ovation = ovationReading.probabilityAtLatitude();
                }
                List<com.gregochr.goldenhour.model.SolarWindReading> solarWind = noaaClient.fetchSolarWind();
                if (!solarWind.isEmpty()) {
                    bz = solarWind.get(solarWind.size() - 1).bzNanoTesla();
                }
            } catch (Exception ignored) {
                // Best-effort enrichment — don't fail the status endpoint over cached data
            }
        }

        TriggerType lastTrigger = stateCache.getLastTriggerType();
        String triggerTypeStr = lastTrigger == null ? null
                : (lastTrigger == TriggerType.FORECAST_LOOKAHEAD ? "forecast" : "realtime");

        return ResponseEntity.ok(new AuroraStatusResponse(
                level,
                level.hexColour(),
                level.description(),
                stateCache.isActive(),
                stateCache.getCachedScores().size(),
                kp,
                stateCache.getLastTriggerKp(),
                triggerTypeStr,
                ovation,
                bz,
                DATA_SOURCE,
                updatedAt != null ? updatedAt : ZonedDateTime.now(ZoneOffset.UTC),
                stateCache.isSimulated()));
    }

    /**
     * Returns scored aurora-eligible locations filtered by Bortle class and minimum star rating.
     *
     * <p>Only populated when the state machine is in the ACTIVE state.
     * Returns an empty list when IDLE.
     *
     * @param maxBortle  maximum Bortle class to include (default 4; relax to 5 for strong alerts)
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
