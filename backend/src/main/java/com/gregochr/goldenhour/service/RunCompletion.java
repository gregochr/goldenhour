package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskSnapshot;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.RunProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.List;

/**
 * The one way a hand-started run (forecast or Bortle enrichment) ends, so that it always tells the
 * admin panel and the {@code job_run} table that it has ended.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #complete} — the normal end. Any task still in a non-terminal state is published
 *       FAILED first (one event per task, through the ordinary {@link LocationTaskEvent} path), so a
 *       run never completes while a task is still showing as in progress; then the {@code job_run}
 *       is closed and the tracker completion is emitted.</li>
 *   <li>{@link #abort} — the end of a run that threw. Each step runs in its own try so one failing
 *       cannot stop the others: sweep the unfinished tasks, tell the tracker (the admin watches
 *       that), then close the {@code job_run} if it is still open.</li>
 * </ul>
 *
 * <p>Both ends are idempotent against each other: the tracker ignores a second completion, and
 * {@link #abort} skips a {@code job_run} that is already closed. {@link #complete} completes the
 * tracker even when closing the {@code job_run} throws (the exception then still propagates to the
 * caller's guard, which retries the close), so the admin hears the run is over whichever step fails.
 *
 * <p>The reasons published here are fixed phrases, never exception messages: they reach every
 * browser subscribed to the run.
 */
public class RunCompletion {

    private static final Logger LOG = LoggerFactory.getLogger(RunCompletion.class);

    /** Reason on a task the run never got to, because the run stopped. */
    public static final String REASON_RUN_STOPPED =
            "Did not finish: the run stopped before this place was evaluated.";

    /** Reason on a task left in EVALUATING when the run otherwise completed. */
    public static final String REASON_EVALUATION_FAILED = "Evaluation failed (see server log).";

    /** Reason on a task left in any other non-terminal state when the run otherwise completed. */
    public static final String REASON_NOT_FINISHED = "Did not finish (see server log).";

    /** Run-level reason when Open-Meteo could not be reached. */
    public static final String REASON_OPEN_METEO =
            "Weather data (Open-Meteo) could not be fetched; nothing was updated.";

    /** Run-level reason for any other unexpected failure. */
    public static final String REASON_UNEXPECTED = "The run stopped unexpectedly. See the server log.";

    /** How far down a cause chain {@link #reasonForForecastRun} looks (it also bounds a cyclic chain). */
    private static final int MAX_CAUSE_DEPTH = 10;

    private final JobRunService jobRunService;
    private final RunProgressTracker progressTracker;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Constructs the helper over the collaborators a run already holds.
     *
     * @param jobRunService   closes the {@code job_run}
     * @param progressTracker the live-progress tracker
     * @param eventPublisher  carries the per-task FAILED events to the tracker
     */
    public RunCompletion(JobRunService jobRunService, RunProgressTracker progressTracker,
            ApplicationEventPublisher eventPublisher) {
        this.jobRunService = jobRunService;
        this.progressTracker = progressTracker;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Picks the run-level reason for a forecast run that threw: Open-Meteo wording when the failure
     * is a weather fetch (a {@link WeatherDataFetchException} or a {@link RestClientException}
     * anywhere in the cause chain), otherwise the generic one. Never the exception's own message.
     *
     * @param cause what escaped the pipeline
     * @return a fixed, safe phrase
     */
    public static String reasonForForecastRun(Throwable cause) {
        Throwable t = cause;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (t instanceof WeatherDataFetchException || t instanceof RestClientException) {
                return REASON_OPEN_METEO;
            }
            t = t.getCause();
        }
        return REASON_UNEXPECTED;
    }

    /**
     * Ends a run normally: fails any task still in progress, closes the {@code job_run}, then emits
     * the tracker completion.
     *
     * @param jobRun    the run
     * @param succeeded the {@code job_run} success count
     * @param failed    the {@code job_run} failure count
     * @param dates     the target dates evaluated, or null
     */
    public void complete(JobRunEntity jobRun, int succeeded, int failed, List<LocalDate> dates) {
        sweepUnfinished(jobRun.getId(), false);
        try {
            jobRunService.completeRun(jobRun, succeeded, failed, dates);
        } finally {
            // The admin watches the tracker: it must hear the run is over even when closing the
            // job_run threw. The exception still propagates, to the executor's guard.
            progressTracker.completeRun(jobRun.getId());
        }
    }

    /**
     * Ends a run normally, without date tracking.
     *
     * @param jobRun    the run
     * @param succeeded the {@code job_run} success count
     * @param failed    the {@code job_run} failure count
     */
    public void complete(JobRunEntity jobRun, int succeeded, int failed) {
        sweepUnfinished(jobRun.getId(), false);
        try {
            jobRunService.completeRun(jobRun, succeeded, failed);
        } finally {
            progressTracker.completeRun(jobRun.getId());
        }
    }

    /**
     * Ends a run that threw. Best-effort and never throws: (a) every unfinished task is published
     * FAILED, (b) the tracker is told the run failed (registering it first if it never got that far),
     * (c) the {@code job_run} is closed unless something already closed it. The {@code job_run}'s
     * counts are the tracker's completed and failed task counts, or zero when the tracker never held
     * the run.
     *
     * @param jobRun the run
     * @param reason a fixed, safe phrase for the reader
     */
    public void abort(JobRunEntity jobRun, String reason) {
        long id = jobRun.getId();
        sweepUnfinished(id, true);
        try {
            progressTracker.failRun(id, reason);
        } catch (RuntimeException e) {
            LOG.warn("Run {} abort: could not complete the progress tracker: {}", id, e.getMessage(), e);
        }
        try {
            if (jobRun.getCompletedAt() == null) {
                RunProgress progress = progressTracker.getProgress(id);
                int succeeded = progress != null ? progress.getCompleted() : 0;
                int failed = progress != null ? progress.getFailed() : 0;
                jobRunService.completeRun(jobRun, succeeded, failed);
            }
        } catch (RuntimeException e) {
            LOG.warn("Run {} abort: could not close the job_run: {}", id, e.getMessage(), e);
        }
    }

    /**
     * Publishes FAILED for every task not yet in a terminal state, one event per task, so the
     * tracker (which broadcasts only the most recently updated snapshot) shows each of them.
     * COMPLETE, SKIPPED, TRIAGED and FAILED tasks are left alone. A task whose event cannot be
     * published is logged and does not stop the others.
     */
    private void sweepUnfinished(long jobRunId, boolean aborted) {
        RunProgress progress = progressTracker.getProgress(jobRunId);
        if (progress == null) {
            return;
        }
        for (LocationTaskSnapshot task : progress.getTasks().values()) {
            if (isTerminal(task.state())) {
                continue;
            }
            String reason = aborted ? REASON_RUN_STOPPED
                    : task.state() == LocationTaskState.EVALUATING
                            ? REASON_EVALUATION_FAILED : REASON_NOT_FINISHED;
            try {
                eventPublisher.publishEvent(new LocationTaskEvent(
                        this, jobRunId, task.taskKey(), task.locationName(), task.targetDate(),
                        task.targetType(), LocationTaskState.FAILED, reason, task.state().name()));
            } catch (RuntimeException e) {
                LOG.warn("Run {}: could not fail unfinished task {}: {}",
                        jobRunId, task.taskKey(), e.getMessage(), e);
            }
        }
    }

    private static boolean isTerminal(LocationTaskState state) {
        return state == LocationTaskState.COMPLETE
                || state == LocationTaskState.FAILED
                || state == LocationTaskState.SKIPPED
                || state == LocationTaskState.TRIAGED;
    }
}
