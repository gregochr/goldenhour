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
     * <p>Empty request lists return a null-batch result without contacting Anthropic.
     * Exceptions are caught and logged at ERROR; the caller receives {@code null}
     * (matching prior behaviour in both {@code submitBatch} variants).
     *
     * @param requests      the requests to submit; empty list is handled gracefully
     * @param batchType     FORECAST or AURORA
     * @param triggerSource what triggered this submission (SCHEDULED/ADMIN/FORCE/JFDI)
     * @param logPrefix     human-readable label used in log lines (e.g. "Near-term batch (inland)")
     * @return a result describing the submitted batch, or {@code null} if the batch was
     *         empty or the API call failed
     */
    public BatchSubmitResult submit(List<BatchCreateParams.Request> requests,
            BatchType batchType, BatchTriggerSource triggerSource, String logPrefix) {
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

            MessageBatch batch = anthropicClient.messages().batches().create(params);
            Instant expiresAt = batch.expiresAt().toInstant();

            JobRunEntity jobRun = jobRunService.startBatchRun(requests.size(), batch.id());

            ForecastBatchEntity entity = new ForecastBatchEntity(
                    batch.id(), batchType, requests.size(), expiresAt);
            if (jobRun != null) {
                entity.setJobRunId(jobRun.getId());
            }
            batchRepository.save(entity);

            LOG.info("{} submitted: batchId={}, {} request(s), expires={}, jobRunId={}, trigger={}",
                    logPrefix, batch.id(), requests.size(), expiresAt,
                    jobRun != null ? jobRun.getId() : null, triggerSource);

            return new BatchSubmitResult(batch.id(), requests.size());
        } catch (Exception e) {
            LOG.error("{} submission failed (trigger={}): {}",
                    logPrefix, triggerSource, e.getMessage(), e);
            return null;
        }
    }
}
