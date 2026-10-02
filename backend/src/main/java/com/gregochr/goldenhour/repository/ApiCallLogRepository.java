package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.BatchCallOutcome;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository for {@link ApiCallLogEntity}.
 */
public interface ApiCallLogRepository extends JpaRepository<ApiCallLogEntity, Long> {

    /**
     * Finds all API calls for a given job run, ordered by call time ascending.
     *
     * @param jobRunId the job run ID to filter by
     * @return list of API call logs
     */
    List<ApiCallLogEntity> findByJobRunIdOrderByCalledAtAsc(Long jobRunId);

    /**
     * Finds recent API calls for a given service, ordered descending.
     *
     * @param service the service name to filter by
     * @param pageable pagination configuration
     * @return list of API call logs
     */
    List<ApiCallLogEntity> findByServiceOrderByCalledAtDesc(ServiceName service, Pageable pageable);

    /**
     * Counts failed API calls for a given service.
     *
     * @param service the service name to filter by
     * @return count of failed calls
     */
    long countByServiceAndSucceededIsFalse(ServiceName service);

    /**
     * Finds the genuinely-failed batch requests across the given Anthropic batch
     * ids — the candidate set for the RETRY_FAILED phase.
     *
     * <p>Selects only rows that were <em>attempted and failed</em>: a batch call
     * ({@code is_batch = true}) that did not succeed ({@code succeeded = false}).
     * Deliberate skips are structurally excluded — a {@code SKIPPED_*} candidate is
     * never sent to the model, so it has no {@code api_call_log} row at all. The
     * caller restricts the batch-id set to the cycle's precursor (non-retry)
     * batches and de-duplicates by {@code custom_id}.
     *
     * @param batchIds Anthropic batch ids ({@code msgbatch_*}) to search within
     * @return failed batch-call rows (any error_type), newest first
     */
    @Query("SELECT a FROM ApiCallLogEntity a "
            + "WHERE a.isBatch = true AND a.succeeded = false "
            + "AND a.batchId IN :batchIds "
            + "ORDER BY a.calledAt DESC")
    List<ApiCallLogEntity> findFailedBatchCalls(@Param("batchIds") Collection<String> batchIds);

    /**
     * Returns every custom_id logged against the given Anthropic batch — succeeded and failed
     * alike. Used by the R7(a) abandonment sweep to identify every request the batch touched, so
     * whichever {@code fc-} ones never reached {@code SCORED} can be closed out.
     *
     * @param batchId the Anthropic batch id ({@code msgbatch_*})
     * @return every custom_id logged against that batch
     */
    @Query("SELECT a.customId FROM ApiCallLogEntity a WHERE a.batchId = :batchId")
    List<String> findCustomIdsByBatchId(@Param("batchId") String batchId);

    /**
     * Returns the outcome of every individual request logged against the given Anthropic batches,
     * succeeded and failed alike, projected to the three columns a caller needs so the (potentially
     * large) response bodies are never loaded.
     *
     * <p>Used by {@code CycleLocationOutcomeResolver} to decide, per place, whether a scheduled
     * cycle's requests got through. Rows without a {@code custom_id} cannot be attributed to a
     * place and are excluded here rather than in every caller.
     *
     * @param batchIds Anthropic batch ids ({@code msgbatch_*}) belonging to one pipeline cycle
     * @return one {@link BatchCallOutcome} per logged request
     */
    @Query("SELECT new com.gregochr.goldenhour.model.BatchCallOutcome("
            + "a.customId, a.succeeded, a.errorType) "
            + "FROM ApiCallLogEntity a "
            + "WHERE a.isBatch = true AND a.batchId IN :batchIds AND a.customId IS NOT NULL")
    List<BatchCallOutcome> findBatchCallOutcomes(@Param("batchIds") Collection<String> batchIds);
}
