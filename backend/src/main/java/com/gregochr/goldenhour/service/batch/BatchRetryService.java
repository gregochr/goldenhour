package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.service.evaluation.ParsedCustomId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects (and, from commit 2, re-submits) a cycle's genuinely-failed forecast
 * requests for the RETRY_FAILED phase.
 *
 * <p><b>What counts as retryable.</b> A failure is a request that was
 * <em>attempted and came back unusable</em> — a parse failure
 * ("Failed to parse evaluation response") or an API error on the request. These
 * are recorded as {@code api_call_log} rows with {@code is_batch = true} and
 * {@code succeeded = false}. A transient model hiccup (garbled JSON) almost
 * always returns valid JSON on a second ask, so a single retry recovers it
 * cheaply.
 *
 * <p><b>What is never retryable.</b> Every deliberate skip
 * ({@code SKIPPED_STABILITY}, {@code SKIPPED_TRIAGED}, {@code SKIPPED_PAST_DATE},
 * {@code SKIPPED_HARD_CONSTRAINT}, {@code SKIPPED_CACHED}, ...). These were
 * intentional non-evaluations recorded in {@code forecast_run_disposition}; they
 * are never sent to the model and therefore have <em>no {@code api_call_log}
 * row</em>. The retry set is derived purely from {@code api_call_log} failures,
 * so a skip cannot enter it — the cost-safety guarantee is structural, not a
 * filter that could drift.
 *
 * <p><b>The cap is budget and tripwire.</b> A handful of failures is transient
 * noise; retry them. More than {@link #failureCap} failures is not noise — it is
 * a systematic problem (prompt regression, model issue, bad input) that would
 * fail again on retry and cost far more, so the phase records it loudly and does
 * NOT retry. The retry is a single pass: each failed request is retried at most
 * once (no retry loops), bounding the per-cycle cost to
 * {@code <= cap requests x 1 retry}.
 */
@Service
public class BatchRetryService {

    private static final Logger LOG = LoggerFactory.getLogger(BatchRetryService.class);

    private final ForecastBatchRepository forecastBatchRepository;
    private final ApiCallLogRepository apiCallLogRepository;
    private final LocationRepository locationRepository;
    private final ForecastService forecastService;
    private final ModelSelectionService modelSelectionService;
    private final EvaluationService evaluationService;

    /**
     * Per-cycle retry cap — both the budget ceiling and the systematic-failure
     * tripwire. Default 5: at Haiku batch rates the worst case (every cycle hits
     * the cap) is a few pence against ~£2.50/night — bounded by construction.
     */
    private final int failureCap;

    /**
     * Constructs the retry service.
     *
     * @param forecastBatchRepository batch repository (precursor / retry lookups)
     * @param apiCallLogRepository    api-call-log repository (failure rows)
     * @param locationRepository      resolves a failed request's location for reconstruction
     * @param forecastService         re-assembles atmospheric data for a single retried request
     * @param modelSelectionService   resolves the model tier (near/far) for a retried request
     * @param evaluationService       submits the reconstructed requests as one retry batch
     * @param failureCap              per-cycle retry cap
     */
    public BatchRetryService(ForecastBatchRepository forecastBatchRepository,
            ApiCallLogRepository apiCallLogRepository,
            LocationRepository locationRepository,
            ForecastService forecastService,
            ModelSelectionService modelSelectionService,
            EvaluationService evaluationService,
            @Value("${photocast.batch.retry-failure-cap:5}") int failureCap) {
        this.forecastBatchRepository = forecastBatchRepository;
        this.apiCallLogRepository = apiCallLogRepository;
        this.locationRepository = locationRepository;
        this.forecastService = forecastService;
        this.modelSelectionService = modelSelectionService;
        this.evaluationService = evaluationService;
        this.failureCap = failureCap;
    }

    /**
     * Selects the cycle's retryable forecast failures and applies the cap policy.
     *
     * <p>Reads failures only from the cycle's <em>precursor</em> (non-retry)
     * FORECAST batches — never from a retry batch — so a retry's own failures can
     * never be re-selected (single-retry guarantee). Failures are de-duplicated by
     * {@code custom_id}; only parseable forecast custom ids are retained (a
     * malformed id cannot be reconstructed and is dropped from the set rather than
     * retried blindly).
     *
     * @param pipelineRunId the orchestrated cycle id
     * @return the selection + cap decision (NONE / RETRY / SYSTEMATIC)
     */
    public RetrySelection selectFailures(Long pipelineRunId) {
        List<ForecastBatchEntity> precursors =
                forecastBatchRepository.findByPipelineRunIdAndRetryFalse(pipelineRunId);
        List<String> batchIds = precursors.stream()
                .filter(b -> b.getBatchType() == BatchType.FORECAST)
                .map(ForecastBatchEntity::getAnthropicBatchId)
                .toList();
        if (batchIds.isEmpty()) {
            return RetrySelection.none(failureCap);
        }

        List<ApiCallLogEntity> failedRows = apiCallLogRepository.findFailedBatchCalls(batchIds);

        // De-duplicate by custom_id (a request has one failure row per precursor
        // batch; defend against any double-write). Keep only parseable forecast
        // ids — a malformed id, a location-not-found, or an aurora id in a forecast
        // batch cannot be reconstructed into the same request, so it is not retried.
        Map<String, RetrySelection.RetryFailure> byCustomId = new LinkedHashMap<>();
        for (ApiCallLogEntity row : failedRows) {
            String customId = row.getCustomId();
            if (customId == null || byCustomId.containsKey(customId)) {
                continue;
            }
            ParsedCustomId parsed;
            try {
                parsed = CustomIdFactory.parse(customId);
            } catch (IllegalArgumentException e) {
                LOG.warn("RETRY_FAILED: dropping unparseable failed custom_id '{}' "
                        + "(pipelineRunId={})", customId, pipelineRunId);
                continue;
            }
            if (parsed instanceof ParsedCustomId.Forecast f) {
                byCustomId.put(customId, new RetrySelection.RetryFailure(
                        customId, f.locationId(), f.date(), f.targetType()));
            } else {
                LOG.warn("RETRY_FAILED: dropping non-forecast failed custom_id '{}' "
                        + "(pipelineRunId={})", customId, pipelineRunId);
            }
        }

        int failureCount = byCustomId.size();
        if (failureCount == 0) {
            return RetrySelection.none(failureCap);
        }
        if (failureCount > failureCap) {
            LOG.error("RETRY_FAILED: {} genuine failures in pipelineRunId={} EXCEEDS cap {} "
                    + "— NOT retrying. This is a systematic failure (prompt regression, model "
                    + "issue, or bad input), not transient noise — investigate.",
                    failureCount, pipelineRunId, failureCap);
            return RetrySelection.systematic(failureCount, failureCap);
        }
        LOG.info("RETRY_FAILED: {} genuine failure(s) within cap {} for pipelineRunId={} "
                + "— will retry once", failureCount, failureCap, pipelineRunId);
        return RetrySelection.retry(new ArrayList<>(byCustomId.values()), failureCap);
    }

    /**
     * Reconstructs the selected failures into the SAME (location, date, event)
     * requests the precursor batch sent and submits them as a single retry batch
     * tagged with the cycle's {@code pipelineRunId} and {@code is_retry = true}.
     *
     * <p>Reconstruction re-loads the location, re-derives the model tier (near/far
     * by horizon — the cheap, deterministic re-derivation; the retry runs minutes
     * after the original so the tier is the same in practice), and re-assembles the
     * atmospheric data via the same {@link ForecastService} path the original used.
     * A request that cannot be reconstructed (location deleted, or weather has since
     * turned unsuitable so assembly triages it away) is logged and left failed — it
     * falls back to the next cycle, exactly as today.
     *
     * <p><b>Idempotent and single-pass.</b> If a retry batch already exists for the
     * cycle (a process restart re-entered the phase), submission is skipped — the
     * existing batch is waited on instead. This, with selection reading only
     * precursor batches, guarantees at most one retry per cycle.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @param selection     a {@link RetrySelection.Decision#RETRY} selection
     * @return number of requests actually submitted in the retry batch (0 if the
     *         selection was not RETRY, a retry already existed, or nothing could be
     *         reconstructed)
     */
    public int submitRetry(Long pipelineRunId, RetrySelection selection) {
        if (selection.decision() != RetrySelection.Decision.RETRY) {
            return 0;
        }
        if (!forecastBatchRepository.findByPipelineRunIdAndRetryTrue(pipelineRunId).isEmpty()) {
            LOG.info("RETRY_FAILED: a retry batch already exists for pipelineRunId={} "
                    + "— skipping submission (idempotent re-entry)", pipelineRunId);
            return 0;
        }

        List<EvaluationTask.Forecast> tasks = new ArrayList<>();
        for (RetrySelection.RetryFailure failure : selection.failures()) {
            EvaluationTask.Forecast task = reconstruct(failure);
            if (task != null) {
                tasks.add(task);
            }
        }
        if (tasks.isEmpty()) {
            LOG.warn("RETRY_FAILED: no requests could be reconstructed for pipelineRunId={} "
                    + "— no retry batch submitted", pipelineRunId);
            return 0;
        }

        EvaluationHandle handle = evaluationService.submit(
                tasks, BatchTriggerSource.RETRY, pipelineRunId, true);
        LOG.info("RETRY_FAILED: submitted retry batch for pipelineRunId={} — {} request(s), "
                + "batchId={}", pipelineRunId, handle.submittedCount(), handle.batchId());
        return handle.submittedCount();
    }

    /**
     * Reconstructs a single failed request, or returns {@code null} if it cannot be
     * rebuilt (location gone, or assembly triaged it away, or any assembly error).
     */
    private EvaluationTask.Forecast reconstruct(RetrySelection.RetryFailure failure) {
        try {
            LocationEntity location = locationRepository.findById(failure.locationId()).orElse(null);
            if (location == null) {
                LOG.warn("RETRY_FAILED: location {} no longer exists — cannot reconstruct {} "
                        + "(stays failed)", failure.locationId(), failure.customId());
                return null;
            }
            EvaluationModel model = modelFor(failure.date());
            ForecastPreEvalResult pre = forecastService.fetchWeatherAndTriage(
                    location, failure.date(), failure.targetType(), location.getTideType(),
                    model, false, null);
            if (pre.atmosphericData() == null) {
                LOG.warn("RETRY_FAILED: {} could not be reconstructed (triaged={}) "
                        + "— stays failed", failure.customId(), pre.triaged());
                return null;
            }
            return new EvaluationTask.Forecast(location, failure.date(), failure.targetType(),
                    model, pre.atmosphericData(),
                    EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
        } catch (Exception e) {
            LOG.warn("RETRY_FAILED: could not reconstruct request {} — stays failed: {}",
                    failure.customId(), e.getMessage());
            return null;
        }
    }

    /**
     * Builds the RETRY_FAILED phase detail summary from the cycle's retry batch
     * outcome, in the form {@code "N failed, M retried, K recovered, J still-failed"}.
     *
     * <p>Read from the persisted retry batch counts (set by the result processor when
     * the batch reached terminal status), so it is correct whether the phase ran
     * straight through or resumed after a restart. {@code M} (retried) is the retry
     * batch's request count — failures that could not be reconstructed are counted in
     * {@code N} but not {@code M}.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @param failedCount   the genuine failure count selection found ({@code N})
     * @return the phase detail summary
     */
    public String summariseRecovery(Long pipelineRunId, int failedCount) {
        List<ForecastBatchEntity> retryBatches =
                forecastBatchRepository.findByPipelineRunIdAndRetryTrue(pipelineRunId);
        int retried = retryBatches.stream()
                .mapToInt(ForecastBatchEntity::getRequestCount).sum();
        int recovered = retryBatches.stream()
                .mapToInt(b -> b.getSucceededCount() != null ? b.getSucceededCount() : 0).sum();
        int stillFailed = retryBatches.stream()
                .mapToInt(b -> b.getErroredCount() != null ? b.getErroredCount() : 0).sum();
        return String.format("%d failed, %d retried, %d recovered, %d still-failed",
                failedCount, retried, recovered, stillFailed);
    }

    /**
     * Resolves the model tier for a retried request from its horizon, mirroring the
     * near/far split the collector applied originally: T+0/T+1 → near-term model,
     * T+2+ → far-term model.
     */
    private EvaluationModel modelFor(LocalDate date) {
        int daysAhead = (int) ChronoUnit.DAYS.between(
                LocalDate.now(ZoneId.of("Europe/London")), date);
        RunType runType = daysAhead <= ForecastTaskCollector.NEAR_TERM_MAX_DAYS
                ? RunType.BATCH_NEAR_TERM
                : RunType.BATCH_FAR_TERM;
        return modelSelectionService.getActiveModel(runType);
    }

    /**
     * Returns the configured per-cycle retry cap.
     *
     * @return the failure cap
     */
    public int failureCap() {
        return failureCap;
    }
}
