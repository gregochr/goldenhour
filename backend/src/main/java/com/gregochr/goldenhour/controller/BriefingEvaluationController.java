package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * SSE and REST endpoints for briefing drill-down Claude evaluations.
 *
 * <p>The SSE endpoint streams per-location scores as each Claude evaluation completes.
 * The cache endpoint returns any previously computed results without triggering evaluations.
 */
@RestController
@RequestMapping("/api/briefing/evaluate")
public class BriefingEvaluationController {

    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final BriefingEvaluationService evaluationService;
    private final EvaluationViewService evaluationViewService;
    private final Executor forecastExecutor;

    /**
     * Constructs a {@code BriefingEvaluationController}.
     *
     * @param evaluationService     the service that orchestrates evaluations and caching
     * @param evaluationViewService the merged evaluation view service
     * @param forecastExecutor      virtual-thread executor for async SSE work
     */
    public BriefingEvaluationController(BriefingEvaluationService evaluationService,
            EvaluationViewService evaluationViewService,
            @Qualifier("forecastExecutor") Executor forecastExecutor) {
        this.evaluationService = evaluationService;
        this.evaluationViewService = evaluationViewService;
        this.forecastExecutor = forecastExecutor;
    }

    /**
     * Streams Claude evaluation results for all GO/MARGINAL locations in a region.
     *
     * <p>Events: {@code location-scored}, {@code evaluation-error}, {@code progress},
     * {@code evaluation-complete}. Cached results are replayed instantly on repeat calls.
     *
     * @param regionName the region to evaluate
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return an SSE emitter streaming evaluation results
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasRole('PRO_USER')")
    public SseEmitter evaluate(
            @RequestParam String regionName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam TargetType targetType) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CompletableFuture.runAsync(
                () -> evaluationService.evaluateRegion(regionName, date, targetType, emitter),
                forecastExecutor);
        return emitter;
    }

    /**
     * Returns cached evaluation scores for a region/date/targetType without triggering
     * new evaluations.
     *
     * @param regionName the region name
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return map of locationName to evaluation result, or empty map
     */
    @GetMapping("/cache")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PRO_USER')")
    public ResponseEntity<Map<String, BriefingEvaluationResult>> getCachedScores(
            @RequestParam String regionName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam TargetType targetType) {
        return ResponseEntity.ok(evaluationService.getCachedScores(regionName, date, targetType));
    }

    /**
     * Returns the UK-formatted evaluation timestamp for cached results.
     *
     * @param regionName the region name
     * @param date       the forecast date
     * @param targetType SUNRISE or SUNSET
     * @return the formatted time (e.g. "14:32") or 204 No Content
     */
    @GetMapping("/cache/timestamp")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PRO_USER')")
    public ResponseEntity<Map<String, String>> getCachedTimestamp(
            @RequestParam String regionName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam TargetType targetType) {
        String evaluatedAt = evaluationService.getCachedEvaluatedAt(regionName, date, targetType);
        if (evaluatedAt == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of("evaluatedAt", evaluatedAt));
    }

    /**
     * Returns all evaluation views across all enabled locations for the standard forecast
     * horizon (T-7 to T+N). Merges scored results from {@code cached_evaluation} with
     * triage/scored rows from {@code forecast_evaluation}.
     *
     * <p>Used by the Map tab to pre-load all scores on mount, so batch-scored locations
     * render scored medallions without requiring an SSE drill-down first.
     *
     * @return flat list of evaluation views with data (source != NONE)
     */
    @GetMapping("/scores")
    public ResponseEntity<List<LocationEvaluationView>> getAllScores() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(7);
        LocalDate horizon = today.plusDays(ForecastCommandFactory.FORECAST_HORIZON_DAYS);
        List<LocationEvaluationView> views = evaluationViewService.forDateRange(
                from, horizon, Set.of(TargetType.SUNRISE, TargetType.SUNSET));
        return ResponseEntity.ok(views);
    }

    /**
     * Clears all cached evaluation results. Admin escape hatch for when scores
     * become genuinely stale or corrupted.
     *
     * @return the number of entries cleared
     */
    @DeleteMapping("/cache")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> clearCache() {
        int cleared = evaluationService.clearCache();
        return ResponseEntity.ok(Map.of("cleared", cleared));
    }
}
