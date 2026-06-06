package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST endpoints for the briefing evaluation cache.
 *
 * <p>Exposes the merged evaluation view (cached results + triage/scored rows) consumed by
 * the Plan and Map tabs, plus an admin escape hatch to clear stale cache entries. The
 * legacy SSE per-location evaluation endpoint was retired in Pass 3.3.3; on-demand
 * re-evaluation is no longer available — fresh scores arrive via the overnight and
 * intraday batch pipelines instead.
 */
@RestController
@RequestMapping("/api/briefing/evaluate")
public class BriefingEvaluationController {

    private final BriefingEvaluationService evaluationService;
    private final EvaluationViewService evaluationViewService;

    /**
     * Constructs a {@code BriefingEvaluationController}.
     *
     * @param evaluationService     the service that owns the cache and admin clear
     * @param evaluationViewService the merged evaluation view service
     */
    public BriefingEvaluationController(BriefingEvaluationService evaluationService,
            EvaluationViewService evaluationViewService) {
        this.evaluationService = evaluationService;
        this.evaluationViewService = evaluationViewService;
    }

    /**
     * Returns all evaluation views across all enabled locations for the standard forecast
     * horizon (T-7 to T+N). Merges scored results from {@code cached_evaluation} with
     * triage/scored rows from {@code forecast_evaluation}.
     *
     * <p>Consumed by the Plan and Map tabs on mount so batch-scored locations render
     * scored medallions immediately.
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
     * <p>Requires an explicit {@code ?confirm=true} so this destructive full-table wipe
     * cannot be triggered accidentally. The service logs a WARN with the caller and the row
     * count removed.
     *
     * @param confirm must be {@code true} to proceed; otherwise the request is rejected
     * @return the number of entries cleared, or a 400 when confirmation is missing
     */
    @DeleteMapping("/cache")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> clearCache(
            @RequestParam(name = "confirm", defaultValue = "false") boolean confirm) {
        if (!confirm) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Confirmation required",
                    "message", "Pass ?confirm=true to clear the evaluation cache"));
        }
        int cleared = evaluationService.clearCache();
        return ResponseEntity.ok(Map.of("cleared", cleared));
    }
}
