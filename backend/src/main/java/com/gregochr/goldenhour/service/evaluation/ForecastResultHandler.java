package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.JobRunService;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *       and {@code cached_evaluation} atomically.</li>
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

    private final BriefingEvaluationService briefingEvaluationService;
    private final ClaudeEvaluationStrategy parsingStrategy;
    private final JobRunService jobRunService;
    private final ObjectMapper objectMapper;

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
     */
    public ForecastResultHandler(BriefingEvaluationService briefingEvaluationService,
            Map<EvaluationModel, EvaluationStrategy> evaluationStrategies,
            JobRunService jobRunService,
            ObjectMapper objectMapper) {
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
            SunsetEvaluation eval = parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper);
            Integer safeRating = RatingValidator.validateRating(
                    eval.rating(), regionName, parsed.date(), parsed.targetType(),
                    location.getName(),
                    outcome.model() != null ? outcome.model().name() : "UNKNOWN");

            BriefingEvaluationResult result = new BriefingEvaluationResult(
                    location.getName(), safeRating,
                    eval.fierySkyPotential(), eval.goldenHourPotential(), eval.summary());

            persistBatchLog(context, outcome, parsed.date(), parsed.targetType(), outcome.model());
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

        Integer safeRating = RatingValidator.validateRating(
                eval.rating(), regionName, task.date(), task.targetType(),
                task.location().getName(), task.model().name());
        BriefingEvaluationResult result = new BriefingEvaluationResult(
                task.location().getName(), safeRating,
                eval.fierySkyPotential(), eval.goldenHourPotential(), eval.summary());

        persistSyncLog(context, outcome, task);
        String cacheKey = CacheKeyFactory.build(regionName, task.date(), task.targetType());
        briefingEvaluationService.writeFromBatch(cacheKey, List.of(result));

        return new EvaluationResult.Scored(eval);
    }

    private void persistBatchLog(ResultContext context, ClaudeBatchOutcome outcome,
            LocalDate targetDate, TargetType targetType, EvaluationModel model) {
        if (context == null || context.jobRunId() == null) {
            return;
        }
        try {
            jobRunService.logBatchResult(
                    context.jobRunId(), context.batchId(), outcome.customId(),
                    outcome.succeeded(), outcome.status(),
                    outcome.errorType(), outcome.errorMessage(),
                    model, outcome.tokenUsage(),
                    targetDate, targetType);
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
