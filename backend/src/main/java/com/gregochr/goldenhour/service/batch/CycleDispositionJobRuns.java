package com.gregochr.goldenhour.service.batch;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers, per pipeline cycle, the job run its dispositions were persisted on.
 *
 * <p>A cycle's {@code forecast_run_disposition} rows hang off the job run of its first submitted
 * batch, which is reachable from the pipeline run through that batch's {@code forecast_batch} row.
 * A cycle that submits no batch at all (everything cached, skipped or triaged away, or every
 * submission failed) has no such row: its dispositions go on a disposition-only <em>anchor</em> job
 * run created by {@code JobRunService.startDispositionAnchorRun}, and nothing in the database ties
 * that run to the pipeline run. Neither {@code job_run} nor {@code pipeline_run} has a column that
 * can hold the link (there is no {@code pipeline_run_id} on {@code job_run}; the only free columns
 * are text, and the link is not to be parsed out of a sentence), and adding one needs a migration.
 *
 * <p>So the link lives here, in memory: {@code ScheduledBatchEvaluationService} records it when it
 * persists a cycle's dispositions, and {@code CycleLocationOutcomeResolver} reads it when it
 * settles the cycle. It is bounded to the last {@value #REMEMBERED} cycles and <b>lost on a
 * restart</b>. That is the safe direction: a cycle whose link is lost resolves from its batches
 * alone (nothing, for a batchless cycle), so it counts nobody and resets nobody, which can only
 * under-count a streak. The settle runs minutes to hours after the submission, in the same process
 * unless the app restarted in between.
 */
@Component
public class CycleDispositionJobRuns {

    /** How many cycles are remembered; cycles run twice a day, so this is months. */
    static final int REMEMBERED = 200;

    private final Map<Long, Long> jobRunByPipelineRun = new LinkedHashMap<>();

    /**
     * Records the job run a cycle's dispositions were persisted on.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @param jobRunId      the job run holding the cycle's dispositions
     */
    public synchronized void remember(Long pipelineRunId, Long jobRunId) {
        jobRunByPipelineRun.remove(pipelineRunId);
        jobRunByPipelineRun.put(pipelineRunId, jobRunId);
        if (jobRunByPipelineRun.size() > REMEMBERED) {
            Iterator<Long> oldest = jobRunByPipelineRun.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /**
     * Returns the job run a cycle's dispositions were persisted on, if this process recorded it.
     *
     * @param pipelineRunId the orchestrated cycle id
     * @return the job run id, or empty if the cycle persisted none or the link was lost
     */
    public synchronized Optional<Long> jobRunFor(Long pipelineRunId) {
        return Optional.ofNullable(jobRunByPipelineRun.get(pipelineRunId));
    }
}
