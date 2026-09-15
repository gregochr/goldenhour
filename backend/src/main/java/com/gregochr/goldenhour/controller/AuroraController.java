package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraStatusResponse;
import com.gregochr.goldenhour.model.AuroraViewlineResponse;
import com.gregochr.goldenhour.model.CurrentNight;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
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
    private final AuroraForecastRunService forecastRunService;

    /**
     * Constructs the controller.
     *
     * @param stateCache         the aurora state machine and score cache
     * @param noaaClient         NOAA SWPC client for enriching the status response
     * @param forecastRunService consulted only for {@code currentNight()} — the map needs the same
     *                           night the run pipeline uses, and when it ends, and this keeps that
     *                           rule in the one class that owns it rather than reimplementing
     *                           dusk/dawn geometry in the browser
     */
    public AuroraController(AuroraStateCache stateCache, NoaaSwpcClient noaaClient,
            AuroraForecastRunService forecastRunService) {
        this.stateCache = stateCache;
        this.noaaClient = noaaClient;
        this.forecastRunService = forecastRunService;
    }

    /**
     * Returns the current alert level and state machine state derived from NOAA SWPC data.
     *
     * <p>Includes the latest Kp and OVATION probability to give the frontend raw signal
     * data alongside the derived level.
     *
     * <p>It also carries {@code currentNightDate} — the night in progress, which between midnight
     * and dawn is <em>yesterday's</em> date. The map defaults to it in aurora mode, so that a
     * forecast run at 02:00 opens on the night it scored rather than on a date with no results.
     * The rule lives in {@code AuroraForecastRunService} and is read here, never re-derived. With it
     * comes {@code currentNightEndsAt}, the instant that night ends, from the same read of the
     * clock: the client keeps its last status when a later fetch fails, so it needs to know when
     * that status's night stopped being the current one.
     *
     * <p>Every state-machine field is read once, up front, before the live NOAA calls. Those calls
     * can wait on NOAA for as long as a cache refresh takes, and the polling job can move the state
     * machine meanwhile. The level used to be read before them and {@code active}, the counts and
     * {@code detectedAt} after, so a transition landing in between answered for two states at once:
     * {@code MODERATE} and not active across a CLEAR, {@code QUIET} and active across a NOTIFY.
     *
     * <p>Before the calls rather than after them, although after would be fresher, because the
     * frontend's {@code AuroraStatusProvider} applies answers in the order their requests were
     * made. Read on arrival, a response's state is as old as its request; read after its NOAA wait,
     * a slow earlier request would carry newer state than a quick later one, and be the answer the
     * client drops. The night and its end are read there too, for the same reason: they depend only
     * on the clock, but read after a NOAA wait, a request made before dawn would answer for the
     * night after it.
     *
     * <p>Two residuals remain. The fields are separate volatiles read one after another, so a writer
     * caught part-way through its writes can still show in one response — during an admin
     * simulation, its level beside {@code simulated: false}, or {@code simulated: true} with no data
     * yet. And {@code AuroraOrchestrator} writes one NOTIFY in several steps, and CLEAR never resets
     * the trigger, so the machine itself can hold a new level beside the previous alert's trigger.
     * Both polls read NOAA before the state machine moves, so that lasts a few field writes; only a
     * daylight NOTIFY that an admin reset or simulation hid from the poll's check still fetches in
     * between. This read serves that faithfully; no snapshot taken here could fix it.
     *
     * @return current aurora status
     */
    @GetMapping("/status")
    public ResponseEntity<AuroraStatusResponse> getStatus() {
        AlertLevel cachedLevel = stateCache.getCurrentLevel();
        boolean active = stateCache.isActive();
        int eligibleLocations = stateCache.getCachedScores().size();
        int darkSkyLocationCount = stateCache.getDarkSkyLocationCount();
        Integer clearLocationCount = stateCache.getClearLocationCount();
        TriggerType lastTrigger = stateCache.getLastTriggerType();
        Double lastTriggerKp = stateCache.getLastTriggerKp();
        Instant activeSince = stateCache.getActiveSince();
        boolean simulated = stateCache.isSimulated();
        AuroraStateCache.SimulatedNoaaData simData = stateCache.getSimulatedData();
        // The night too, and from one call, so its date and end come from one read of the clock and
        // name the same night. Null only from a stubbed service; relayed as-is, never replaced with a
        // calendar date — and checked once, since a second check of the same value is one SpotBugs
        // rejects as redundant.
        CurrentNight night = forecastRunService.currentNight();
        LocalDate nightDate = null;
        Instant nightEndsAt = null;
        if (night != null) {
            nightDate = night.date();
            nightEndsAt = night.endsAt();
        }

        AlertLevel level = cachedLevel == null ? AlertLevel.QUIET : cachedLevel;

        Double kp = null;
        Double ovation = null;
        Double bz = null;
        String gScale = null;
        ZonedDateTime updatedAt = null;

        if (simulated) {
            // Return simulated NOAA values — no live API call needed
            kp = simData.kp();
            ovation = simData.ovationProbability();
            bz = simData.bzNanoTesla();
            gScale = simData.gScale();
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

        String triggerTypeStr = lastTrigger == null ? null
                : (lastTrigger == TriggerType.FORECAST_LOOKAHEAD ? "forecast" : "realtime");

        if (!simulated) {
            // Derive the storm scale from the Kp that drove the alert (the forecast trigger Kp
            // where present, else the latest live Kp), so the banner's severity index tracks
            // amber-vs-red escalation. Null below the G1 storm threshold.
            Double severityKp = lastTriggerKp != null ? lastTriggerKp : kp;
            gScale = AlertLevel.gScaleFromKp(severityKp);
        }

        return ResponseEntity.ok(new AuroraStatusResponse(
                level,
                level.hexColour(),
                level.description(),
                active,
                eligibleLocations,
                darkSkyLocationCount,
                clearLocationCount,
                kp,
                lastTriggerKp,
                triggerTypeStr,
                ovation,
                bz,
                DATA_SOURCE,
                updatedAt != null ? updatedAt : ZonedDateTime.now(ZoneOffset.UTC),
                simulated,
                activeSince,
                gScale,
                nightDate,
                nightEndsAt));
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
