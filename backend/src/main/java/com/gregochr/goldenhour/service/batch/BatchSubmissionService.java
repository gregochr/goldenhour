package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.JobRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Single entry point for submitting batch requests to the Anthropic Batch API.
 *
 * <p>Previously {@code ScheduledBatchEvaluationService} carried two near-identical
 * submit methods — {@code submitBatch} (void) for scheduled runs and
 * {@code submitBatchWithResult} (returning {@link BatchSubmitResult}) for admin runs —
 * and {@code ForceSubmitBatchService} carried two more inline copies. This service
 * collapses all four into a single {@link #submit} method parameterised by
 * {@link BatchTriggerSource}, returning a result record that scheduled callers can
 * discard.
 *
 * <p>Callers are responsible for constructing the {@link BatchCreateParams.Request}
 * list (typically via {@code BatchRequestFactory}); this service only takes care of
 * the Anthropic call, the {@link ForecastBatchEntity} persistence, and the linked
 * {@link JobRunEntity} bookkeeping.
 */
@Service
public class BatchSubmissionService {

    private static final Logger LOG = LoggerFactory.getLogger(BatchSubmissionService.class);

    private final AnthropicClient anthropicClient;
    private final ForecastBatchRepository batchRepository;
    private final JobRunService jobRunService;

    /**
     * Constructs the submission service.
     *
     * @param anthropicClient raw Anthropic SDK client for batch API access
     * @param batchRepository repository for persisting the batch tracking entity
     * @param jobRunService   service for creating the linked job run record
     */
    public BatchSubmissionService(AnthropicClient anthropicClient,
            ForecastBatchRepository batchRepository,
            JobRunService jobRunService) {
        this.anthropicClient = anthropicClient;
        this.batchRepository = batchRepository;
        this.jobRunService = jobRunService;
    }

    /**
     * Submits a batch to the Anthropic API, persists the tracking entity, and creates
     * a linked job run.
     *
     * <p>Existing callers that aren't part of an orchestrated cycle (admin JFDI,
     * force-submit, aurora batch) call this 4-arg overload. The orchestrator
     * calls the 5-arg overload below with a non-null {@code pipelineRunId}.
     *
     * @param requests      the requests to submit; empty list is handled gracefully
     * @param batchType     FORECAST or AURORA
     * @param triggerSource what triggered this submission (SCHEDULED/ADMIN/FORCE/JFDI)
     * @param logPrefix     human-readable label used in log lines
     * @return a result describing the submitted batch, or {@code null} if the batch was
     *         empty or the API call failed
     */
    public BatchSubmitResult submit(List<BatchCreateParams.Request> requests,
            BatchType batchType, BatchTriggerSource triggerSource, String logPrefix) {
        return submit(requests, batchType, triggerSource, logPrefix, null);
    }

    /**
     * Cycle-aware variant — tags the persisted {@link ForecastBatchEntity} with
     * the given {@code pipelineRunId} so the orchestrator can query its
     * batch set later via
     * {@link com.gregochr.goldenhour.repository.ForecastBatchRepository#findByPipelineRunId}.
     *
     * <p>Empty request lists return a null-batch result without contacting Anthropic.
     * Exceptions are caught and logged at ERROR; the caller receives {@code null}.
     *
     * @param requests      the requests to submit; empty list is handled gracefully
     * @param batchType     FORECAST or AURORA
     * @param triggerSource what triggered this submission
     * @param logPrefix     human-readable label used in log lines
     * @param pipelineRunId orchestrated cycle id, or {@code null} for ad-hoc submissions
     * @return a result describing the submitted batch, or {@code null} if the batch was
     *         empty or the API call failed
     */
    public BatchSubmitResult submit(List<BatchCreateParams.Request> requests,
            BatchType batchType, BatchTriggerSource triggerSource, String logPrefix,
            Long pipelineRunId) {
        return submit(requests, batchType, triggerSource, logPrefix, pipelineRunId, false);
    }

    /**
     * Retry-aware variant — additionally stamps {@code forecast_batch.is_retry} so a
     * RETRY_FAILED-phase batch is distinguishable from its precursor(s) sharing the
     * same {@code pipelineRunId}.
     *
     * <p>The flag is set at row creation (not patched afterwards) so it is visible
     * the instant the batch becomes pollable — closing the window in which the 60s
     * {@code BatchPollingService} could otherwise process a retry batch as a
     * non-retry and route its results through the destructive (replace) cache write
     * rather than the merge.
     *
     * @param requests      the requests to submit; empty list is handled gracefully
     * @param batchType     FORECAST or AURORA
     * @param triggerSource what triggered this submission
     * @param logPrefix     human-readable label used in log lines
     * @param pipelineRunId orchestrated cycle id, or {@code null} for ad-hoc submissions
     * @param isRetry       {@code true} to mark the persisted batch as a retry
     * @return a result describing the submitted batch, or {@code null} if the batch was
     *         empty or the API call failed
     */
    public BatchSubmitResult submit(List<BatchCreateParams.Request> requests,
            BatchType batchType, BatchTriggerSource triggerSource, String logPrefix,
            Long pipelineRunId, boolean isRetry) {
        if (requests.isEmpty()) {
            LOG.info("{} skipped: no requests (trigger={})", logPrefix, triggerSource);
            return null;
        }

        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[BATCH DIAG] Output config schema: {}",
                        requests.get(0).params().outputConfig());
            }

            BatchCreateParams params = BatchCreateParams.builder()
                    .requests(requests)
                    .build();

            // ⚠️ Everything below this line runs AFTER money has been spent. The batch now exists
            // at Anthropic and cannot be recalled, so the ordering of what follows is the whole
            // recovery story.
            MessageBatch batch = anthropicClient.messages().batches().create(params);
            Instant expiresAt = batch.expiresAt().toInstant();

            // The forecast_batch row is persisted FIRST, before any job-run bookkeeping, because
            // it is the ONLY thing that makes the paid batch discoverable: BatchPollingService
            // finds work exclusively through locally persisted SUBMITTED rows. This used to run
            // after startBatchRun, so a failure in job-run bookkeeping — a table this batch's
            // results do not depend on — was enough to strand it forever.
            ForecastBatchEntity entity = new ForecastBatchEntity(
                    batch.id(), batchType, requests.size(), expiresAt);
            if (pipelineRunId != null) {
                entity.setPipelineRunId(pipelineRunId);
            }
            entity.setRetry(isRetry);
            try {
                batchRepository.save(entity);
            } catch (Exception persistFailure) {
                // ⚠️ Do NOT fall through to the null return. `null` means "nothing was submitted",
                // and every caller reads it that way — EvaluationServiceImpl maps it to
                // EvaluationHandle.empty(), from which the orchestrator concludes it has a
                // zero-batch cycle, treats that as terminal, and briefs from stale cache while
                // paid work is still running. An ambiguous "the remote call may have succeeded and
                // we lost the record" must never be reported as an ordinary empty submission.
                LOG.error("ORPHANED BATCH {}: submitted to Anthropic but the tracking row could "
                                + "not be persisted, so polling will never discover it. "
                                + "{} request(s), trigger={}, expires={}. Recover by inserting a "
                                + "forecast_batch row for this id.",
                        batch.id(), requests.size(), triggerSource, expiresAt, persistFailure);
                throw new OrphanedBatchException(batch.id(), persistFailure);
            }

            // Job-run bookkeeping is deliberately best-effort and AFTER the row above: it feeds
            // metrics, not result processing, so losing it costs a dashboard line rather than a
            // batch.
            JobRunEntity jobRun = jobRunService.startBatchRun(requests.size(), batch.id());
            if (jobRun != null) {
                entity.setJobRunId(jobRun.getId());
                batchRepository.save(entity);
            }

            Long jobRunId = jobRun != null ? jobRun.getId() : null;
            LOG.info("{} submitted: batchId={}, {} request(s), expires={}, jobRunId={}, "
                            + "pipelineRunId={}, trigger={}",
                    logPrefix, batch.id(), requests.size(), expiresAt,
                    jobRunId, pipelineRunId, triggerSource);

            return new BatchSubmitResult(jobRunId, batch.id(), requests.size());
        } catch (OrphanedBatchException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("{} submission failed (trigger={}): {}",
                    logPrefix, triggerSource, e.getMessage(), e);
            return null;
        }
    }
}
