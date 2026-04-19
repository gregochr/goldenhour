package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
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
import java.util.Map;
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
    private final Executor forecastExecutor;

    /**
     * Constructs a {@code BriefingEvaluationController}.
     *
     * @param evaluationService the service that orchestrates evaluations and caching
     * @param forecastExecutor  virtual-thread executor for async SSE work
     */
    public BriefingEvaluationController(BriefingEvaluationService evaluationService,
            @Qualifier("forecastExecutor") Executor forecastExecutor) {
        this.evaluationService = evaluationService;
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
