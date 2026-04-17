package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraStatusResponse;
import com.gregochr.goldenhour.model.AuroraViewlineResponse;
import com.gregochr.goldenhour.model.KpForecast;
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
                stateCache.getDarkSkyLocationCount(),
                stateCache.getClearLocationCount(),
                kp,
                stateCache.getLastTriggerKp(),
                triggerTypeStr,
                ovation,
                bz,
                DATA_SOURCE,
                updatedAt != null ? updatedAt : ZonedDateTime.now(ZoneOffset.UTC),
                stateCache.isSimulated(),
                stateCache.getActiveSince()));
    }

    /**
     * Returns the aurora viewline — the southernmost visible aurora boundary for UK longitudes.
     *
     * <p>Derived from NOAA SWPC OVATION nowcast data, clamped by a Kp-to-latitude hard cap
     * based on real-world UK observer reports. Returns {@code active: false} when no
     * significant aurora probability exists in the UK range.
     *
     * @return viewline response
     */
    @GetMapping("/viewline")
    public AuroraViewlineResponse getViewline() {
        double currentKp = resolveCurrentKp();
        return noaaClient.fetchViewline(currentKp);
    }

    /**
     * Returns a forecast aurora extent line based on the Kp-to-latitude lookup table.
     *
     * <p>Used when the alert was triggered by a forecast (not live data). Returns a
     * straight line at the cap latitude for the forecast Kp value.
     *
     * @return forecast viewline response with {@code isForecast: true}
     */
    @GetMapping("/viewline/forecast")
    public AuroraViewlineResponse getForecastViewline() {
        Double forecastKp = stateCache.getLastTriggerKp();
        if (forecastKp == null) {
            return new AuroraViewlineResponse(
                    List.of(), "No forecast available", 90.0,
                    ZonedDateTime.now(ZoneOffset.UTC), false, true);
        }
        return noaaClient.buildForecastViewline(forecastKp);
    }

    /**
     * Resolves the current Kp index for viewline capping.
     *
     * <p>Uses the latest real-time Kp reading (same source as the banner). Falls back to
     * the peak Kp from the 3-day NOAA forecast if no real-time data is available, or a
     * conservative default of 4.0 if both sources fail.
     *
     * @return best-available current Kp value
     */
    private double resolveCurrentKp() {
        try {
            List<KpReading> recentKp = noaaClient.fetchKp();
            if (!recentKp.isEmpty()) {
                return recentKp.get(recentKp.size() - 1).kp();
            }
            List<KpForecast> forecast = noaaClient.fetchKpForecast();
            if (!forecast.isEmpty()) {
                return forecast.stream()
                        .mapToDouble(KpForecast::kp)
                        .max()
                        .orElse(4.0);
            }
        } catch (Exception ignored) {
            // Fail-safe — use conservative default
        }
        return 4.0;
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
