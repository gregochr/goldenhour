package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.AuroraForecastPreview;
import com.gregochr.goldenhour.model.AuroraForecastResultDto;
import com.gregochr.goldenhour.model.AuroraForecastRunRequest;
import com.gregochr.goldenhour.model.AuroraForecastRunResponse;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * REST controller for user-triggered aurora forecast runs.
 *
 * <p>Exposes three endpoints:
 * <ul>
 *   <li>{@code GET /api/aurora/forecast/preview} — cheap 3-night Kp preview for the night
 *       selector popup (reads cached NOAA data, no Claude cost).</li>
 *   <li>{@code POST /api/aurora/forecast/run} — runs the full Claude pipeline for the selected
 *       nights and stores results to the database.</li>
 *   <li>{@code GET /api/aurora/forecast/results} — retrieves stored results for the map view.</li>
 *   <li>{@code GET /api/aurora/forecast/results/available-dates} — returns all dates with stored
 *       results so the frontend knows when to show the Aurora toggle.</li>
 * </ul>
 *
 * <p>All endpoints are gated to {@code ADMIN} and {@code PRO_USER} roles.
 */
@RestController
@RequestMapping("/api/aurora/forecast")
@PreAuthorize("hasAnyRole('ADMIN', 'PRO_USER')")
public class AuroraForecastController {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraForecastController.class);

    private final AuroraForecastRunService forecastRunService;

    /**
     * Constructs the controller.
     *
     * @param forecastRunService the aurora forecast run service
     */
    public AuroraForecastController(AuroraForecastRunService forecastRunService) {
        this.forecastRunService = forecastRunService;
    }

    /**
     * Returns a 3-night Kp preview for the night selector popup.
     *
     * <p>This is cheap — it reads the cached NOAA Kp forecast and counts eligible locations.
     * No Claude API calls are made. Response is suitable for pre-populating the night checkboxes
     * with Kp expectations before the user commits to a (paid) Claude run.
     *
     * @return preview of tonight, T+1, and T+2
     */
    @GetMapping("/preview")
    public ResponseEntity<AuroraForecastPreview> getPreview() {
        LOG.debug("Aurora forecast preview requested");
        return ResponseEntity.ok(forecastRunService.getPreview());
    }

    /**
     * Runs aurora forecasts for the user-selected nights.
     *
     * <p>For each requested night, this endpoint:
     * <ol>
     *   <li>Fetches NOAA Kp forecast and determines the alert level.</li>
     *   <li>Queries Bortle-eligible locations.</li>
     *   <li>Runs weather triage (tonight only; future dates pass all locations to Claude).</li>
     *   <li>Makes one Claude API call per viable night.</li>
     *   <li>Stores all results (Claude-scored + triaged) keyed by date and location.</li>
     * </ol>
     *
     * <p>Results for the requested dates are replaced if the user runs the same night again.
     *
     * @param request the nights to forecast
     * @return per-night outcomes and cost summary
     */
    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRO_USER')")
    public ResponseEntity<AuroraForecastRunResponse> runForecast(
            @RequestBody AuroraForecastRunRequest request) {
        LOG.info("Aurora forecast run requested for {} night(s): {}",
                request.nights() != null ? request.nights().size() : 0, request.nights());
        AuroraForecastRunResponse response = forecastRunService.runForecast(request);
        LOG.info("Aurora forecast run complete: {} night(s), {} Claude call(s), cost={}",
                response.nights().size(), response.totalClaudeCalls(), response.estimatedCost());
        return ResponseEntity.ok(response);
    }

    /**
     * Returns stored aurora forecast results for the map view.
     *
     * <p>Results are keyed by date and include one entry per location that was either
     * Claude-scored or weather-triaged. The frontend uses this to display aurora star
     * ratings on the map for any date that has been forecast.
     *
     * @param date the night to retrieve results for
     * @return list of scored/triaged locations for that date
     */
    @GetMapping("/results")
    public List<AuroraForecastResultDto> getResultsForDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return forecastRunService.getResultsForDate(date);
    }

    /**
     * Returns all distinct dates for which stored aurora forecast results exist.
     *
     * <p>Used by the frontend to determine whether the Aurora toggle should be shown
     * on the map (the Aurora option is only visible when results exist for at least one date).
     *
     * @return sorted list of ISO date strings (e.g. ["2026-03-21", "2026-03-22"])
     */
    @GetMapping("/results/available-dates")
    public List<String> getAvailableDates() {
        return forecastRunService.getAvailableDates();
    }
}
