package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.UserSettingsService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The admin dry-run (plan §2.9): one question through whichever engine is active, as the calling
 * admin, with the tool trace. Everything {@code POST /api/admin/ask/dry-run} decides lives here so the
 * controller only parses and maps.
 *
 * <p>⚠️ <b>With the Claude engine this spends real money.</b> It is a real conversation: the model
 * turns are logged to {@code api_call_log} under the day's {@code ASK} job run and count toward
 * today's typed spend, exactly as a reader's question does (the spend cap applies to the typed
 * endpoint, not to this one — an admin is trusted to know what they are pressing). With
 * {@code photocast.ask.stub=true} it spends nothing and writes nothing. The question is minimally
 * cleaned ({@link AskQuestionSanitiser#sanitise}); the typed endpoint's guards (rate limit, allowance,
 * pre-filter, cache) are deliberately absent.
 */
@Service
public class AskDryRunService {

    private final AskProperties properties;
    private final AskEngine engine;
    private final AskSnapshotBuilder snapshotBuilder;
    private final RegionRepository regionRepository;
    private final UserSettingsService settingsService;
    private final DriveTimeResolver driveTimeResolver;

    /**
     * Creates the service.
     *
     * @param properties        the Ask settings (which engine is labelled)
     * @param engine            the one active engine: the stub or Claude
     * @param snapshotBuilder   builds the snapshot the engine reads
     * @param regionRepository  validates the question's region ids
     * @param settingsService   resolves the calling admin's user id
     * @param driveTimeResolver whether the calling admin has stored drive times
     */
    public AskDryRunService(AskProperties properties, AskEngine engine,
            AskSnapshotBuilder snapshotBuilder, RegionRepository regionRepository,
            UserSettingsService settingsService, DriveTimeResolver driveTimeResolver) {
        this.properties = properties;
        this.engine = engine;
        this.snapshotBuilder = snapshotBuilder;
        this.regionRepository = regionRepository;
        this.settingsService = settingsService;
        this.driveTimeResolver = driveTimeResolver;
    }

    /**
     * The dry-run request.
     *
     * @param question  the question text
     * @param regionIds the regions asked about; null or empty means every region
     * @param windowId  the context window ({@code yyyy-MM-dd_sunrise|sunset}), optional; one that
     *                  is not in the window set is ignored
     */
    public record Request(String question, List<Long> regionIds, String windowId) {
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
    public record Response(String engine, AskOutcome.Status status, AskAnswer answer,
            boolean personal, int turns, String reason, List<AskTools.ToolCall> trace) {
    }

    /**
     * How a dry-run ended: it ran, the request was not valid, or there was no briefing to run it
     * against.
     *
     * @param response   the outcome and trace, or null when the request was refused
     * @param error      the sentence for a refusal, or null when it ran
     * @param noBriefing true when the request was valid but no briefing has been built yet
     */
    public record Result(Response response, String error, boolean noBriefing) {

        private static Result ran(Response response) {
            return new Result(response, null, false);
        }

        private static Result invalid(String error) {
            return new Result(null, error, false);
        }

        private static Result missingBriefing() {
            return new Result(null, "No briefing has been built yet.", true);
        }
    }

    /**
     * Runs one question as the calling admin and returns the outcome with the tool trace, so an admin
     * can see what the engine did and why.
     *
     * @param request the question, regions and optional context window; null is refused
     * @param auth    the calling admin; their user context lets a drive-time question work
     * @return the result: the outcome, an invalid-request sentence, or no-briefing
     */
    public Result dryRun(Request request, Authentication auth) {
        if (request == null) {
            return Result.invalid("A request body is required.");
        }
        AskQuestionSanitiser.Result cleaned = AskQuestionSanitiser.sanitise(request.question());
        if (!cleaned.ok()) {
            return Result.invalid(cleaned.error());
        }
        Optional<AskScope> scope = AskScopes.resolve(regionRepository, request.regionIds());
        if (scope.isEmpty()) {
            return Result.invalid(AskScopes.INVALID_REGIONS);
        }
        Optional<AskSnapshot> snapshot = snapshotBuilder.current();
        if (snapshot.isEmpty()) {
            return Result.missingBriefing();
        }
        AskQuestion question = AskQuestion.of(cleaned, request.windowId(), scope.get(), "plan");
        AskRun run = engine.run(question, snapshot.get(), adminContext(auth), AskRunOptions.none());
        AskOutcome outcome = run.outcome();
        return Result.ran(new Response(properties.isStub() ? "stub" : "claude", outcome.status(),
                outcome.answer(), outcome.personal(), outcome.turns(), run.reason(), run.trace()));
    }

    /** The calling admin as a conversation's asker: their id, and whether they have drive times. */
    private AskUserContext adminContext(Authentication auth) {
        Long userId = settingsService.getUserId(auth);
        return new AskUserContext(userId, UserRole.ADMIN, driveTimeResolver.hasDriveTimes(userId));
    }
}
