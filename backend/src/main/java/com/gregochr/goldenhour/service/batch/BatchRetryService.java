package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.ParsedCustomId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
     * @param failureCap              per-cycle retry cap
     */
    public BatchRetryService(ForecastBatchRepository forecastBatchRepository,
            ApiCallLogRepository apiCallLogRepository,
            @Value("${photocast.batch.retry-failure-cap:5}") int failureCap) {
        this.forecastBatchRepository = forecastBatchRepository;
        this.apiCallLogRepository = apiCallLogRepository;
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
     * Returns the configured per-cycle retry cap.
     *
     * @return the failure cap
     */
    public int failureCap() {
        return failureCap;
    }
}
