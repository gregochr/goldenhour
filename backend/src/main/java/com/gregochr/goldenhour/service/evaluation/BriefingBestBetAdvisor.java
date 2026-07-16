package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BestBetResult;
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.CandidateCoverage;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.RollupResult;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.gregochr.goldenhour.model.TokenUsage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Makes a single Haiku call after briefing triage completes to produce Claude-generated
 * "best bet" photography recommendations.
 *
 * <p>Input is region-level rollup data only (not per-location slot data), keeping the prompt
 * small (~1–2 KB). Any failure is caught and returns an empty list — the briefing always
 * loads and falls back to the mechanical headline.
 */
@Component
public class BriefingBestBetAdvisor {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingBestBetAdvisor.class);

    /**
     * Default response-token ceiling for the best-bet JSON (standard, non-thinking calls)
     * when {@code photocast.best-bet.max-tokens} is not overridden.
     *
     * <p>Sized with deliberate headroom over the previous 1024 ceiling. A 1024-token cap
     * was observed truncating a verbose two-pick response mid-field at ~3656 chars
     * (1024 output tokens ≈ ~3.6 KB of JSON/prose), which produced structurally invalid
     * JSON and zero persisted picks. 4096 comfortably clears a full reasoning-plus-two-picks
     * response even when the model is verbose in the output channel.
     */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    /** Maximum response tokens for extended-thinking calls (thinking budget + response). */
    private static final int MAX_TOKENS_THINKING = 16000;

    /**
     * The {@code claudeAverageRating} a region must reach to be eligible as Pick 2
     * ("Also Good") under either tier of the advisor's selection rule.
     *
     * <p>Diagnostic mirror of the threshold stated in {@link BestBetPromptText}, which remains
     * the single source of truth: this constant never gates selection or ranking. It exists only
     * so {@link #logPickTwoEligibility} can report whether any region <em>could</em> have
     * supported an Also Good pick. Keep it in step with the prompt if that rule changes.
     */
    private static final double PICK_TWO_RATING_FLOOR = 3.0;

    /** How many top-rated candidate slots {@link #logPickTwoEligibility} names. */
    private static final int PICK_TWO_LOG_TOP_N = 3;

    private final AnthropicApiClient anthropicApiClient;
    private final ObjectMapper objectMapper;
    private final JobRunService jobRunService;
    private final ModelSelectionService modelSelectionService;
    private final AuroraStateCache auroraStateCache;
    private final StabilitySnapshotProvider stabilitySnapshotProvider;
    private final BriefingEvaluationService briefingEvaluationService;
    private final TravelDayService travelDayService;

    /**
     * Response-token ceiling for the best-bet JSON on standard (non-thinking) calls.
     * Configurable without a redeploy via {@code photocast.best-bet.max-tokens}; defaults
     * to {@link #DEFAULT_MAX_TOKENS}.
     */
    private final int maxTokens;

    private final java.time.Clock clock;

    /** Derives the best dark-sky aurora region from the cached aurora scores. */
    private final AuroraRegionSelector auroraRegionSelector;

    /** Builds the region-level rollup JSON and reconstructs validation sets for replay. */
    private final BriefingRollupBuilder rollupBuilder;

    /** Enriches validated picks with structured display fields from the triage data. */
    private final BestBetEnricher enricher;

    /**
     * Constructs a {@code BriefingBestBetAdvisor}.
     *
     * @param anthropicApiClient         resilient Anthropic API client
     * @param objectMapper               Jackson mapper for JSON building and parsing
     * @param jobRunService              service for logging the API call in job run metrics
     * @param modelSelectionService      service for resolving the active Claude model
     * @param auroraStateCache           read-only access to the current aurora alert state
     * @param stabilitySnapshotProvider  provides the latest stability summary for region rollup
     * @param briefingEvaluationService  cached Claude evaluation scores from drill-down
     * @param travelDayService           excludes travel-day events from the candidate rollup
     * @param maxTokens                  response-token ceiling for standard best-bet calls
     * @param clock                      UTC clock supplying "now" and (via London) "today"
     *                                   ({@code photocast.best-bet.max-tokens})
     */
    public BriefingBestBetAdvisor(AnthropicApiClient anthropicApiClient,
            ObjectMapper objectMapper, JobRunService jobRunService,
            ModelSelectionService modelSelectionService,
            AuroraStateCache auroraStateCache,
            StabilitySnapshotProvider stabilitySnapshotProvider,
            @Lazy BriefingEvaluationService briefingEvaluationService,
            TravelDayService travelDayService,
            @Value("${photocast.best-bet.max-tokens:" + DEFAULT_MAX_TOKENS + "}") int maxTokens,
            java.time.Clock clock) {
        this.anthropicApiClient = anthropicApiClient;
        this.objectMapper = objectMapper;
        this.jobRunService = jobRunService;
        this.modelSelectionService = modelSelectionService;
        this.auroraStateCache = auroraStateCache;
        this.stabilitySnapshotProvider = stabilitySnapshotProvider;
        this.briefingEvaluationService = briefingEvaluationService;
        this.travelDayService = travelDayService;
        this.maxTokens = maxTokens;
        this.clock = clock;
        this.auroraRegionSelector = new AuroraRegionSelector(auroraStateCache);
        this.rollupBuilder = new BriefingRollupBuilder(objectMapper, clock, travelDayService,
                briefingEvaluationService, stabilitySnapshotProvider, auroraStateCache,
                auroraRegionSelector);
        this.enricher = new BestBetEnricher(clock);
    }

    /**
     * Returns the human-readable name of the currently configured model (e.g. "Opus").
     *
     * @return display name of the active briefing model
     */
    public String getModelDisplayName() {
        EvaluationModel model = modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET);
        return model.name().charAt(0) + model.name().substring(1).toLowerCase();
    }

    /**
     * Returns the advisor's current live system prompt, so the replay harness can run a captured
     * or synthetic rollup through the production prompt as the "before" side of a before/after
     * comparison against a candidate prompt. Reading it here is cleaner than the reflection the
     * prompt-regression test uses.
     *
     * @return the current {@code SYSTEM_PROMPT}
     */
    public String currentSystemPrompt() {
        return BestBetPromptText.systemPrompt();
    }

    /**
     * Produces Claude-generated best-bet picks from the post-triage region rollup data,
     * carrying an explicit {@link BestBetStatus} so callers can tell an honest empty result
     * apart from a failure.
     *
     * <p>The status REPORTS which internal path was taken; it never changes the selection or
     * ranking. {@link BestBetStatus#SUCCESS_WITH_PICKS} when usable picks survived (including
     * picks salvaged from a truncated response); {@link BestBetStatus#SUCCESS_NO_PICKS} when
     * the advisor honestly returned an empty pick set; {@link BestBetStatus#FAILED} on an
     * exception, an unparseable response with nothing salvageable, or when every parsed pick
     * was rejected by validation (nothing usable came back). The briefing always loads
     * regardless — the frontend switches on the status.
     *
     * @param days      the fully assembled briefing days (triage complete)
     * @param jobRunId  the current briefing job run ID for API call logging
     * @param driveMap  unused — retained for API compatibility (pass {@code Map.of()})
     * @return the advisor outcome (status + picks)
     */
    public BestBetResult advise(List<BriefingDay> days, Long jobRunId,
            Map<String, Integer> driveMap) {
        try {
            EvaluationModel model = modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET);
            boolean useExtendedThinking = modelSelectionService.isExtendedThinking(RunType.BRIEFING_BEST_BET)
                    && model != EvaluationModel.HAIKU;
            LocalDateTime now = LocalDateTime.now(clock);
            RollupResult rollup = rollupBuilder.buildRollupJson(days, now);
            logPickTwoEligibility(rollup.coverageByKey(), jobRunId);
            long startMs = System.currentTimeMillis();

            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .model(model.getModelId())
                    .maxTokens(useExtendedThinking ? MAX_TOKENS_THINKING : maxTokens)
                    .systemOfTextBlockParams(List.of(
                            TextBlockParam.builder().text(BestBetPromptText.systemPrompt()).build()))
                    .addUserMessage(rollup.json());
            if (useExtendedThinking) {
                paramsBuilder.thinking(ThinkingConfigAdaptive.builder().build());
            }

            Message response = anthropicApiClient.createMessage(paramsBuilder.build());

            long durationMs = System.currentTimeMillis() - startMs;
            String raw = extractFirstText(response);
            Optional<StopReason> stopReason = response.stopReason();
            var usage = response.usage();
            TokenUsage tokenUsage = usage == null ? null : new TokenUsage(
                    usage.inputTokens(), usage.outputTokens(),
                    usage.cacheCreationInputTokens().orElse(0L),
                    usage.cacheReadInputTokens().orElse(0L));

            LOG.info("Best-bet advisor completed ({}ms, model={}, stopReason={})",
                    durationMs, model, stopReason.map(Object::toString).orElse("unknown"));
            // Capture the exact rollup input (request body) alongside the response so the
            // advisor replay harness can re-feed any live cycle's input through a swapped
            // prompt for before/after validation — previously this was logged as null. The
            // token usage is passed too so the call records its real cost, not £0.
            jobRunService.logApiCall(jobRunId, ServiceName.ANTHROPIC,
                    "POST", "briefing-best-bet", rollup.json(),
                    durationMs, 200, raw, true, null,
                    model, tokenUsage);

            BestBetResult parsed = classifyAndParse(raw);
            logResponseDisposition(stopReason, parsed.picks().size(), raw.length(), jobRunId);
            if (parsed.status() != BestBetStatus.SUCCESS_WITH_PICKS) {
                // SUCCESS_NO_PICKS (honest decline) or FAILED — nothing to validate/enrich.
                return parsed;
            }
            List<BestBet> validated = validateAndFilterPicks(
                    parsed.picks(), rollup.validEvents(), rollup.validRegions(), rollup.validDayNames());
            if (validated.isEmpty()) {
                // Picks parsed but none named a valid event/region — nothing usable came back.
                LOG.warn("Best-bet advisor parsed picks but all failed validation — FAILED "
                        + "(jobRunId={})", jobRunId);
                return BestBetResult.failed();
            }
            List<BestBet> evidenced = dropUnevaluatedPicks(validated, rollup.coverageByKey());
            if (evidenced.isEmpty()) {
                // Valid picks came back, but none carry any Claude colour rating. A weather GO
                // count alone is not evidence of a good sky, so we decline rather than dress up a
                // guess as a recommendation — this is the honest "nothing evaluated" state.
                LOG.info("Best-bet advisor: no pick carries a colour evaluation — declining with "
                        + "no-picks (jobRunId={})", jobRunId);
                return BestBetResult.noPicks();
            }
            List<BestBet> covered = applyCoverageAwareRanking(evidenced, rollup.coverageByKey());
            List<BestBet> enriched = enricher.enrichWithEventData(covered, days);
            if (enriched.isEmpty()) {
                // Picks parsed but none survived enrichment — nothing usable came back.
                LOG.warn("Best-bet advisor parsed picks but none survived enrichment — FAILED "
                        + "(jobRunId={})", jobRunId);
                return BestBetResult.failed();
            }
            return BestBetResult.withPicks(enriched);
        } catch (Exception e) {
            LOG.warn("Best-bet advisor failed — returning FAILED status (fallback to headline)", e);
            return BestBetResult.failed();
        }
    }

    /**
     * Logs, once per cycle, whether any region could have supported a Pick 2 ("Also Good").
     *
     * <p>Pick 2 carries a quality floor that Pick 1 does not: both tiers of the advisor's
     * selection rule require a region rated at least {@link #PICK_TWO_RATING_FLOOR}, and the
     * prompt instructs the model to stay silent rather than pad the recommendation when nothing
     * clears it. A briefing then shows a Best Bet and no Also Good — from the outside,
     * indistinguishable from a silent failure. This line makes "the week was simply flat" legible
     * in the log, so diagnosing a missing Also Good needs no database query.
     *
     * <p>Reports the best rating available, how many slots clear the floor, and the top few
     * candidates by rating. Slots with no colour rating at all are excluded from the ranking —
     * they can never support a pick (see {@link BestBetRanker#dropUnevaluatedPicks}).
     *
     * @param coverage per-{@code event|region} Claude coverage from the rollup
     * @param jobRunId the briefing job run id — correlates to the {@code api_call_log} row
     */
    private void logPickTwoEligibility(Map<String, CandidateCoverage> coverage, Long jobRunId) {
        List<Map.Entry<String, CandidateCoverage>> rated = coverage.entrySet().stream()
                .filter(e -> e.getValue().claudeRatedCount() > 0)
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<String, CandidateCoverage> e) -> e.getValue().claudeAverageRating())
                        .reversed())
                .toList();
        if (rated.isEmpty()) {
            LOG.info("[BEST-BET PICK2] No region-event slot carries a colour rating this cycle — "
                    + "no Also Good is possible (jobRunId={})", jobRunId);
            return;
        }
        long clearing = rated.stream()
                .filter(e -> e.getValue().claudeAverageRating() >= PICK_TWO_RATING_FLOOR)
                .count();
        String top = rated.stream()
                .limit(PICK_TWO_LOG_TOP_N)
                .map(e -> String.format("%s=%.2f(n=%d)", e.getKey(),
                        e.getValue().claudeAverageRating(), e.getValue().claudeRatedCount()))
                .collect(Collectors.joining(", "));
        LOG.info("[BEST-BET PICK2] Best available rating {} across {} rated slot(s); {} clear the "
                        + "{} Also Good floor{}. Top: {} (jobRunId={})",
                String.format("%.2f", rated.get(0).getValue().claudeAverageRating()),
                rated.size(), clearing, PICK_TWO_RATING_FLOOR,
                clearing == 0 ? " — no Also Good is possible" : "",
                top, jobRunId);
    }

    /**
     * Emits a single classifiable disposition log so a truncated advisor response can never
     * again hide as an honest empty-pick result — the camouflage that let this bug persist.
     *
     * <p>Distinguishes the three cases the forensic dig had to separate by hand:
     * <ul>
     *   <li><b>(a) honest zero</b> — the model returned valid JSON with no picks; logged at INFO.</li>
     *   <li><b>(b) truncation</b> — the response stopped on the token limit and nothing survived;
     *       logged at WARN with the correlating {@code jobRunId} (the api_call_log key) and the
     *       remediation hint.</li>
     *   <li><b>(c) salvage</b> — the response was token-limited but valid leading pick(s) were
     *       recovered; logged at WARN noting how many survived.</li>
     * </ul>
     *
     * @param stopReason    the SDK-reported stop reason (max_tokens drives truncation detection)
     * @param pickCount     number of picks parsed (after any salvage)
     * @param responseChars length of the raw response text
     * @param jobRunId      the briefing job run id — correlates to the {@code api_call_log} row
     */
    private void logResponseDisposition(Optional<StopReason> stopReason, int pickCount,
            int responseChars, Long jobRunId) {
        boolean tokenLimited = stopReason
                .filter(sr -> sr.equals(StopReason.MAX_TOKENS)).isPresent();
        if (tokenLimited) {
            if (pickCount > 0) {
                LOG.warn("[BEST-BET TRUNCATION] Advisor response stopped on the token limit "
                        + "(stopReason=max_tokens, responseChars={}, jobRunId={}, maxTokens={}) "
                        + "but {} valid pick(s) were salvaged. Raise photocast.best-bet.max-tokens "
                        + "if this recurs.", responseChars, jobRunId, maxTokens, pickCount);
            } else {
                LOG.warn("[BEST-BET TRUNCATION] Advisor response stopped on the token limit "
                        + "(stopReason=max_tokens, responseChars={}, jobRunId={}, maxTokens={}) "
                        + "and no picks could be salvaged — the Planner falls back to the "
                        + "mechanical headline. Raise photocast.best-bet.max-tokens.",
                        responseChars, jobRunId, maxTokens);
            }
            return;
        }
        if (pickCount == 0) {
            LOG.info("[BEST-BET] Advisor returned no picks — honest decline "
                    + "(stopReason={}, responseChars={}, jobRunId={})",
                    stopReason.map(Object::toString).orElse("unknown"), responseChars, jobRunId);
        }
    }

    /**
     * Validates picks against the known-good event IDs, region names, and day names,
     * discards any invalid picks, and re-ranks the survivors.
     *
     * <p>A pick is invalid if its {@code event} is not in {@code validEvents} (unless null),
     * its {@code region} is not in {@code validRegions} (unless null or aurora event), or
     * its narrative text references a day name outside the forecast window.
     * If all picks fail validation the list is empty and the caller falls back to the
     * mechanical headline.
     *
     * @param picks        parsed picks from Claude
     * @param validEvents  event IDs present in the rollup input
     * @param validRegions region names present in the rollup input
     * @param validDayNames day names present in the forecast window
     * @return validated, re-ranked list (may be empty)
     */
    List<BestBet> validateAndFilterPicks(List<BestBet> picks,
            Set<String> validEvents, Set<String> validRegions, Set<String> validDayNames) {
        return BestBetPickValidator.validateAndFilterPicks(
                picks, validEvents, validRegions, validDayNames);
    }

    /**
     * Enforces the headline coverage floor: a region cannot hold rank 1 on cheap
     * GO-count merit when only a couple of its locations were actually
     * Claude-evaluated and a better-covered alternative pick is available.
     *
     * <p>Extends the principle behind {@code BriefingHonestyFilter} (which rewrites
     * regions with <em>zero</em> Claude scores on the read path) to the
     * <em>insufficient</em>-coverage case at the crowning decision: the headline
     * must clear {@link BestBetRanker#MIN_HEADLINE_CLAUDE_COVERAGE} when a pick that does clear
     * it exists. The gate is deliberately comparative — it demotes a thin headline
     * only by promoting a genuinely better-evidenced pick. When no pick clears the
     * floor the order is left untouched (thin coverage is then the best evidence
     * available; the targeted force-evaluation path is what raises headline
     * contenders above the floor in the first place).
     *
     * <p>Stay-home picks and aurora picks are exempt — a stay-home pick crowns
     * nothing and aurora has its own clear-sky gate in the prompt.
     *
     * <p>When a promotion happens the new headline's relationship/differsBy are
     * cleared (rank 1 carries neither) and the trailing picks' relationship fields
     * are recomputed relative to the new headline so they stay coherent.
     *
     * @param picks    validated picks in Claude's ranked order
     * @param coverage per-{@code event|region} Claude coverage from the rollup
     * @return the picks, possibly reordered so a covered pick holds the headline
     */
    List<BestBet> applyCoverageAwareRanking(List<BestBet> picks,
            Map<String, CandidateCoverage> coverage) {
        return BestBetRanker.applyCoverageAwareRanking(picks, coverage);
    }

    /**
     * Drops picks with zero Claude colour coverage. A best bet's entire premise is Claude's colour
     * evaluation; a region/event with no colour rating at all — only a weather GO count — is not
     * evidence of a good sky, so recommending it (even hedged) is dishonest. Stay-home and aurora
     * picks are exempt. Survivors are renumbered so the highest remaining
     * pick holds rank 1; an empty result signals "no colour-backed recommendation available", which
     * the caller maps to {@code SUCCESS_NO_PICKS} (an honest decline), never {@code FAILED}.
     *
     * @param picks    validated picks in ranked order
     * @param coverage per-{@code event|region} Claude coverage from the rollup
     * @return the picks that carry colour evidence, renumbered; possibly empty
     */
    List<BestBet> dropUnevaluatedPicks(List<BestBet> picks,
            Map<String, CandidateCoverage> coverage) {
        return BestBetRanker.dropUnevaluatedPicks(picks, coverage);
    }

    /**
     * Builds the region-level rollup JSON sent to Claude as the user message, plus the
     * validation sets derived from the same data.
     *
     * <p>Thin delegator to {@link BriefingRollupBuilder#buildRollupJson}; retained so callers
     * (and tests) that only need the rollup can reach it through the advisor.
     *
     * @param days     the briefing days
     * @param now      current UTC time for past-event filtering
     * @return rollup result containing the JSON and validation sets
     * @throws JsonProcessingException if Jackson serialization fails
     */
    RollupResult buildRollupJson(List<BriefingDay> days, LocalDateTime now)
            throws JsonProcessingException {
        return rollupBuilder.buildRollupJson(days, now);
    }

    /**
     * Result of evaluating the briefing rollup with a single Claude model.
     *
     * @param model           the model used
     * @param rawResponse     raw text from Claude, or null on failure
     * @param parsedPicks     picks before validation (empty on failure)
     * @param validatedPicks  picks after validation (empty on failure)
     * @param durationMs      API call duration in milliseconds
     * @param tokenUsage      token counts from the API response
     * @param thinkingText    raw extended thinking chain text, or null for non-ET variants
     */
    public record ModelComparisonResult(EvaluationModel model, String rawResponse,
            List<BestBet> parsedPicks, List<BestBet> validatedPicks,
            long durationMs, TokenUsage tokenUsage, String thinkingText) {
    }

    /**
     * Aggregated result of a multi-model comparison run.
     *
     * @param rollupJson the JSON sent to all five variants
     * @param results    one result per variant (HAIKU, SONNET, SONNET_ET, OPUS, OPUS_ET)
     */
    public record ComparisonRun(String rollupJson, List<ModelComparisonResult> results) {
    }

    /**
     * Calls all five variants (Haiku, Sonnet, Sonnet+ET, Opus, Opus+ET) sequentially with
     * the same briefing rollup and returns the parsed, validated picks for each.
     *
     * <p>Variants run sequentially (not in parallel) to stay within the Claude bulkhead
     * concurrency cap. Per-variant failures are caught and returned as failed results with
     * null rawResponse, so partial success is possible.
     *
     * @param days     the fully assembled briefing days (triage complete)
     * @param driveMap unused — retained for API compatibility (pass {@code Map.of()})
     * @return comparison run containing the rollup JSON and all variant results
     * @throws com.fasterxml.jackson.core.JsonProcessingException if rollup JSON build fails
     */
    public ComparisonRun compareModels(List<BriefingDay> days,
            Map<String, Integer> driveMap) throws com.fasterxml.jackson.core.JsonProcessingException {
        LocalDateTime now = LocalDateTime.now(clock);
        RollupResult rollup = rollupBuilder.buildRollupJson(days, now);
        List<EvaluationModel> models = List.of(
                EvaluationModel.HAIKU, EvaluationModel.SONNET, EvaluationModel.SONNET_ET,
                EvaluationModel.OPUS, EvaluationModel.OPUS_ET);

        List<ModelComparisonResult> results = new ArrayList<>();
        for (EvaluationModel model : models) {
            results.add(callModel(model, rollup, days));
        }
        return new ComparisonRun(rollup.json(), results);
    }

    private ModelComparisonResult callModel(EvaluationModel model, RollupResult rollup,
            List<BriefingDay> days) {
        try {
            boolean extendedThinking = model.isExtendedThinking();
            long startMs = System.currentTimeMillis();

            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(model.getModelId())
                    .maxTokens(extendedThinking ? MAX_TOKENS_THINKING : maxTokens)
                    .systemOfTextBlockParams(List.of(
                            TextBlockParam.builder().text(BestBetPromptText.systemPrompt()).build()))
                    .addUserMessage(rollup.json());

            if (extendedThinking) {
                builder.thinking(ThinkingConfigAdaptive.builder().build());
            }

            Message response = anthropicApiClient.createMessage(builder.build());
            long durationMs = System.currentTimeMillis() - startMs;

            // Text blocks only — thinking blocks are filtered out here
            String raw = extractFirstText(response);

            // Extract thinking chain text (null for non-ET variants)
            String thinkingText = response.content().stream()
                    .filter(ContentBlock::isThinking)
                    .map(ContentBlock::asThinking)
                    .map(ThinkingBlock::thinking)
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst()
                    .orElse(null);

            TokenUsage tokenUsage = new TokenUsage(
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    response.usage().cacheCreationInputTokens().orElse(0L),
                    response.usage().cacheReadInputTokens().orElse(0L));

            List<BestBet> parsed = parseBestBets(raw);
            List<BestBet> validated = validateAndFilterPicks(
                    parsed, rollup.validEvents(), rollup.validRegions(), rollup.validDayNames());
            List<BestBet> covered = applyCoverageAwareRanking(validated, rollup.coverageByKey());
            List<BestBet> enriched = enricher.enrichWithEventData(covered, days);

            LOG.info("Model comparison {} completed ({}ms, {} picks, thinking={})",
                    model, durationMs, enriched.size(), thinkingText != null);
            return new ModelComparisonResult(model, raw, parsed, enriched, durationMs, tokenUsage, thinkingText);
        } catch (Exception e) {
            LOG.warn("Model comparison {} failed: {}", model, e.getMessage());
            return new ModelComparisonResult(model, null, List.of(), List.of(), 0, TokenUsage.EMPTY, null);
        }
    }

    /**
     * Extracts the first text block from a Claude response, or {@code ""} when none is present.
     * Shared by {@link #advise}, {@link #callModel} and {@link #replayWithPrompt} so every path
     * reads the response identically.
     *
     * @param response the Claude message
     * @return the first text block's content, or an empty string
     */
    private static String extractFirstText(Message response) {
        return response.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElse("");
    }

    /**
     * Replays the advisor against a pre-captured or synthetic rollup JSON using an explicitly
     * supplied system prompt, bypassing {@link #buildRollupJson}. This is the before/after
     * validation primitive for advisor prompt changes: feed one stored rollup through two prompt
     * variants and diff the selected picks, or feed a synthetic rollup to assert a contract
     * (e.g. an all-STANDDOWN rollup must yield the stay-home pick) without waiting for a live
     * cycle.
     *
     * <p>The captured input lives in {@code api_call_log.request_body} (see {@link #advise}).
     * The validation sets are reconstructed from the rollup JSON itself, so the parsed picks
     * pass through the same {@link #validateAndFilterPicks} and {@link #applyCoverageAwareRanking}
     * gates production uses — the returned picks are what production would select. Display
     * enrichment (via {@link BestBetEnricher}) is skipped: it needs live {@code BriefingDay}
     * objects and changes neither selection nor ranking.
     *
     * @param rollupJson   the exact user-message rollup JSON (as captured in api_call_log)
     * @param systemPrompt the system prompt to evaluate with (pass {@link BestBetPromptText#systemPrompt()} for
     *                     the baseline before-state)
     * @param model        the model to call
     * @return the classified outcome (status + validated, ranked picks)
     * @throws JsonProcessingException if the rollup JSON cannot be parsed
     */
    public BestBetResult replayWithPrompt(String rollupJson, String systemPrompt, EvaluationModel model)
            throws JsonProcessingException {
        BriefingRollupBuilder.ReconstructedRollup sets = rollupBuilder.reconstructRollup(rollupJson);

        boolean extendedThinking = model.isExtendedThinking();
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model.getModelId())
                .maxTokens(extendedThinking ? MAX_TOKENS_THINKING : maxTokens)
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder().text(systemPrompt).build()))
                .addUserMessage(rollupJson);
        if (extendedThinking) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        }

        Message response = anthropicApiClient.createMessage(builder.build());
        String raw = extractFirstText(response);

        BestBetResult parsed = classifyAndParse(raw);
        if (parsed.status() != BestBetStatus.SUCCESS_WITH_PICKS) {
            return parsed;
        }
        List<BestBet> validated = validateAndFilterPicks(
                parsed.picks(), sets.validEvents(), sets.validRegions(), sets.validDayNames());
        List<BestBet> covered = applyCoverageAwareRanking(validated, sets.coverageByKey());
        if (covered.isEmpty()) {
            return BestBetResult.failed();
        }
        return BestBetResult.withPicks(covered);
    }

    /**
     * Parses the Claude JSON response into a list of {@link BestBet} records.
     *
     * <p>Thin convenience wrapper over {@link #classifyAndParse(String)} that discards the
     * outcome status. Retained for callers that only need the picks (e.g. the model-comparison
     * utility); {@link #advise} uses {@code classifyAndParse} directly so it can report the
     * status.
     *
     * @param raw the raw Claude response text
     * @return parsed picks, or empty list if parsing fails
     */
    List<BestBet> parseBestBets(String raw) {
        return BestBetResponseParser.parseBestBets(raw, objectMapper);
    }

    /**
     * Parses the Claude JSON response and classifies the outcome into a {@link BestBetResult}.
     *
     * <p>Mapping:
     * <ul>
     *   <li>blank/missing content, a missing or non-array {@code picks} field, or an
     *       unparseable response from which nothing could be salvaged → {@code FAILED};</li>
     *   <li>a valid but empty {@code picks} array → {@code SUCCESS_NO_PICKS} (honest decline);</li>
     *   <li>one or more parsed picks, including picks salvaged from a truncated response →
     *       {@code SUCCESS_WITH_PICKS}.</li>
     * </ul>
     *
     * @param raw the raw Claude response text
     * @return the parse outcome (status + picks)
     */
    BestBetResult classifyAndParse(String raw) {
        return BestBetResponseParser.classifyAndParse(raw, objectMapper);
    }
}
