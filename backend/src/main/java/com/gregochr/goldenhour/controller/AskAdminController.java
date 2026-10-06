package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.UserSettingsService;
import com.gregochr.goldenhour.service.ask.AskAnswer;
import com.gregochr.goldenhour.service.ask.AskEngine;
import com.gregochr.goldenhour.service.ask.AskOutcome;
import com.gregochr.goldenhour.service.ask.AskProperties;
import com.gregochr.goldenhour.service.ask.AskQuestion;
import com.gregochr.goldenhour.service.ask.AskQuestionSanitiser;
import com.gregochr.goldenhour.service.ask.AskReadyService;
import com.gregochr.goldenhour.service.ask.AskRun;
import com.gregochr.goldenhour.service.ask.AskRunOptions;
import com.gregochr.goldenhour.service.ask.AskSnapshot;
import com.gregochr.goldenhour.service.ask.AskSnapshotBuilder;
import com.gregochr.goldenhour.service.ask.AskTools;
import com.gregochr.goldenhour.service.ask.AskUserContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Admin-only Ask PhotoCast endpoints (plan §2.9): the dry-run and the Ready precompute; B5 adds the
 * metrics endpoint.
 *
 * <p>Every endpoint answers 404 while {@code photocast.ask.enabled} is false, so a switched-off
 * feature has no surface (the role check still comes first: a non-admin is 403 and an anonymous
 * caller 401 whatever the flag says).
 */
@RestController
@RequestMapping("/api/admin/ask")
@PreAuthorize("hasRole('ADMIN')")
public class AskAdminController {

    /** The most region ids a dry-run may name; the roster is a handful, so more is a mistake. */
    static final int MAX_REGION_IDS = 20;

    private final AskProperties properties;
    private final AskEngine engine;
    private final AskSnapshotBuilder snapshotBuilder;
    private final RegionRepository regionRepository;
    private final UserSettingsService settingsService;
    private final DriveTimeResolver driveTimeResolver;
    private final AskReadyService readyService;

    /**
     * Constructs the controller.
     *
     * @param properties        the Ask settings (the {@code enabled} flag)
     * @param engine            the one active engine: the stub or Claude
     * @param snapshotBuilder   builds the snapshot the engine reads
     * @param regionRepository  validates the question's region ids
     * @param settingsService   resolves the calling admin's user id
     * @param driveTimeResolver whether the calling admin has stored drive times
     * @param readyService      the Ready precompute
     */
    public AskAdminController(AskProperties properties, AskEngine engine,
            AskSnapshotBuilder snapshotBuilder, RegionRepository regionRepository,
            UserSettingsService settingsService, DriveTimeResolver driveTimeResolver,
            AskReadyService readyService) {
        this.properties = properties;
        this.engine = engine;
        this.snapshotBuilder = snapshotBuilder;
        this.regionRepository = regionRepository;
        this.settingsService = settingsService;
        this.driveTimeResolver = driveTimeResolver;
        this.readyService = readyService;
    }

    /**
     * The dry-run request.
     *
     * @param question  the question text
     * @param regionIds the regions asked about; null or empty means every region
     * @param windowId  the context window ({@code yyyy-MM-dd_sunrise|sunset}), optional; one that
     *                  is not in the window set is ignored
     */
    public record DryRunRequest(String question, List<Long> regionIds, String windowId) {
    }

    /**
     * The dry-run response.
     *
     * @param engine   {@code stub} or {@code claude}: which engine answered
     * @param status   OK, CANT or FAILED
     * @param answer   the validated answer, or null when FAILED
     * @param personal whether the conversation used the asker's own drive times
     * @param turns    how many model turns it took
     * @param reason   why a FAILED run failed, or null
     * @param trace    every tool call the conversation made, in order, errors included
     */
    public record DryRunResponse(String engine, AskOutcome.Status status, AskAnswer answer,
            boolean personal, int turns, String reason, List<AskTools.ToolCall> trace) {
    }

    /**
     * Runs one question through whichever engine is active, as the calling admin, and returns the
     * outcome with the tool trace, so an admin can see what the engine did and why.
     *
     * <p>⚠️ <b>With the Claude engine this spends real money.</b> It is a real conversation: the
     * model turns are logged to {@code api_call_log} under the day's {@code ASK} job run and count
     * toward today's typed spend, exactly as a reader's question does (the spend cap applies to the
     * typed endpoint, not to this one — an admin is trusted to know what they are pressing). With
     * {@code photocast.ask.stub=true} it spends nothing and writes nothing. The question is
     * minimally cleaned ({@link AskQuestionSanitiser}); the full typed-endpoint guards (rate limit,
     * allowance, pre-filter, cache) are B4 and B5 and are deliberately absent here.
     *
     * @param request the question, regions and optional context window
     * @param auth    the calling admin; their user context lets a drive-time question work
     * @return 200 with the outcome and trace; 400 for a bad question or region; 404 when Ask is
     *         off; 409 when no briefing has been built yet
     */
    @PostMapping("/dry-run")
    public ResponseEntity<?> dryRun(@RequestBody DryRunRequest request, Authentication auth) {
        if (!properties.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (request == null) {
            return badRequest("A request body is required.");
        }
        AskQuestionSanitiser.Result cleaned = AskQuestionSanitiser.sanitise(request.question());
        if (!cleaned.ok()) {
            return badRequest(cleaned.error());
        }
        Optional<List<Long>> regionIds = validRegionIds(request.regionIds());
        if (regionIds.isEmpty()) {
            return badRequest("Unknown, disabled or too many region ids.");
        }
        Optional<AskSnapshot> snapshot = snapshotBuilder.current();
        if (snapshot.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "No briefing has been built yet."));
        }
        // The normalised form is the cache key B5 will derive properly; a dry-run never caches.
        AskQuestion question = new AskQuestion(cleaned.sanitised(),
                cleaned.sanitised().toLowerCase(Locale.ROOT), request.windowId(),
                regionIds.get(), "plan");
        AskRun run = engine.run(question, snapshot.get(), adminContext(auth), AskRunOptions.none());
        AskOutcome outcome = run.outcome();
        return ResponseEntity.ok(new DryRunResponse(
                properties.isStub() ? "stub" : "claude", outcome.status(),
                outcome.answer(), outcome.personal(), outcome.turns(), run.reason(), run.trace()));
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
     * @return 200 with {@code {written, skipped, failed}}; 404 when Ask is off; 409 with
     *         {@code {error}} when the precompute was refused as a whole (no fresh briefing, a
     *         simulation is active, or another precompute is running)
     */
    @PostMapping("/ready/precompute")
    public ResponseEntity<?> precompute() {
        if (!properties.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        AskReadyService.Result result = readyService.precomputeOnDemand();
        if (result.wasRefused()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", result.refusal()));
        }
        return ResponseEntity.ok(new PrecomputeResponse(result.written(), result.skipped(),
                result.failed()));
    }

    /** The region ids to ask about when every one is a distinct, enabled region; empty otherwise. */
    private Optional<List<Long>> validRegionIds(List<Long> requested) {
        if (requested == null || requested.isEmpty()) {
            return Optional.of(List.of());
        }
        if (requested.size() > MAX_REGION_IDS || requested.contains(null)) {
            return Optional.empty();
        }
        Set<Long> ids = new LinkedHashSet<>(requested);
        List<RegionEntity> found = regionRepository.findAllById(ids);
        boolean allEnabled = found.size() == ids.size()
                && found.stream().allMatch(RegionEntity::isEnabled);
        return allEnabled ? Optional.of(new ArrayList<>(ids)) : Optional.empty();
    }

    /** The calling admin as a conversation's asker: their id, and whether they have drive times. */
    private AskUserContext adminContext(Authentication auth) {
        Long userId = settingsService.getHomeLocation(auth).userId();
        return new AskUserContext(userId, UserRole.ADMIN, driveTimeResolver.hasDriveTimes(userId));
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
