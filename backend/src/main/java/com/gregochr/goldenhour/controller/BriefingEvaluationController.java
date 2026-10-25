package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.util.Authorities;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
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
    private final Clock clock;

    /**
     * Constructs a {@code BriefingEvaluationController}.
     *
     * @param evaluationService     the service that owns the cache and admin clear
     * @param evaluationViewService the merged evaluation view service
     * @param clock                 supplies "today" on the UK civil calendar, via
     *                              {@link ForecastHorizon} — the same anchor
     *                              {@code ForecastController}'s list endpoint uses, because the
     *                              two windows have to match
     */
    public BriefingEvaluationController(BriefingEvaluationService evaluationService,
            EvaluationViewService evaluationViewService, Clock clock) {
        this.evaluationService = evaluationService;
        this.evaluationViewService = evaluationViewService;
        this.clock = clock;
    }

    /**
     * Returns all evaluation views across all enabled locations for the standard forecast
     * horizon (T-7 to T+N). Merges scored results from {@code cached_evaluation} with
     * triage/scored rows from {@code forecast_evaluation}.
     *
     * <p>Consumed by the Plan and Map tabs on mount so batch-scored locations render
     * their scores immediately.
     *
     * <p>The Fiery Sky and Golden Hour scores and the per-location written why are Pro features
     * (docs/engineering/pro-features-gate-plan.md §6): a reader who is neither PRO_USER nor ADMIN
     * gets each row through {@link LocationEvaluationView#forLite()}. The check is fail-closed, and
     * the ETag is hashed from the body, so the two variants never share a validator.
     *
     * @param auth the caller's authentication
     * @return flat list of evaluation views with data (source != NONE), shaped for the caller's role
     */
    @GetMapping("/scores")
    public ResponseEntity<List<LocationEvaluationView>> getAllScores(Authentication auth) {
        // Deliberately the same window as ForecastController's list endpoint — same two constants
        // and, since both must name the same days, the same UK civil anchor. The Map's date strip
        // derives its dates from the FORECAST payload alone, so a score returned for a date outside
        // that window has no chip to surface it, and a chip with no score behind it reads as a
        // stand-down rather than as absent (see frontend `standDown.js`). The two drifted apart for
        // one hour a night when the forecast window moved to Europe/London and this did not.
        LocalDate today = ForecastHorizon.today(clock);
        LocalDate from = today.minusDays(ForecastHorizon.SERVE_PAST_DAYS);
        LocalDate horizon = today.plusDays(ForecastCommandFactory.FORECAST_HORIZON_DAYS);
        List<LocationEvaluationView> views = evaluationViewService.forDateRange(
                from, horizon, Set.of(TargetType.SUNRISE, TargetType.SUNSET));
        boolean pro = Authorities.hasRole(auth, UserRole.PRO_USER)
                || Authorities.hasRole(auth, UserRole.ADMIN);
        return ResponseEntity.ok(pro ? views : views.stream().map(LocationEvaluationView::forLite).toList());
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
