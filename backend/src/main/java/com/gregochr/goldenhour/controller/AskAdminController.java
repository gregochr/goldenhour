package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.service.ask.AskDryRunService;
import com.gregochr.goldenhour.service.ask.AskMetricsService;
import com.gregochr.goldenhour.service.ask.AskReadyPrecompute;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin-only Ask PhotoCast endpoints (plan §2.9): the dry-run, the Ready precompute and the metrics.
 * Parses and delegates: the dry-run's rules live in {@link AskDryRunService}.
 *
 * <p>Every endpoint answers 404 while {@code photocast.ask.enabled} is false, from
 * {@code AskFlagInterceptor} before the request reaches this class, so a switched-off feature has no
 * surface. The role check still comes first: a non-admin is 403 and an anonymous caller 401 whatever
 * the flag says (the interceptor stands down for a caller without {@code ROLE_ADMIN}, so
 * {@code @PreAuthorize} answers them).
 */
@RestController
@RequestMapping("/api/admin/ask")
@PreAuthorize("hasRole('ADMIN')")
public class AskAdminController {

    private final AskDryRunService dryRunService;
    private final AskReadyPrecompute readyPrecompute;
    private final AskMetricsService metricsService;

    /**
     * Constructs the controller.
     *
     * @param dryRunService  runs the dry-run
     * @param readyPrecompute   the Ready precompute
     * @param metricsService the question-log metrics
     */
    public AskAdminController(AskDryRunService dryRunService, AskReadyPrecompute readyPrecompute,
            AskMetricsService metricsService) {
        this.dryRunService = dryRunService;
        this.readyPrecompute = readyPrecompute;
        this.metricsService = metricsService;
    }

    /**
     * Runs one question through whichever engine is active, as the calling admin, and returns the
     * outcome with the tool trace, so an admin can see what the engine did and why.
     *
     * <p>⚠️ <b>With the Claude engine this spends real money</b>; see {@link AskDryRunService}.
     *
     * @param request the question, regions and optional context window
     * @param auth    the calling admin; their user context lets a drive-time question work
     * @return 200 with the outcome and trace; 400 for a bad question or region; 409 when no briefing
     *         has been built yet
     */
    @PostMapping("/dry-run")
    public ResponseEntity<?> dryRun(@RequestBody AskDryRunService.Request request, Authentication auth) {
        AskDryRunService.Result result = dryRunService.dryRun(request, auth);
        if (result.response() != null) {
            return ResponseEntity.ok(result.response());
        }
        HttpStatus status = result.noBriefing() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", result.error()));
    }

    /**
     * The Ready precompute's report.
     *
     * @param written the answers stored
     * @param skipped the questions not run (unavailable for a scope, nothing to show, or left when
     *                the 5-minute deadline came)
     * @param failed  the questions that failed and stored nothing
     */
    public record PrecomputeResponse(int written, int skipped, int failed) {
    }

    /**
     * Runs the Ready precompute now, on the calling thread, and reports what it did: the owner's
     * lever after a manual briefing rebuild (which does not trigger it) and the only way to get
     * Ready answers locally. It is the same precompute the pipeline dispatches after each cycle —
     * the same 5-minute deadline, the same refusals — except that it is not counted against, or
     * stopped by, {@code photocast.ask.ready.max-cycles-per-day}: pressing it is a person's
     * decision.
     *
     * <p>⚠️ With the Claude engine this spends real money (one run per scope and question, billed to
     * an {@code ASK_READY} job run); with {@code photocast.ask.stub=true} it spends nothing.
     *
     * @return 200 with {@code {written, skipped, failed}}; 409 with
     *         {@code {error}} when the precompute was refused as a whole (no fresh briefing, a
     *         simulation is active, or another precompute is running)
     */
    @PostMapping("/ready/precompute")
    public ResponseEntity<?> precompute() {
        AskReadyPrecompute.Result result = readyPrecompute.precomputeOnDemand();
        if (result.wasRefused()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", result.refusal()));
        }
        return ResponseEntity.ok(new PrecomputeResponse(result.written(), result.skipped(),
                result.failed()));
    }

    /**
     * How Ask PhotoCast is being used and what it costs over the last {@code days} UK civil days
     * (including today): the count of each outcome, the cache-hit, Ready-match and can't-answer rates,
     * the most common things PhotoCast was asked for and does not have, and the typed and Ready spend.
     * <b>It never returns a question</b>, raw or normalised (see {@link AskMetricsService}).
     *
     * @param days the window as text; a whole number, clamped to 1..90 rather than refused (so an
     *             absurdly large or negative one is a valid request), default 7
     * @return 200 with the metrics; 400 when {@code days} is not a whole number
     */
    @GetMapping("/metrics")
    public ResponseEntity<?> metrics(@RequestParam(name = "days", required = false) String days) {
        int window = AskMetricsService.DEFAULT_DAYS;
        if (days != null && !days.isBlank()) {
            try {
                window = AskMetricsService.clampDays(days.strip());
            } catch (NumberFormatException e) {
                return badRequest("days must be a whole number.");
            }
        }
        return ResponseEntity.ok(metricsService.metrics(window));
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
