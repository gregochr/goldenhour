package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunListItem;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.ForecastCycleJobs;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The runs a scheduled job can be proved to have written, for the Scheduler tab's "Last run" column and its
 * panel's "Recent runs".
 *
 * <p>Exactly five jobs are attributable, and each is mapped here and nowhere else:
 * <ul>
 *   <li>the three cycle jobs ({@link ForecastCycleJobs#allCycleJobs()}), to the pipeline runs of the job's
 *       {@link CycleType};</li>
 *   <li>{@value #TIDE_JOB}, to {@code TIDE} job runs with {@code triggeredManually = false};</li>
 *   <li>{@value #AURORA_BATCH_JOB}, to {@code SCHEDULED_BATCH} job runs whose Anthropic batch is of type
 *       {@code AURORA}.</li>
 * </ul>
 * Every other job has no attributable run (the aurora poll, for one, records {@code triggeredManually} as
 * false whatever started it), so it gets no last run and no recent runs.
 *
 * <p>The items are mapped by {@link AdminRunsService}, so a status, a name and a note read here exactly as
 * they do in the Runs list. Reading one job costs a fixed handful of queries however many rows it returns (its runs,
 * then, for a cycle, its batches, their cost runs and the unlinked batches' notes): never one per row.
 *
 * <p>A manual cycle (Run now here, or a catch-up) is a pipeline run like a scheduled one, and {@code pipeline_run}
 * carries no manual flag, so it counts as its cycle job's last run too.
 */
@Service
public class SchedulerRunsService {

    /** The tide refresh job's key. */
    public static final String TIDE_JOB = "tide_refresh";

    /** The aurora batch evaluation job's key. */
    public static final String AURORA_BATCH_JOB = "aurora_batch_evaluation";

    /** The number of recent runs served when the caller gives no limit. */
    public static final int DEFAULT_LIMIT = 3;

    /** The most recent runs served for one job. */
    public static final int MAX_LIMIT = 10;

    /** Where a job's runs are written. */
    private enum Source {
        /** Pipeline runs of one cycle type. */
        CYCLE,
        /** Scheduled (not manual) TIDE job runs. */
        SCHEDULED_TIDE,
        /** SCHEDULED_BATCH job runs whose Anthropic batch is an aurora batch. */
        AURORA_BATCH
    }

    /** A job's attributable runs: where they are written, their cycle type if any, and their Runs group. */
    private record Mapping(Source source, CycleType cycleType, RunGroup group) {
    }

    private final PipelineRunRepository pipelineRuns;
    private final JobRunRepository jobRuns;
    private final SchedulerJobConfigRepository jobConfigs;
    private final AdminRunsService runsService;

    /**
     * Constructs the service.
     *
     * @param pipelineRuns pipeline run store
     * @param jobRuns      job run store
     * @param jobConfigs   scheduled job store, to tell an unknown job from one with no runs
     * @param runsService  maps runs to list items by the Runs list's rules
     */
    public SchedulerRunsService(PipelineRunRepository pipelineRuns, JobRunRepository jobRuns,
            SchedulerJobConfigRepository jobConfigs, AdminRunsService runsService) {
        this.pipelineRuns = pipelineRuns;
        this.jobRuns = jobRuns;
        this.jobConfigs = jobConfigs;
        this.runsService = runsService;
    }

    private static Optional<Mapping> mappingOf(String jobKey) {
        for (ForecastCycleJobs.Job job : ForecastCycleJobs.allCycleJobs()) {
            if (job.key().equals(jobKey)) {
                return Optional.of(new Mapping(Source.CYCLE, job.cycleType(), RunGroup.CYCLES));
            }
        }
        if (TIDE_JOB.equals(jobKey)) {
            return Optional.of(new Mapping(Source.SCHEDULED_TIDE, null, RunGroup.TIDE));
        }
        if (AURORA_BATCH_JOB.equals(jobKey)) {
            return Optional.of(new Mapping(Source.AURORA_BATCH, null, RunGroup.AURORA));
        }
        return Optional.empty();
    }

    /**
     * The Runs group a job's runs belong to, which is also the {@code kind} the Runs tab filters by.
     *
     * @param jobKey the scheduler job key
     * @return the group, or empty for a job with no attributable runs
     */
    public static Optional<RunGroup> groupOf(String jobKey) {
        return mappingOf(jobKey).map(Mapping::group);
    }

    /**
     * The newest runs of a job.
     *
     * @param jobKey the scheduler job key
     * @param limit  how many to serve, already within 1 to {@value #MAX_LIMIT}
     * @return up to {@code limit} runs, newest first; empty for a job with no attributable runs
     */
    public List<RunListItem> recentRuns(String jobKey, int limit) {
        Optional<Mapping> mapping = mappingOf(jobKey);
        if (mapping.isEmpty()) {
            return List.of();
        }
        Pageable page = PageRequest.of(0, limit);
        return switch (mapping.get().source()) {
            case CYCLE -> runsService.cycleItems(
                    pipelineRuns.findByCycleTypeOrderByTriggerTimeDescIdDesc(mapping.get().cycleType(), page));
            case SCHEDULED_TIDE -> runsService.jobItems(
                    jobRuns.findByRunTypeAndTriggeredManuallyOrderByStartedAtDescIdDesc(
                            RunType.TIDE, Boolean.FALSE, page),
                    null);
            case AURORA_BATCH -> runsService.jobItems(
                    jobRuns.findByRunTypeAndBatchType(RunType.SCHEDULED_BATCH, BatchType.AURORA, page),
                    BatchType.AURORA);
        };
    }

    /**
     * The newest runs of a job, for the endpoint that serves them.
     *
     * @param jobKey the scheduler job key
     * @param limit  how many to serve, already within 1 to {@value #MAX_LIMIT}
     * @return the runs (empty for a job with no attributable runs), or empty when no such job exists
     */
    public Optional<List<RunListItem>> recentRunsForJob(String jobKey, int limit) {
        if (jobConfigs.findByJobKey(jobKey).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(recentRuns(jobKey, limit));
    }

    /**
     * The newest run of each of the given jobs that has an attributable run. Each attributable job costs one read of
     * its own (at most five jobs, and a few queries each: its runs, then a cycle's batches and cost runs), never one
     * per job in the list or per row.
     *
     * @param jobKeys the scheduler job keys
     * @return the last run by job key; a job with no attributable run, or none yet, is absent
     */
    public Map<String, RunListItem> lastRuns(Collection<String> jobKeys) {
        Map<String, RunListItem> last = new LinkedHashMap<>();
        for (String jobKey : jobKeys) {
            recentRuns(jobKey, 1).stream().findFirst().ifPresent(item -> last.put(jobKey, item));
        }
        return last;
    }
}
