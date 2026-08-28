package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.BatchState;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BluebellEvaluation;
import com.gregochr.goldenhour.model.WoodlandEvaluation;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideContext;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
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
 * {@link SunsetEvaluationParser#parseEvaluation} logic that
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
    private final SunsetEvaluationParser parser;
    private final JobRunService jobRunService;
    private final ObjectMapper objectMapper;
    private final RatingCombiner ratingCombiner;
    private final ForecastDataAugmentor forecastDataAugmentor;
    private final ForecastScoreWriter forecastScoreWriter;
    private final ForecastEvaluationRepository forecastEvaluationRepository;

    /**
     * Constructs the handler.
     *
     * <p>Uses the shared {@link SunsetEvaluationParser} (any concrete model — HAIKU is the
     * cheapest to instantiate) out of the strategy map only to reuse its
     * {@link SunsetEvaluationParser#parseEvaluation} method. No Claude calls are made
     * through this strategy; it is a parser handle.
     *
     * @param briefingEvaluationService cache writer (for both sync and end-of-batch flushes)
     * @param parser                    parses raw Batch API text into evaluations
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
     * @param forecastEvaluationRepository R5 seam: scores a {@code PENDING} {@code
     *                                  forecast_evaluation} row in place by primary key when a
     *                                  batch result carries a non-null {@code evalRowId}
     */
    public ForecastResultHandler(BriefingEvaluationService briefingEvaluationService,
            JobRunService jobRunService,
            ObjectMapper objectMapper,
            RatingCombiner ratingCombiner,
            ForecastDataAugmentor forecastDataAugmentor,
            ForecastScoreWriter forecastScoreWriter,
            SunsetEvaluationParser parser,
            ForecastEvaluationRepository forecastEvaluationRepository) {
        this.briefingEvaluationService = briefingEvaluationService;
        this.parser = parser;
        this.jobRunService = jobRunService;
        this.objectMapper = objectMapper;
        this.ratingCombiner = ratingCombiner;
        this.forecastDataAugmentor = forecastDataAugmentor;
        this.forecastScoreWriter = forecastScoreWriter;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
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
     * @param evalRowId  primary key of the {@code PENDING} row this result scores in place (R5),
     *                   or {@code null} for every non-{@code fc-} lane and for an {@code fc-} id
     *                   with no embedded row id (pre-deploy format)
     */
    public record ForecastIdentity(Long locationId, LocalDate date, TargetType targetType,
            Long evalRowId) {
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
            SunsetEvaluationParser.ParseResult parsed0 =
                    parser.parseEvaluationWithMetadata(outcome.rawText(), objectMapper);
            SunsetEvaluation eval = parsed0.evaluation();
            String modelName = outcome.model() != null ? outcome.model().name() : "UNKNOWN";
            BriefingEvaluationResult result = buildResult(
                    location, eval, parsed.date(), parsed.targetType(), regionName, modelName,
                    context != null ? context.pipelineRunId() : null,
                    parsed.evalRowId(), outcome.model());

            if (parsed0.usedRegexFallback()) {
                // Strict JSON parse failed and the regex fallback recovered the result (possibly
                // over-capturing — the Bug B trigger). This is a silent success, so persist the raw
                // response and mark it findable for the admin Job Run screen / a real fixture.
                persistBatchLog(context, outcome, parsed.date(), parsed.targetType(),
                        outcome.model(), REGEX_FALLBACK_MARKER, outcome.rawText());
            } else {
                // Persist the raw response on the clean-success path too, not only on the regex
                // fallback. A batch-scored slot otherwise leaves no record of what Claude returned
                // (request_body and response_body are both null), which is what made the
                // 2026-07-25 adjacent-location divergence impossible to diagnose after the fact.
                persistBatchLog(context, outcome, parsed.date(), parsed.targetType(),
                        outcome.model(), null, outcome.rawText());
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
     * {@link SunsetEvaluationParser#parseBluebellEvaluation} and combined through the
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
                    parser.parseBluebellEvaluation(outcome.rawText(), objectMapper);
            if (bluebell.rating() == null) {
                throw new IllegalStateException("bluebell response omitted the rating");
            }
            String modelName = outcome.model() != null ? outcome.model().name() : "UNKNOWN";
            BriefingEvaluationResult result = buildBluebellResult(
                    location, bluebell, parsed.date(), parsed.targetType(), regionName, modelName,
                    context != null ? context.pipelineRunId() : null);
            persistBatchLog(context, outcome, parsed.date(), parsed.targetType(),
                    outcome.model(), null, outcome.rawText());
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
     * existing {@link BriefingEvaluationService#writeFromBatch} entry point.
     *
     * @deprecated REPLACES the region's whole cache entry with {@code results}. That is only safe
     *     when the caller owns every location in the region for that date and event, and no caller
     *     does: coastal/inland are split per-location across separate batches, and bluebell and
     *     woodland are their own buckets, so whichever finished second deleted the first's
     *     locations. Use {@link #mergeCacheKey} instead. Retained only so the destructive
     *     behaviour stays directly testable.
     *
     * @param cacheKey region cache key
     * @param results  all locations in that cache key for this batch
     */
    @Deprecated
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

    /**
     * Merges a woodland mini-batch's results into the region's cache entry.
     *
     * <p>Merges rather than flushes for the same reason bluebell does: the woodland batch holds
     * only a region's canopy sites, so replacing the entry would drop its sky locations. Unlike
     * bluebell there is no recombination to do — a canopy site has no sky result to fold back
     * into, by construction.
     *
     * @param cacheKey region cache key
     * @param results  the canopy locations for that cache key
     */
    public void mergeWoodlandCacheKey(String cacheKey, List<BriefingEvaluationResult> results) {
        briefingEvaluationService.mergeWoodlandFromBatch(cacheKey, results);
    }

    /**
     * Parses a woodland-prompt batch response into a cache-keyed result.
     *
     * <p>Mirrors {@link #parseBluebellBatchResponse}: no sky evaluation exists behind a canopy
     * slot, so the combiner runs over a woodland-only context, the {@code SkyVisitor} abstains,
     * and {@code WoodlandVisitor} supplies the sole component. The serving payload carries no
     * 0-100 potentials — the woodland summary and headline are the user-facing prose.
     *
     * @param location the canopy location
     * @param parsed   the identity decoded from the custom id
     * @param outcome  the raw Claude batch outcome
     * @param context  the result context (pipeline run, logging)
     * @return the cache-keyed success, or empty when the response failed or could not be parsed
     */
    public Optional<BatchSuccess> parseWoodlandBatchResponse(LocationEntity location,
            ForecastIdentity parsed, ClaudeBatchOutcome outcome, ResultContext context) {

        String regionName = location.getRegion() != null
                ? location.getRegion().getName() : location.getName();
        String cacheKey = CacheKeyFactory.build(regionName, parsed.date(), parsed.targetType());

        if (!outcome.succeeded()) {
            persistBatchLog(context, outcome, parsed.date(), parsed.targetType(), null);
            return Optional.empty();
        }

        try {
            WoodlandEvaluation woodland =
                    parser.parseWoodlandEvaluation(outcome.rawText(), objectMapper);
            if (woodland.rating() == null) {
                throw new IllegalStateException("woodland response omitted the rating");
            }
            String modelName = outcome.model() != null ? outcome.model().name() : "UNKNOWN";
            BriefingEvaluationResult result = buildWoodlandResult(
                    location, woodland, parsed.date(), parsed.targetType(), regionName, modelName,
                    context != null ? context.pipelineRunId() : null);
            persistBatchLog(context, outcome, parsed.date(), parsed.targetType(),
                    outcome.model(), null, outcome.rawText());
            return Optional.of(new BatchSuccess(cacheKey, result));
        } catch (Exception e) {
            LOG.warn("Woodland batch: parse failed for '{}': {}",
                    outcome.customId(), e.getMessage());
            LOG.warn("Woodland batch: raw response for '{}': {}",
                    outcome.customId(), outcome.rawText());
            ClaudeBatchOutcome parseFailure = ClaudeBatchOutcome.failure(
                    outcome.customId(), "PARSE_FAILED", "parse_error", e.getMessage());
            persistBatchLog(context, parseFailure, parsed.date(), parsed.targetType(), null);
            return Optional.empty();
        }
    }

    /**
     * Builds the {@link BriefingEvaluationResult} for a woodland-prompt evaluation.
     *
     * <p>No tide context is derived: {@code isWoodlandOnly()} excludes SEASCAPE, so a canopy site
     * has no tide preference to score and the tide visitor would abstain regardless.
     */
    private BriefingEvaluationResult buildWoodlandResult(LocationEntity location,
            WoodlandEvaluation woodland, LocalDate date, TargetType targetType, String regionName,
            String modelName, Long pipelineRunId) {
        RatingCombiner.CombinedRating combined = ratingCombiner.combine(
                location, new VisitorContext(null, null, null, woodland));
        Integer safeRating = RatingValidator.validateRating(
                combined.rating(), regionName, date, targetType, location.getName(), modelName);

        try {
            forecastScoreWriter.writeComponents(
                    location, date, targetType, combined.components(), pipelineRunId);
        } catch (Exception e) {
            LOG.error("forecast_score woodland dual-write FAILED for component key "
                    + "(location={}, date={}, event={}); the SERVED evaluation is unaffected, but "
                    + "this slot's forecast_score row is now stale and the API reads bluebell "
                    + "ratings from it. Repaired only IF this slot is successfully evaluated again — "
                    + "triage and the T+2/T+3 stability gates can skip every later "
                    + "attempt, so a stale row can outlive its event: {}",
                    location.getName(), date, targetType, e.getMessage(), e);
        }

        return new BriefingEvaluationResult(
                location.getName(), safeRating, null, null, woodland.summary(),
                null, null, woodland.headline());
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
            eval = parser.parseEvaluation(outcome.rawText(), objectMapper);
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
                context != null ? context.pipelineRunId() : null,
                task.evalRowId(), task.model());

        persistSyncLog(context, outcome, task);
        if (task.writeTarget() == EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE) {
            // Merge, not replace. The cache key is per REGION and this is ONE location's result;
            // writeFromBatch would rebuild the region's entry from this single result and delete
            // every other location in it. Same defect as the batch path, and worse here because
            // the incoming set is a single row rather than a whole bucket.
            String cacheKey = CacheKeyFactory.build(regionName, task.date(), task.targetType());
            briefingEvaluationService.mergeFromBatch(cacheKey, List.of(result));
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
     * @param evalRowId     primary key of the {@code PENDING} {@code forecast_evaluation} row
     *                      this result scores in place (R5), or {@code null} when there is none
     *                      (bluebell/woodland results never reach this method; a sky result on
     *                      the sync path, an unparseable pre-deploy custom id, or a lane out of
     *                      R8 scope)
     * @param resolvedModel the model that actually produced this response, persisted on the row
     *                      alongside the rating so the row's {@code evaluation_model} reflects
     *                      what scored it rather than only what was requested
     * @return the result to persist (cache payload element)
     */
    private BriefingEvaluationResult buildResult(LocationEntity location, SunsetEvaluation eval,
            LocalDate date, TargetType targetType, String regionName, String modelName,
            Long pipelineRunId, Long evalRowId, EvaluationModel resolvedModel) {
        BriefingEvaluationResult result;
        if (eval.rating() == null) {
            // Sky not forecast: Claude omitted the rating. The combiner never runs, so there is
            // no genuine component score to record — no forecast_score dual-write here (Pass 2).
            // The row and cached_evaluation must never disagree about one slot (2026-08-03 three-
            // rules lesson), so this substitution lands in the row too (R5) — same as the scored
            // branch below.
            result = new BriefingEvaluationResult(
                    location.getName(), SKY_NOT_FORECAST_RATING,
                    eval.fierySkyPotential(), eval.goldenHourPotential(), SKY_NOT_FORECAST_SUMMARY,
                    null, null, null);
        } else {
            Set<TideType> tideTypes = location.getTideType();
            TideContext tide = (tideTypes != null && !tideTypes.isEmpty())
                    ? forecastDataAugmentor.deriveTideContext(location, date, targetType)
                            .orElse(null)
                    : null;
            RatingCombiner.CombinedRating combined =
                    ratingCombiner.combine(location, new VisitorContext(eval, tide));
            Integer safeRating = RatingValidator.validateRating(
                    combined.rating(), regionName, date, targetType, location.getName(), modelName);

            dualWriteForecastScore(location, date, targetType, eval, combined, pipelineRunId);

            result = new BriefingEvaluationResult(
                    location.getName(), safeRating,
                    eval.fierySkyPotential(), eval.goldenHourPotential(), eval.summary(),
                    null, null, eval.headline());
        }
        if (evalRowId != null) {
            scoreEvaluationRow(evalRowId, result, resolvedModel);
        }
        return result;
    }

    /**
     * R5: scores a {@code PENDING} {@code forecast_evaluation} row in place by primary key —
     * the same validated rating, potentials, summary and headline as the
     * {@code cached_evaluation} write, including the sky-not-forecast substitution. Does NOT
     * bump {@code forecast_run_at} — it means "when the weather was sampled", and the freshness
     * comparisons in {@code EvaluationViewService} depend on it staying the submit-time value.
     *
     * <p>A missing row (deleted, or an id from a different environment) is logged and swallowed
     * — the served rating (cache/forecast_score) is unaffected either way, this is only the
     * secondary durable-history write.
     *
     * @param evalRowId     primary key of the pending row
     * @param result        the validated result also being written to {@code cached_evaluation}
     * @param resolvedModel the model that produced the response
     */
    private void scoreEvaluationRow(Long evalRowId, BriefingEvaluationResult result,
            EvaluationModel resolvedModel) {
        Optional<ForecastEvaluationEntity> row = forecastEvaluationRepository.findById(evalRowId);
        if (row.isEmpty()) {
            LOG.warn("R5: PENDING forecast_evaluation row {} not found at result time — "
                    + "cannot score in place", evalRowId);
            return;
        }
        ForecastEvaluationEntity entity = row.get();
        entity.setRating(result.rating());
        entity.setFierySkyPotential(result.fierySkyPotential());
        entity.setGoldenHourPotential(result.goldenHourPotential());
        entity.setSummary(result.summary());
        entity.setHeadline(result.headline());
        if (resolvedModel != null) {
            entity.setEvaluationModel(resolvedModel);
        }
        entity.setBatchState(BatchState.SCORED);
        forecastEvaluationRepository.save(entity);
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
                    + "(location={}, date={}, event={}); the SERVED evaluation is unaffected, but "
                    + "this slot's forecast_score row is now stale and the API reads bluebell "
                    + "ratings from it. Repaired only IF this slot is successfully evaluated again — "
                    + "triage and the T+2/T+3 stability gates can skip every later "
                    + "attempt, so a stale row can outlive its event: {}",
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
     * the live product and must not fail because a secondary write did. The
     * {@code REQUIRES_NEW} boundary inside the writer confines any rollback to the dual-write.
     *
     * <p>⚠️ This javadoc used to end "{@code forecast_score} is the record being proven", which
     * has been false since {@code ForecastDtoMapper} started serving the API's bluebell rating
     * from that table. Swallowing is still correct; treating the consequence as nil is not. See
     * {@link ForecastScoreWriter} for what a lost write costs and why it usually self-heals.
     */
    private void dualWriteForecastScore(LocationEntity location, LocalDate date,
            TargetType targetType, SunsetEvaluation eval, RatingCombiner.CombinedRating combined,
            Long pipelineRunId) {
        try {
            forecastScoreWriter.write(
                    location, date, targetType, eval, combined.components(), pipelineRunId);
        } catch (Exception e) {
            LOG.error("forecast_score dual-write FAILED for component key "
                    + "(location={}, date={}, event={}); the SERVED evaluation is unaffected, but "
                    + "this slot's forecast_score row is now stale and the API reads bluebell "
                    + "ratings from it. Repaired only IF this slot is successfully evaluated again — "
                    + "triage and the T+2/T+3 stability gates can skip every later "
                    + "attempt, so a stale row can outlive its event: {}",
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
