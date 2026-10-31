package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link ForecastBatchEntity}.
 */
public interface ForecastBatchRepository extends JpaRepository<ForecastBatchEntity, Long> {

    /**
     * Returns all batches in a given status, ordered by submission time descending.
     *
     * @param status the batch status to filter by
     * @return matching batches, newest first
     */
    List<ForecastBatchEntity> findByStatusOrderBySubmittedAtDesc(BatchStatus status);

    /**
     * Returns the most recent N batches regardless of status, newest first.
     *
     * @return recent batch records
     */
    List<ForecastBatchEntity> findTop20ByOrderBySubmittedAtDesc();

    /**
     * Finds the batch linked to a given job run.
     *
     * @param jobRunId the job run ID
     * @return the batch entity if found
     */
    Optional<ForecastBatchEntity> findByJobRunId(Long jobRunId);

    /**
     * Finds the batch by its Anthropic-assigned batch ID.
     *
     * @param anthropicBatchId the {@code msgbatch_*} ID returned by the Batch API
     * @return the batch entity if found
     */
    Optional<ForecastBatchEntity> findByAnthropicBatchId(String anthropicBatchId);

    /**
     * Whether a batch with this Anthropic-assigned ID is already tracked.
     *
     * <p>Used by {@link com.gregochr.goldenhour.service.batch.AnthropicBatchClient}'s
     * duplicate-batch adoption guard to exclude a listed candidate that is already a KNOWN
     * batch — most importantly, an earlier bucket of the same cycle. {@link
     * com.gregochr.goldenhour.service.batch.BatchSubmissionService#submit} persists a bucket's
     * row immediately after {@code create()} returns, before the next bucket is even built, so by
     * the time a later bucket's retry lists recent batches, every genuinely-succeeded sibling is
     * already tracked and excluded here — closing the "adopts a sibling bucket's batch id" failure
     * a size+timing match alone could not rule out.
     *
     * @param anthropicBatchId the {@code msgbatch_*} ID to check
     * @return {@code true} if a row for this id already exists
     */
    boolean existsByAnthropicBatchId(String anthropicBatchId);

    /**
     * Returns all batches tagged with the given pipeline run id.
     *
     * <p>Used by the nightly pipeline orchestrator to answer "is the batch
     * set for this cycle complete?" — the cycle is complete when no batch
     * in this list is still {@code SUBMITTED}.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @return matching batches (any status)
     */
    List<ForecastBatchEntity> findByPipelineRunId(Long pipelineRunId);

    /**
     * Returns a cycle's batches of one type that are not in any of the given statuses, used by the
     * location-failure settle to find forecast batches still being polled (status outside
     * {@link BatchStatus#TERMINAL}).
     *
     * @param pipelineRunId the orchestrated cycle id
     * @param batchType     the batch type to consider
     * @param statuses      the statuses to exclude
     * @return the cycle's matching batches, any order
     */
    List<ForecastBatchEntity> findByPipelineRunIdAndBatchTypeAndStatusNotIn(Long pipelineRunId,
            ForecastBatchEntity.BatchType batchType, java.util.Collection<BatchStatus> statuses);

    /**
     * Returns the precursor (non-retry) batches for a cycle.
     *
     * <p>The RETRY_FAILED phase selects its retry set from these batches' failed
     * requests only — never from a retry batch's own failures — which makes the
     * single-retry guarantee structural.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @return matching {@code is_retry = false} batches (any status)
     */
    List<ForecastBatchEntity> findByPipelineRunIdAndRetryFalse(Long pipelineRunId);

    /**
     * Returns the retry batches already submitted for a cycle.
     *
     * <p>Used as the idempotency guard: a non-empty result means the RETRY_FAILED
     * phase has already submitted a retry for this cycle, so submission is skipped
     * (the existing retry batch is waited on instead). Bounds each cycle to one
     * retry.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @return matching {@code is_retry = true} batches (any status)
     */
    List<ForecastBatchEntity> findByPipelineRunIdAndRetryTrue(Long pipelineRunId);

    /**
     * Links a submitted batch to its job run, touching that column and nothing else.
     *
     * <p>⚠️ This exists instead of re-saving the entity, and the distinction is load-bearing. The
     * tracking row is persisted the instant Anthropic returns — before job-run bookkeeping — so
     * that polling can discover the batch as early as possible. That means the row is already
     * visible to {@code BatchPollingService} by the time this runs. Re-saving the in-memory entity
     * would merge <em>every</em> column from an instance that still holds the construction-time
     * defaults, so a poller that had meanwhile completed the batch would have its
     * {@code COMPLETED} status, counts and token totals silently reverted to {@code SUBMITTED} —
     * putting already-processed results back in the polling set. A targeted update cannot do that.
     *
     * @param id       the batch primary key
     * @param jobRunId the job run to link
     * @return the number of rows updated (0 if the batch has since been deleted)
     */
    @Modifying
    @Transactional
    @Query("update ForecastBatchEntity b set b.jobRunId = :jobRunId where b.id = :id")
    int linkJobRun(@Param("id") Long id, @Param("jobRunId") Long jobRunId);

    /**
     * The job run ids of every batch a cycle submitted, from a minimum id upwards, whatever the cycle's
     * age. The Runs list hides these from its top level.
     *
     * @param minId the smallest job run id of interest
     * @return the job run ids
     */
    @Query("SELECT b.jobRunId FROM ForecastBatchEntity b WHERE b.pipelineRunId IS NOT NULL "
            + "AND b.jobRunId IS NOT NULL AND b.jobRunId >= :minId")
    List<Long> findCycleBatchJobRunIdsFrom(@Param("minId") long minId);

    /**
     * Of the given Anthropic batch ids, those that belong to a cycle. Backs the Runs list's fallback for a
     * batch whose job run link was never written (matched on the run's {@code notes}).
     *
     * @param anthropicBatchIds the candidate Anthropic batch ids
     * @return the ids that a cycle submitted
     */
    @Query("SELECT b.anthropicBatchId FROM ForecastBatchEntity b WHERE b.pipelineRunId IS NOT NULL "
            + "AND b.anthropicBatchId IN :anthropicBatchIds")
    List<String> findCycleAnthropicBatchIds(@Param("anthropicBatchIds") Collection<String> anthropicBatchIds);

    /**
     * The batches linked to any of the given job runs (one set query for a whole page).
     *
     * @param jobRunIds the job run ids
     * @return the batches
     */
    List<ForecastBatchEntity> findByJobRunIdIn(Collection<Long> jobRunIds);

    /**
     * The batches of any of the given cycles, retries included.
     *
     * @param pipelineRunIds the pipeline run ids
     * @return the batches
     */
    List<ForecastBatchEntity> findByPipelineRunIdIn(Collection<Long> pipelineRunIds);
}
