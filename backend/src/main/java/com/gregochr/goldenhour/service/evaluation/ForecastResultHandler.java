package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BluebellEvaluation;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideContext;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.ForecastDataAugmentor;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.evaluation.visitor.RatingCombiner;
import com.gregochr.goldenhour.service.evaluation.visitor.VisitorContext;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ResultHandler} for forecast colour evaluations.
 *
 * <p>Owns the parsing + persistence pipeline for both Anthropic transports:
 * <ul>
 *   <li>{@link #parseBatchResponse} — invoked once per individual response by
 *       {@link com.gregochr.goldenhour.service.batch.BatchResultProcessor}; writes
 *       {@code api_call_log} and returns the parsed result so the orchestrator can
 *       aggregate by cache key before the single per-batch
 *       {@code briefingEvaluationService.writeFromBatch} call.</li>
 *   <li>{@link #handleSyncResult} — invoked by {@link EvaluationServiceImpl#evaluateNow}
 *       for forecast tasks; writes {@code api_call_log} (with {@code is_batch=false})
 *       unconditionally, and writes {@code cached_evaluation} only when the task's
 *       {@link EvaluationTask.Forecast#writeTarget()} is
 *       {@link EvaluationTask.Forecast.WriteTarget#BRIEFING_CACHE}. Tasks with
 *       {@link EvaluationTask.Forecast.WriteTarget#NONE} skip the cache write so the
 *       caller can manage its own persistence (e.g. {@code forecast_evaluation}).</li>
 * </ul>
 *
 * <p>Wraps the same {@link RatingValidator} call and the same
 * {@link ClaudeEvaluationStrategy#parseEvaluation} logic that
 * {@code BatchResultProcessor} used inline before Pass 3.2. The integration test
 * pyramid (sub-package {@code integration}) is the contract that proves the writes
 * remain byte-identical.
 */
@Component
public class ForecastResultHandler implements ResultHandler<EvaluationTask.Forecast> {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastResultHandler.class);

    /**
     * {@code error_type} marker stamped on the {@code api_call_log} row when the JSON regex
     * fallback was used (Bug B). Makes the silent over-capture successes findable on the admin
     * Job Run screen even though {@code succeeded} is {@code true}.
     */
    public static final String REGEX_FALLBACK_MARKER = "regex_fallback";

    /**
     * Rating substituted when Claude returned a parseable response but omitted the sky rating
     * ("sky not forecast"). A low default that drops the location in ranking but keeps it visible
     * in the result set rather than silently dropping it (the old {@code null} behaviour).
     */
    static final int SKY_NOT_FORECAST_RATING = 1;

    /**
     * Summary substituted for the sky-not-forecast state, overriding any prose Claude returned.
     * Distinct from the normal low-score "poor sky" wording — this is "evaluated but unscoreable".
     */
    static final String SKY_NOT_FORECAST_SUMMARY =
            "Claude did not forecast the fiery sky and golden hour for this location";

    private final BriefingEvaluationService briefingEvaluationService;
    private final ClaudeEvaluationStrategy parsingStrategy;
    private final JobRunService jobRunService;
    private final ObjectMapper objectMapper;
    private final RatingCombiner ratingCombiner;
    private final ForecastDataAugmentor forecastDataAugmentor;
    private final ForecastScoreWriter forecastScoreWriter;

    /**
     * Constructs the handler.
     *
     * <p>Pulls a {@link ClaudeEvaluationStrategy} (any concrete model — HAIKU is the
     * cheapest to instantiate) out of the strategy map only to reuse its
     * {@link ClaudeEvaluationStrategy#parseEvaluation} method. No Claude calls are made
     * through this strategy; it is a parser handle.
     *
     * @param briefingEvaluationService cache writer (for both sync and end-of-batch flushes)
     * @param evaluationStrategies      the strategy bean map; only HAIKU's parser is used
     * @param jobRunService             writer of {@code api_call_log} rows
     * @param objectMapper              Jackson mapper threaded through to the parser
     * @param ratingCombiner            v2.13 visitor combiner — derives the persisted star
     *                                  rating from the parsed evaluation. This is the single
     *                                  result seam both transports (batch + sync) flow through.
     * @param forecastDataAugmentor     re-derives the tide context (option B) at the combine seam
     *                                  for coastal locations, so the {@code TideVisitor} can score
     *                                  the tide separately from the sky
     * @param forecastScoreWriter       Pass 2 dual-write: persists the combiner's component scores
     *                                  to {@code forecast_score} alongside the serving path,
     *                                  isolated so its failure never fails the evaluation
     */
    public ForecastResultHandler(BriefingEvaluationService briefingEvaluationService,
            Map<EvaluationModel, EvaluationStrategy> evaluationStrategies,
            JobRunService jobRunService,
            ObjectMapper objectMapper,
            RatingCombiner ratingCombiner,
            ForecastDataAugmentor forecastDataAugmentor,
            ForecastScoreWriter forecastScoreWriter) {
        this.briefingEvaluationService = briefingEvaluationService;
        EvaluationStrategy strategy = evaluationStrategies.get(EvaluationModel.HAIKU);
        if (!(strategy instanceof ClaudeEvaluationStrategy claude)) {
            throw new IllegalStateException(
                    "ForecastResultHandler requires a ClaudeEvaluationStrategy bean for HAIKU "
                            + "to reuse its JSON parser; got " + strategy);
        }
        this.parsingStrategy = claude;
        this.jobRunService = jobRunService;
        this.objectMapper = objectMapper;
        this.ratingCombiner = ratingCombiner;
        this.forecastDataAugmentor = forecastDataAugmentor;
        this.forecastScoreWriter = forecastScoreWriter;
    }

    @Override
    public Class<EvaluationTask.Forecast> taskType() {
        return EvaluationTask.Forecast.class;
    }

    /**
     * Resolved per-batch-response payload returned by
     * {@link #parseBatchResponse}: carries the cache key the result belongs to and
     * the {@link BriefingEvaluationResult} to write into it.
     *
     * @param cacheKey {@code "regionName|date|targetType"} — the cache key for grouping
     * @param result   parsed evaluation
     */
    public record BatchSuccess(String cacheKey, BriefingEvaluationResult result) {
    }

    /**
     * Lightweight identity tuple equivalent to {@link ParsedCustomId.Forecast},
     * {@link ParsedCustomId.Jfdi}, and {@link ParsedCustomId.ForceSubmit}'s shared
     * fields. Decouples this handler from the dispatch-side custom-id taxonomy so the
     * handler does not need to know which prefix produced this result.
     *
     * @param locationId location id from the custom id
     * @param date       evaluation date
     * @param targetType SUNRISE / SUNSET / HOURLY
     */
    public record ForecastIdentity(Long locationId, LocalDate date, TargetType targetType) {
    }

    /**
     * Parses one batch response and writes its {@code api_call_log} row.
     *
     * <p>Returns the parsed {@link BatchSuccess} on success; returns {@link Optional#empty}
     * on failure (after logging the error and writing a failure {@code api_call_log} row).
     * The orchestrator collects successes and calls {@link #flushCacheKey} once per cache
     * key after the loop.
     *
     * @param location the resolved {@link LocationEntity} (orchestrator already
     *                 looked it up from the parsed custom id)
     * @param parsed   the parsed forecast identity
     * @param outcome  drained-of-SDK-types view of one Anthropic batch response
     * @param context  per-batch observability context (jobRunId, batchId)
     * @return the parsed success payload, or empty if the response failed
     */
    public Optional<BatchSuccess> parseBatchResponse(LocationEntity location,
            ForecastIdentity parsed, ClaudeBatchOutcome outcome, ResultContext context) {

        String regionName = location.getRegion() != null
                ? location.getRegion().getName() : location.getName();
        String cacheKey = CacheKeyFactory.build(regionName, parsed.date(), parsed.targetType());

        if (!outcome.succeeded()) {
            persistBatchLog(context, outcome, parsed.date(), parsed.targetType(), null);
            return Optional.empty();
        }

        try {
            ClaudeEvaluationStrategy.ParseResult parsed0 =
                    parsingStrategy.parseEvaluationWithMetadata(outcome.rawText(), objectMapper);
            SunsetEvaluation eval = parsed0.evaluation();
            String modelName = outcome.model() != null ? outcome.model().name() : "UNKNOWN";
            BriefingEvaluationResult result = buildResult(
                    location, eval, parsed.date(), parsed.targetType(), regionName, modelName,
                    context != null ? context.pipelineRunId() : null);

            if (parsed0.usedRegexFallback()) {
                // Strict JSON parse failed and the regex fallback recovered the result (possibly
                // over-capturing — the Bug B trigger). This is a silent success, so persist the raw
                // response and mark it findable for the admin Job Run screen / a real fixture.
                persistBatchLog(context, outcome, parsed.date(), parsed.targetType(),
                        outcome.model(), REGEX_FALLBACK_MARKER, outcome.rawText());
            } else {
                persistBatchLog(context, outcome, parsed.date(), parsed.targetType(),
                        outcome.model());
            }
            return Optional.of(new BatchSuccess(cacheKey, result));
        } catch (Exception e) {
            LOG.warn("Forecast batch: parse failed for '{}': {}", outcome.customId(), e.getMessage());
            LOG.warn("Forecast batch: raw response for '{}': {}",
                    outcome.customId(), outcome.rawText());
            ClaudeBatchOutcome parseFailure = ClaudeBatchOutcome.failure(
                    outcome.customId(), "PARSE_FAILED", "parse_error", e.getMessage());
            persistBatchLog(context, parseFailure, parsed.date(), parsed.targetType(), null);
            return Optional.empty();
        }
    }

    /**
     * Parses one bluebell mini-batch response and writes its {@code api_call_log} row.
     *
     * <p>The bluebell sibling of {@link #parseBatchResponse}: the response was produced by the
     * dedicated bluebell prompt (custom id {@code bb-...}), so it is parsed via
     * {@link ClaudeEvaluationStrategy#parseBluebellEvaluation} and combined through the
     * {@link RatingCombiner} with a bluebell-only {@link VisitorContext} (no sky evaluation). For
     * an in-season WOODLAND site the combiner's exposure rule makes the bluebell score the rating;
     * the dual-write persists ONLY the BLUEBELL component (no FIERY_SKY / GOLDEN_HOUR rows).
     *
     * <p>Returns the parsed {@link BatchSuccess} on success; {@link Optional#empty} on failure
     * (after logging and writing a failure {@code api_call_log} row). The orchestrator MERGES the
     * bluebell successes into the region cache entry (never replaces it), so the region's sky
     * locations are preserved.
     *
     * @param location the resolved {@link LocationEntity}
     * @param parsed   the parsed forecast identity (from the {@code bb-} custom id)
     * @param outcome  drained-of-SDK-types view of one Anthropic batch response
     * @param context  per-batch observability context (jobRunId, batchId)
     * @return the parsed success payload, or empty if the response failed
     */
    public Optional<BatchSuccess> parseBluebellBatchResponse(LocationEntity location,
            ForecastIdentity parsed, ClaudeBatchOutcome outcome, ResultContext context) {

        String regionName = location.getRegion() != null
                ? location.getRegion().getName() : location.getName();
        String cacheKey = CacheKeyFactory.build(regionName, parsed.date(), parsed.targetType());

        if (!outcome.succeeded()) {
            persistBatchLog(context, outcome, parsed.date(), parsed.targetType(), null);
            return Optional.empty();
        }

        try {
            BluebellEvaluation bluebell =
                    parsingStrategy.parseBluebellEvaluation(outcome.rawText(), objectMapper);
            if (bluebell.rating() == null) {
                throw new IllegalStateException("bluebell response omitted the rating");
            }
            String modelName = outcome.model() != null ? outcome.model().name() : "UNKNOWN";
            BriefingEvaluationResult result = buildBluebellResult(
                    location, bluebell, parsed.date(), parsed.targetType(), regionName, modelName,
                    context != null ? context.pipelineRunId() : null);
            persistBatchLog(context, outcome, parsed.date(), parsed.targetType(), outcome.model());
            return Optional.of(new BatchSuccess(cacheKey, result));
        } catch (Exception e) {
            LOG.warn("Bluebell batch: parse failed for '{}': {}",
                    outcome.customId(), e.getMessage());
            LOG.warn("Bluebell batch: raw response for '{}': {}",
                    outcome.customId(), outcome.rawText());
            ClaudeBatchOutcome parseFailure = ClaudeBatchOutcome.failure(
                    outcome.customId(), "PARSE_FAILED", "parse_error", e.getMessage());
            persistBatchLog(context, parseFailure, parsed.date(), parsed.targetType(), null);
            return Optional.empty();
        }
    }

    /**
     * Writes a finalised group of batch results to {@code cached_evaluation} via the
     * existing {@link BriefingEvaluationService#writeFromBatch} entry point. Called
     * once per cache key after the orchestrator finishes streaming.
     *
     * @param cacheKey region cache key
     * @param results  all locations in that cache key for this batch
     */
    public void flushCacheKey(String cacheKey, List<BriefingEvaluationResult> results) {
        briefingEvaluationService.writeFromBatch(cacheKey, results);
    }

    /**
     * Writes a group of RETRY_FAILED-phase batch results by <em>merging</em> them into
     * the existing cache entry rather than replacing it.
     *
     * <p>A retry batch carries only the locations that failed in the precursor batch.
     * Routing those through {@link #flushCacheKey} would replace the region's entry and
     * lose the locations that originally succeeded. {@link #mergeCacheKey} overlays the
     * recovered locations onto the prior entry, preserving the rest. The processor
     * selects this path when the batch is flagged {@code is_retry}.
     *
     * @param cacheKey region cache key
     * @param results  the recovered locations for that cache key
     */
    public void mergeCacheKey(String cacheKey, List<BriefingEvaluationResult> results) {
        briefingEvaluationService.mergeFromBatch(cacheKey, results);
    }

    /**
     * Merges a group of bluebell mini-batch results into the region cache entry, recombining the
     * rating with a prior sky result for OPEN_FELL sites (C3b). Delegates to
     * {@link BriefingEvaluationService#mergeBluebellFromBatch}; the processor selects this path
     * for {@code bb-} responses.
     *
     * @param cacheKey region cache key
     * @param results  the bluebell results for that cache key
     */
    public void mergeBluebellCacheKey(String cacheKey, List<BriefingEvaluationResult> results) {
        briefingEvaluationService.mergeBluebellFromBatch(cacheKey, results);
    }

    @Override
    public EvaluationResult handleSyncResult(EvaluationTask.Forecast task,
            ClaudeSyncOutcome outcome, ResultContext context) {

        String regionName = task.location().getRegion() != null
                ? task.location().getRegion().getName() : task.location().getName();

        if (!outcome.succeeded()) {
            persistSyncLog(context, outcome, task);
            return new EvaluationResult.Errored(
                    outcome.errorType() != null ? outcome.errorType() : "unknown",
                    outcome.errorMessage());
        }

        SunsetEvaluation eval;
        try {
            eval = parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper);
        } catch (Exception e) {
            LOG.warn("Forecast sync evaluation: parse failed for {}: {}",
                    task.taskKey(), e.getMessage());
            ClaudeSyncOutcome parseFailure = ClaudeSyncOutcome.failure(
                    "parse_error", e.getMessage(), task.model(), outcome.durationMs());
            persistSyncLog(context, parseFailure, task);
            return new EvaluationResult.Errored("parse_error", e.getMessage());
        }

        BriefingEvaluationResult result = buildResult(
                task.location(), eval, task.date(), task.targetType(),
                regionName, task.model().name(),
                context != null ? context.pipelineRunId() : null);

        persistSyncLog(context, outcome, task);
        if (task.writeTarget() == EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE) {
            String cacheKey = CacheKeyFactory.build(regionName, task.date(), task.targetType());
            briefingEvaluationService.writeFromBatch(cacheKey, List.of(result));
        }

        // Carry the result's rating into the payload so forecast_evaluation (written by
        // ForecastService from this Scored result) stays consistent with the cache/briefing
        // surface — including the sky-not-forecast 1★ substitution handled in buildResult.
        return new EvaluationResult.Scored(eval.withRating(result.rating()));
    }

    /**
     * Builds the {@link BriefingEvaluationResult} for a successfully-parsed evaluation, shared by
     * both transports so batch and sync behave identically.
     *
     * <p>Two cases:
     * <ul>
     *   <li><b>Sky not forecast</b> ({@code eval.rating() == null}): a parseable response that
     *       omitted the rating. Substitute {@link #SKY_NOT_FORECAST_RATING} + the
     *       {@link #SKY_NOT_FORECAST_SUMMARY} (overriding Claude's prose) + a null headline +
     *       no triage fields, and do NOT combine. Branching here, before the combine, also
     *       structurally prevents a coastal sky-empty location from being scored on tide alone —
     *       the tide is never averaged.</li>
     *   <li><b>Sky scored:</b> re-derive the tide context (coastal only) and run the combiner over
     *       the sky + tide visitors, then validate.</li>
     * </ul>
     *
     * @param location   the location under evaluation
     * @param eval       the parsed Claude evaluation
     * @param date       the evaluation date
     * @param targetType SUNRISE or SUNSET
     * @param regionName    region name for the rating guardrail log context
     * @param modelName     model id for the rating guardrail log context
     * @param pipelineRunId the orchestrated pipeline run id for forecast_score provenance, or
     *                      {@code null} on the sync/admin path
     * @return the result to persist (cache payload element)
     */
    private BriefingEvaluationResult buildResult(LocationEntity location, SunsetEvaluation eval,
            LocalDate date, TargetType targetType, String regionName, String modelName,
            Long pipelineRunId) {
        if (eval.rating() == null) {
            // Sky not forecast: Claude omitted the rating. The combiner never runs, so there is
            // no genuine component score to record — no forecast_score dual-write here (Pass 2).
            return new BriefingEvaluationResult(
                    location.getName(), SKY_NOT_FORECAST_RATING,
                    eval.fierySkyPotential(), eval.goldenHourPotential(), SKY_NOT_FORECAST_SUMMARY,
                    null, null, null);
        }
        Set<TideType> tideTypes = location.getTideType();
        TideContext tide = (tideTypes != null && !tideTypes.isEmpty())
                ? forecastDataAugmentor.deriveTideContext(location, date, targetType).orElse(null)
                : null;
        RatingCombiner.CombinedRating combined =
                ratingCombiner.combine(location, new VisitorContext(eval, tide));
        Integer safeRating = RatingValidator.validateRating(
                combined.rating(), regionName, date, targetType, location.getName(), modelName);

        dualWriteForecastScore(location, date, targetType, eval, combined, pipelineRunId);

        return new BriefingEvaluationResult(
                location.getName(), safeRating,
                eval.fierySkyPotential(), eval.goldenHourPotential(), eval.summary(),
                null, null, eval.headline());
    }

    /**
     * Builds the {@link BriefingEvaluationResult} for a bluebell-prompt evaluation.
     *
     * <p>Runs the combiner over a bluebell-only {@link VisitorContext} (no sky evaluation): the
     * {@code SkyVisitor} abstains on the null sky slice and the {@code BluebellVisitor} produces
     * the BLUEBELL component, so the combiner's exposure rule yields the rating (the bluebell
     * score for WOODLAND). The dual-write persists ONLY the resulting components — no FIERY_SKY /
     * GOLDEN_HOUR rows, since there is no sky evaluation behind a bluebell-only slot.
     *
     * <p>The serving payload carries no 0–100 potentials (both null); the bluebell summary and
     * headline are the user-facing prose. C3b refines the OPEN_FELL case to recombine the rating
     * with the sky score at the cache-merge step; until then an open-fell bluebell result lands
     * its own bluebell-derived rating (never reached in production out of season).
     */
    private BriefingEvaluationResult buildBluebellResult(LocationEntity location,
            BluebellEvaluation bluebell, LocalDate date, TargetType targetType, String regionName,
            String modelName, Long pipelineRunId) {
        Set<TideType> tideTypes = location.getTideType();
        TideContext tide = (tideTypes != null && !tideTypes.isEmpty())
                ? forecastDataAugmentor.deriveTideContext(location, date, targetType).orElse(null)
                : null;
        RatingCombiner.CombinedRating combined =
                ratingCombiner.combine(location, new VisitorContext(null, tide, bluebell));
        Integer safeRating = RatingValidator.validateRating(
                combined.rating(), regionName, date, targetType, location.getName(), modelName);

        try {
            forecastScoreWriter.writeComponents(
                    location, date, targetType, combined.components(), pipelineRunId);
        } catch (Exception e) {
            LOG.error("forecast_score bluebell dual-write FAILED for component key "
                    + "(location={}, date={}, event={}); evaluation proceeds unaffected: {}",
                    location.getName(), date, targetType, e.getMessage(), e);
        }

        return new BriefingEvaluationResult(
                location.getName(), safeRating, null, null, bluebell.summary(),
                null, null, bluebell.headline());
    }

    /**
     * Pass 2 dual-write seam: persists the combiner's component scores to {@code forecast_score}
     * alongside the serving payload, never instead of it. Isolated so a write failure logs
     * loudly at ERROR (with the component key) and the evaluation proceeds — the serving path is
     * the live product, {@code forecast_score} is the record being proven. The
     * {@code REQUIRES_NEW} boundary inside the writer confines any rollback to the dual-write.
     */
    private void dualWriteForecastScore(LocationEntity location, LocalDate date,
            TargetType targetType, SunsetEvaluation eval, RatingCombiner.CombinedRating combined,
            Long pipelineRunId) {
        try {
            forecastScoreWriter.write(
                    location, date, targetType, eval, combined.components(), pipelineRunId);
        } catch (Exception e) {
            LOG.error("forecast_score dual-write FAILED for component key "
                    + "(location={}, date={}, event={}); evaluation proceeds unaffected: {}",
                    location.getName(), date, targetType, e.getMessage(), e);
        }
    }

    private void persistBatchLog(ResultContext context, ClaudeBatchOutcome outcome,
            LocalDate targetDate, TargetType targetType, EvaluationModel model) {
        persistBatchLog(context, outcome, targetDate, targetType, model, null, null);
    }

    private void persistBatchLog(ResultContext context, ClaudeBatchOutcome outcome,
            LocalDate targetDate, TargetType targetType, EvaluationModel model,
            String errorTypeOverride, String responseBody) {
        if (context == null || context.jobRunId() == null) {
            return;
        }
        try {
            jobRunService.logBatchResult(
                    context.jobRunId(), context.batchId(), outcome.customId(),
                    outcome.succeeded(), outcome.status(),
                    errorTypeOverride != null ? errorTypeOverride : outcome.errorType(),
                    outcome.errorMessage(),
                    model, outcome.tokenUsage(),
                    targetDate, targetType, responseBody);
        } catch (Exception e) {
            LOG.warn("Forecast batch: failed to persist api_call_log for customId={}: {}",
                    outcome.customId(), e.getMessage());
        }
    }

    private void persistSyncLog(ResultContext context, ClaudeSyncOutcome outcome,
            EvaluationTask.Forecast task) {
        if (context == null || context.jobRunId() == null) {
            return;
        }
        try {
            TokenUsage tokens = outcome.tokenUsage() != null
                    ? outcome.tokenUsage() : TokenUsage.EMPTY;
            jobRunService.logAnthropicApiCall(
                    context.jobRunId(), outcome.durationMs(),
                    outcome.succeeded() ? 200 : 500,
                    outcome.succeeded() ? null : outcome.errorMessage(),
                    outcome.succeeded(), outcome.errorMessage(),
                    task.model(), tokens,
                    false,
                    task.date(), task.targetType());
        } catch (Exception e) {
            LOG.warn("Forecast sync: failed to persist api_call_log for {}: {}",
                    task.taskKey(), e.getMessage());
        }
    }
}
