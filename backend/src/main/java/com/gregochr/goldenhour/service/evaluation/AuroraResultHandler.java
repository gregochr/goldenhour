package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ResultHandler} for aurora photography evaluations.
 *
 * <p>Handles both transports:
 * <ul>
 *   <li>{@link #processBatchResponse} — invoked by
 *       {@link com.gregochr.goldenhour.service.batch.BatchResultProcessor} for the single
 *       aurora batch response. Re-runs weather triage at result-processing time (because
 *       cloud cover may have changed since submission), parses the multi-location response,
 *       applies the rejected-1★ fallback, and writes scores to {@link AuroraStateCache}.</li>
 *   <li>{@link #handleSyncResult} — invoked by {@link EvaluationServiceImpl#evaluateNow}
 *       for aurora tasks (real-time path). Parses the response and writes to
 *       {@link AuroraStateCache} with the rejected-1★ fallback applied by the caller
 *       before submission. Records {@code api_call_log} so aurora real-time has the same
 *       observability footprint as forecast.</li>
 * </ul>
 *
 * <p>Closes the Pass 1 §6 observability gap for aurora real-time: prior to Pass 3.2
 * synchronous aurora calls produced no {@code api_call_log} or {@code job_run} rows.
 */
@Component
public class AuroraResultHandler implements ResultHandler<EvaluationTask.Aurora> {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraResultHandler.class);

    private final ClaudeAuroraInterpreter claudeAuroraInterpreter;
    private final AuroraStateCache auroraStateCache;
    private final WeatherTriageService weatherTriageService;
    private final LocationRepository locationRepository;
    private final AuroraProperties auroraProperties;
    private final JobRunService jobRunService;

    /**
     * Constructs the handler.
     *
     * @param claudeAuroraInterpreter parses Claude's multi-location aurora JSON
     * @param auroraStateCache        in-memory aurora score cache writer
     * @param weatherTriageService    re-triages locations at result time
     * @param locationRepository      Bortle-filtered candidate lookup
     * @param auroraProperties        Bortle thresholds per alert level
     * @param jobRunService           writer of {@code api_call_log} rows
     */
    public AuroraResultHandler(ClaudeAuroraInterpreter claudeAuroraInterpreter,
            AuroraStateCache auroraStateCache,
            WeatherTriageService weatherTriageService,
            LocationRepository locationRepository,
            AuroraProperties auroraProperties,
            JobRunService jobRunService) {
        this.claudeAuroraInterpreter = claudeAuroraInterpreter;
        this.auroraStateCache = auroraStateCache;
        this.weatherTriageService = weatherTriageService;
        this.locationRepository = locationRepository;
        this.auroraProperties = auroraProperties;
        this.jobRunService = jobRunService;
    }

    @Override
    public Class<EvaluationTask.Aurora> taskType() {
        return EvaluationTask.Aurora.class;
    }

    /**
     * Outcome of {@link #processBatchResponse}.
     *
     * @param success      true if the score cache was updated; false if any gate prevented it
     * @param scoredCount  number of scored locations written (0 on failure)
     * @param failureReason short reason on failure, else {@code null}
     */
    public record AuroraBatchOutcome(boolean success, int scoredCount, String failureReason) {
        public static AuroraBatchOutcome ok(int scoredCount) {
            return new AuroraBatchOutcome(true, scoredCount, null);
        }

        public static AuroraBatchOutcome failure(String reason) {
            return new AuroraBatchOutcome(false, 0, reason);
        }
    }

    /**
     * Processes the single aurora batch response.
     *
     * <p>Re-runs Bortle filtering and weather triage at result-processing time (cloud
     * cover may have changed since submission), parses Claude's multi-location response,
     * and writes scores to {@link AuroraStateCache}. Rejected locations get a 1★ fallback.
     *
     * @param parsedAlertLevel alert level decoded from the custom id
     * @param outcome          drained-of-SDK-types view of the aurora response
     * @param context          per-batch observability context
     * @return whether the score cache was updated
     */
    public AuroraBatchOutcome processBatchResponse(AlertLevel parsedAlertLevel,
            ClaudeBatchOutcome outcome, ResultContext context) {

        persistBatchLog(context, outcome, outcome.model());

        if (!outcome.succeeded()) {
            return AuroraBatchOutcome.failure(
                    outcome.errorType() != null ? outcome.errorType() : outcome.status());
        }

        AlertLevel level = parsedAlertLevel != null ? parsedAlertLevel : AlertLevel.QUIET;

        int threshold = (level == AlertLevel.STRONG)
                ? auroraProperties.getBortleThreshold().getStrong()
                : auroraProperties.getBortleThreshold().getModerate();

        List<LocationEntity> candidates = locationRepository
                .findByBortleClassLessThanEqualAndEnabledTrue(threshold);
        if (candidates.isEmpty()) {
            LOG.info("Aurora batch result: no Bortle-eligible locations for re-triage");
            return AuroraBatchOutcome.failure("No Bortle-eligible locations at result processing time");
        }

        WeatherTriageService.TriageResult triage;
        try {
            triage = weatherTriageService.triage(candidates);
        } catch (Exception e) {
            LOG.warn("Aurora batch result: weather re-triage failed: {}", e.getMessage());
            return AuroraBatchOutcome.failure("Weather re-triage failed: " + e.getMessage());
        }

        if (triage.viable().isEmpty()) {
            LOG.info("Aurora batch result: no viable locations after re-triage");
            return AuroraBatchOutcome.failure("No viable locations after re-triage");
        }

        try {
            List<AuroraForecastScore> scores = claudeAuroraInterpreter.parseBatchResponse(
                    outcome.rawText(), level, triage.viable(), triage.cloudByLocation());

            List<AuroraForecastScore> allScores = new ArrayList<>(scores);
            for (LocationEntity rejected : triage.rejected()) {
                int cloud = triage.cloudByLocation().getOrDefault(rejected, 100);
                allScores.add(new AuroraForecastScore(rejected, 1, level, cloud,
                        "Overcast at time of evaluation", "✗ Cloud cover: Overcast"));
            }

            auroraStateCache.updateScores(allScores);
            return AuroraBatchOutcome.ok(allScores.size());
        } catch (Exception e) {
            LOG.error("Aurora batch result: score parsing/caching failed: {}",
                    e.getMessage(), e);
            return AuroraBatchOutcome.failure("Score parsing failed: " + e.getMessage());
        }
    }

    @Override
    public EvaluationResult handleSyncResult(EvaluationTask.Aurora task,
            ClaudeSyncOutcome outcome, ResultContext context) {

        persistSyncLog(context, outcome, task);

        if (!outcome.succeeded()) {
            return new EvaluationResult.Errored(
                    outcome.errorType() != null ? outcome.errorType() : "unknown",
                    outcome.errorMessage());
        }

        try {
            List<AuroraForecastScore> claudeScores = claudeAuroraInterpreter.parseBatchResponse(
                    outcome.rawText(), task.alertLevel(),
                    task.viableLocations(), task.cloudByLocation());

            // Sync path: the orchestrator (AuroraOrchestrator) holds the rejected
            // locations and merges scored + 1★ fallback before calling
            // {@link AuroraStateCache#updateScores}. The handler intentionally
            // does NOT touch the state cache here; we only return the parsed
            // scored results so the caller can merge.
            return new EvaluationResult.Scored(claudeScores);
        } catch (Exception e) {
            LOG.warn("Aurora sync evaluation: parse/cache failed for {}: {}",
                    task.taskKey(), e.getMessage());
            return new EvaluationResult.Errored("parse_error", e.getMessage());
        }
    }

    private void persistBatchLog(ResultContext context, ClaudeBatchOutcome outcome,
            com.gregochr.goldenhour.entity.EvaluationModel model) {
        if (context == null || context.jobRunId() == null) {
            return;
        }
        try {
            jobRunService.logBatchResult(
                    context.jobRunId(), context.batchId(), outcome.customId(),
                    outcome.succeeded(), outcome.status(),
                    outcome.errorType(), outcome.errorMessage(),
                    model, outcome.tokenUsage(),
                    null, null);
        } catch (Exception e) {
            LOG.warn("Aurora batch: failed to persist api_call_log for customId={}: {}",
                    outcome.customId(), e.getMessage());
        }
    }

    private void persistSyncLog(ResultContext context, ClaudeSyncOutcome outcome,
            EvaluationTask.Aurora task) {
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
                    null, null);
        } catch (Exception e) {
            LOG.warn("Aurora sync: failed to persist api_call_log for {}: {}",
                    task.taskKey(), e.getMessage());
        }
    }
}
