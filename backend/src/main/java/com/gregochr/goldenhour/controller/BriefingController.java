package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingDigestResponse;
import com.gregochr.goldenhour.model.BriefingModelTestResultDto;
import com.gregochr.goldenhour.model.BriefingModelTestRunDto;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.service.BriefingDigestService;
import com.gregochr.goldenhour.service.BriefingModelTestService;
import com.gregochr.goldenhour.service.BriefingRoleShaper;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.util.Authorities;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the daily briefing — a zero-Claude-cost pre-flight check
 * that reports weather and tide conditions across all enabled colour locations.
 *
 * <p>Serves the cached briefing result. The cache is populated by the scheduled
 * briefing refresh job every 2 hours. Also provides admin endpoints for briefing
 * model comparison testing.
 */
@RestController
@RequestMapping("/api/briefing")
public class BriefingController {

    private final BriefingService briefingService;
    private final BriefingModelTestService briefingModelTestService;
    private final BriefingDigestService briefingDigestService;
    private final BriefingRoleShaper briefingRoleShaper;

    /**
     * Constructs a {@code BriefingController}.
     *
     * @param briefingService          the service that manages the briefing cache
     * @param briefingModelTestService the service that runs briefing model comparisons
     * @param briefingDigestService    the service that projects the briefing into the digest
     * @param briefingRoleShaper       the shaper that strips Pro-only tide data for LITE readers
     */
    public BriefingController(BriefingService briefingService,
            BriefingModelTestService briefingModelTestService,
            BriefingDigestService briefingDigestService,
            BriefingRoleShaper briefingRoleShaper) {
        this.briefingService = briefingService;
        this.briefingModelTestService = briefingModelTestService;
        this.briefingDigestService = briefingDigestService;
        this.briefingRoleShaper = briefingRoleShaper;
    }

    /**
     * Returns the most recent daily briefing, or 204 No Content if no briefing
     * has been generated yet.
     *
     * <p>Calls the API-facing variant of the cached-briefing accessor so the
     * Gate 2 honesty filter is applied to any region with zero Claude-scored
     * locations before the response leaves the service layer.
     *
     * <p><b>The body is role-shaped.</b> The tide curve and the per-location tide match are Pro
     * features (docs/engineering/tide-pro-gate-plan.md §7), so a reader who is neither PRO_USER nor
     * ADMIN gets the briefing with them removed. The check is fail-closed: anything that is not
     * positively PRO or ADMIN is served as LITE. The strip happens only here, at the HTTP edge, so
     * the digest and Ask keep the full data. A role variant is not personal data: the body depends
     * on the role alone, and the ETag is hashed from the body, so the two variants never share one.
     *
     * @param auth the caller's authentication
     * @return the cached briefing response with honesty filter applied, shaped for the caller's role
     */
    @GetMapping
    public ResponseEntity<DailyBriefingResponse> getBriefing(Authentication auth) {
        DailyBriefingResponse briefing = briefingService.getCachedBriefingForApi();
        if (briefing == null) {
            return ResponseEntity.noContent().build();
        }
        boolean pro = Authorities.hasRole(auth, UserRole.PRO_USER)
                || Authorities.hasRole(auth, UserRole.ADMIN);
        return ResponseEntity.ok(pro ? briefing : briefingRoleShaper.forLite(briefing));
    }

    /**
     * Returns a flat, bounded, chronological projection of the briefing's solar windows, or 204 No
     * Content when no briefing has been generated yet.
     *
     * <p>The same forecast {@link #getBriefing(Authentication)} serves, with the tree removed — for clients that
     * cannot afford to walk it. Bearer with no role gate, by inheritance from {@code /api/**} →
     * {@code .authenticated()}: it is a strict projection of a payload every authenticated caller
     * may already read in full, so a gate here would deny nothing and break the callers it exists
     * for. It carries no per-user data, which is what makes it safe to ETag-revalidate alongside
     * {@code /api/briefing}.
     *
     * @param limit how many windows to return, clamped into
     *              [1, {@value BriefingDigestService#MAX_LIMIT}]
     * @return the digest, or 204 when there is no briefing
     */
    @GetMapping("/digest")
    public ResponseEntity<BriefingDigestResponse> getDigest(
            @RequestParam(defaultValue = "" + BriefingDigestService.DEFAULT_LIMIT) int limit) {
        BriefingDigestResponse digest = briefingDigestService.digest(limit);
        if (digest == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(digest);
    }

    /**
     * Triggers an immediate briefing refresh and waits for it. Admin-only.
     *
     * <p>Refused with 409 while a refresh is already running — typically the pipeline's own,
     * at the tail of a cycle. Queuing a second build behind it would only repeat the one the
     * admin is about to get anyway.
     *
     * @return 200 once the refresh is complete, or 409 Conflict if one was already running
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> runBriefing() {
        if (!briefingService.refreshBriefingIfIdle()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "A briefing refresh is already running."));
        }
        return ResponseEntity.ok(Map.of("status", "Briefing refresh complete."));
    }

    /**
     * Runs a briefing model comparison test — calls Haiku, Sonnet, and Opus with the
     * same rollup data from the current cached briefing. Admin-only.
     *
     * @return the completed test run entity with summary stats
     */
    @PostMapping("/compare-models")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BriefingModelTestRunDto> runComparison() {
        return ResponseEntity.ok(BriefingModelTestRunDto.from(briefingModelTestService.runComparison()));
    }

    /**
     * Returns the 20 most recent briefing model test runs. Admin-only.
     *
     * @return recent test runs, newest first
     */
    @GetMapping("/compare-models/runs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<BriefingModelTestRunDto>> getComparisonRuns() {
        return ResponseEntity.ok(briefingModelTestService.getRecentRuns().stream()
                .map(BriefingModelTestRunDto::from).toList());
    }

    /**
     * Returns the results for a specific briefing model test run. Admin-only.
     *
     * @param runId the test run ID
     * @return results ordered by evaluation model
     */
    @GetMapping("/compare-models/results")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<BriefingModelTestResultDto>> getComparisonResults(
            @RequestParam Long runId) {
        return ResponseEntity.ok(briefingModelTestService.getResults(runId).stream()
                .map(BriefingModelTestResultDto::from).toList());
    }
}
