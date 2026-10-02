package com.gregochr.goldenhour.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the live progress of a single forecast run.
 *
 * <p>Thread-safe — all state is in a {@link ConcurrentHashMap}. Derived counters
 * and status are computed on-the-fly from the task map.
 */
public class RunProgress {

    /** Derived run status based on aggregated task states. */
    public enum RunStatus {
        /** At least one task is still in progress. */
        RUNNING,
        /** All tasks completed successfully (or were skipped). */
        COMPLETE,
        /** Some tasks completed, some failed. */
        PARTIAL,
        /** All tasks failed. */
        FAILED
    }

    private final long jobRunId;
    private final ConcurrentHashMap<String, LocationTaskSnapshot> tasks = new ConcurrentHashMap<>();
    private final Instant startedAt;
    private volatile RunPhase phase = RunPhase.TRIAGE;
    private volatile String failureReason;
    private volatile boolean stopped;
    private final Object streamLock = new Object();
    private final Object retryLock = new Object();
    private volatile Instant lastActivityAt;
    private volatile Instant completedAt;
    private volatile Long retriedAs;
    private volatile Instant retriedAt;

    /**
     * Constructs a new run progress tracker for a job run.
     *
     * @param jobRunId the job run ID
     */
    public RunProgress(long jobRunId) {
        this.jobRunId = jobRunId;
        this.startedAt = Instant.now();
        this.lastActivityAt = this.startedAt;
    }

    /**
     * Registers a task as PENDING.
     *
     * @param taskKey      the unique task key
     * @param locationName the location name
     * @param targetDate   the target date
     * @param targetType   SUNRISE, SUNSET, or HOURLY
     */
    public void registerTask(String taskKey, String locationName,
            String targetDate, String targetType) {
        tasks.put(taskKey, new LocationTaskSnapshot(
                taskKey, locationName, targetDate, targetType,
                LocationTaskState.PENDING, null, null, Instant.now()));
    }

    /**
     * Updates a task's state from an event.
     *
     * @param event the task event
     */
    public void updateTask(LocationTaskEvent event) {
        tasks.put(event.getTaskKey(), LocationTaskSnapshot.fromEvent(event));
    }

    /**
     * The monitor that orders this run's per-task broadcasts against a subscriber's replay: every
     * "update a task and tell the subscribers" and every "copy the tasks and replay them to a new
     * subscriber" holds it, so a subscriber sees each task's states in the order they happened. One
     * per run, never shared between runs. See {@code RunProgressTracker}'s class javadoc for the lock
     * order against its completion lock.
     *
     * @return the run's stream lock
     */
    public Object streamLock() {
        return streamLock;
    }

    /**
     * The monitor that makes "retry this run's failures" a single decision: whoever holds it checks
     * whether a retry has already been started from this run, starts one, and records it with
     * {@link #recordRetry} before releasing it, so two requests cannot both start one. One per run.
     *
     * @return the run's retry lock
     */
    public Object retryLock() {
        return retryLock;
    }

    /**
     * Records the run that retried this one. Call it only while holding {@link #retryLock()}.
     * Held in memory with this progress entry, so it is forgotten when the entry is evicted and does
     * not survive a restart.
     *
     * @param retryJobRunId the job run id of the retry that was started
     * @param at            when it was started
     */
    public void recordRetry(long retryJobRunId, Instant at) {
        this.retriedAs = retryJobRunId;
        this.retriedAt = at;
    }

    /**
     * When the retry recorded by {@link #recordRetry} was started.
     *
     * @return the instant, or {@code null} when no retry has been started from this run
     */
    public Instant getRetriedAt() {
        return retriedAt;
    }

    /**
     * The job run that retried this run, if a retry has been started from it.
     *
     * @return the retry's job run id, or {@code null} when none has been started
     */
    public Long getRetriedAs() {
        return retriedAs;
    }

    /**
     * Notes that something happened to this run (a task changed, the phase moved), so it is not idle.
     * Fed by the tracker's clock rather than the system's, so eviction can be tested without sleeping.
     *
     * @param at when the activity happened
     */
    public void touch(Instant at) {
        this.lastActivityAt = at;
    }

    /**
     * Returns when this run last did something: registered, changed a task or moved phase.
     *
     * @return the instant of the latest activity
     */
    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    /**
     * Records when the run completed, which starts the clock on how long its entry is retained.
     *
     * @param at the completion instant
     */
    public void markCompleted(Instant at) {
        this.completedAt = at;
    }

    /**
     * Returns when the run completed.
     *
     * @return the completion instant, or {@code null} while the run has not completed
     */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * Returns the job run ID.
     *
     * @return the job run ID
     */
    public long getJobRunId() {
        return jobRunId;
    }

    /**
     * Returns all task snapshots as an unmodifiable map.
     *
     * @return map of task key to snapshot
     */
    public Map<String, LocationTaskSnapshot> getTasks() {
        return Map.copyOf(tasks);
    }

    /**
     * Returns when this run started.
     *
     * @return the start instant
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * Returns the total number of tasks.
     *
     * @return total task count
     */
    public int getTotal() {
        return tasks.size();
    }

    /**
     * Returns the number of completed tasks.
     *
     * @return completed task count
     */
    public int getCompleted() {
        return countByState(LocationTaskState.COMPLETE);
    }

    /**
     * Returns the number of failed tasks.
     *
     * @return failed task count
     */
    public int getFailed() {
        return countByState(LocationTaskState.FAILED);
    }

    /**
     * Returns the number of skipped tasks.
     *
     * @return skipped task count
     */
    public int getSkipped() {
        return countByState(LocationTaskState.SKIPPED);
    }

    /**
     * Returns the number of triaged tasks.
     *
     * @return triaged task count
     */
    public int getTriaged() {
        return countByState(LocationTaskState.TRIAGED);
    }

    /**
     * Returns the current run phase.
     *
     * @return the run phase
     */
    public RunPhase getPhase() {
        return phase;
    }

    /**
     * Sets the current run phase.
     *
     * @param phase the new phase
     */
    public void setPhase(RunPhase phase) {
        this.phase = phase;
    }

    /**
     * Returns the number of tasks currently in progress (not terminal states).
     *
     * @return in-progress task count
     */
    public int getInProgress() {
        return (int) tasks.values().stream()
                .map(LocationTaskSnapshot::state)
                .filter(s -> s != LocationTaskState.PENDING
                        && s != LocationTaskState.COMPLETE
                        && s != LocationTaskState.FAILED
                        && s != LocationTaskState.SKIPPED
                        && s != LocationTaskState.TRIAGED)
                .count();
    }

    /**
     * Returns the elapsed time since the run started, in milliseconds.
     *
     * @return elapsed milliseconds
     */
    public long getElapsedMs() {
        return Instant.now().toEpochMilli() - startedAt.toEpochMilli();
    }

    /**
     * Records that the run as a whole failed, independent of what its tasks say.
     *
     * <p>A run can fail before it has any task (the tracker then holds zero tasks, which would
     * otherwise read as a clean {@link RunStatus#COMPLETE}) or part-way through. Once set,
     * {@link #getStatus()} never reports {@code COMPLETE} or {@code RUNNING} for this run.
     *
     * @param reason a fixed, safe phrase describing why the run stopped (never a raw exception message)
     */
    public synchronized void markFailed(String reason) {
        if (stopped) {
            return; // the stop reason names what ended the run, and nothing later is more specific
        }
        this.failureReason = reason;
    }

    /**
     * Stops this run: Claude rejected the API key, so every further evaluation would fail the same
     * way and none is attempted. Sets the run-level failure reason (which {@link #markFailed} will not
     * overwrite from here on) and makes the run non-retryable.
     *
     * <p>Scoped to this one run's progress object, so two overlapping runs do not affect each other
     * and a later run starts unstopped.
     *
     * @param reason a fixed, safe phrase describing why the run stopped
     * @return {@code true} if this call stopped the run, {@code false} if it was already stopped
     */
    public synchronized boolean stop(String reason) {
        if (stopped) {
            return false;
        }
        this.failureReason = reason;
        this.stopped = true;
        return true;
    }

    /**
     * Whether the run was stopped because Claude rejected the API key.
     *
     * @return {@code true} once {@link #stop} has been called
     */
    public boolean isStopped() {
        return stopped;
    }

    /**
     * Why "Retry failed" is not offered for this run; the one answer both the {@code run-complete}
     * payload and the retry endpoint read, so the panel and the server cannot disagree.
     */
    public enum RetryBlock {
        /** The run was stopped on a rejected API key: re-running its places fails them the same way. */
        API_KEY_REJECTED,
        /** A light-pollution (Bortle) run: its failed tasks are locations, not forecast slots. */
        LIGHT_POLLUTION,
        /** Some other run whose failed tasks are not sunrise/sunset slots, so there is no slot to re-run. */
        NOT_FORECAST_SLOTS
    }

    /**
     * Why "Retry failed" cannot do any good for this run, or {@code null} when it can.
     *
     * <p>Blocked when the run was stopped on a rejected key (the places it would re-run fail the same
     * way until the key is fixed and a new run is started), and when a failed task is not a forecast
     * slot: retry re-runs (location, date, sunrise/sunset) triples, and a Bortle task has a location
     * and no date or event.
     *
     * @return the reason retry is not offered, or {@code null}
     */
    public RetryBlock getRetryBlock() {
        if (stopped) {
            return RetryBlock.API_KEY_REJECTED;
        }
        RetryBlock block = null;
        for (LocationTaskSnapshot task : getFailedTasks()) {
            String type = task.targetType();
            if ("BORTLE".equals(type)) {
                return RetryBlock.LIGHT_POLLUTION;
            }
            if (!"SUNRISE".equals(type) && !"SUNSET".equals(type)) {
                block = RetryBlock.NOT_FORECAST_SLOTS;
            }
        }
        return block;
    }

    /**
     * Whether "Retry failed" can do any good for this run.
     *
     * @return {@code true} when {@link #getRetryBlock()} is {@code null}
     */
    public boolean isRetryable() {
        return getRetryBlock() == null;
    }

    /**
     * Returns the run-level failure reason, or null if the run did not fail as a whole.
     *
     * @return the reason, or null
     */
    public String getFailureReason() {
        return failureReason;
    }

    /**
     * Derives the run status from the aggregated task states and any run-level failure.
     *
     * <p>With a run-level failure the status is {@code PARTIAL} when some task completed or was
     * triaged before the run stopped, otherwise {@code FAILED} (including a run with no tasks).
     * Without one, every task must be finished for the run to leave {@code RUNNING}; a run in which
     * something failed is {@code FAILED} when nothing completed or was triaged (a skipped slot is
     * not an outcome, so skips alongside failures do not soften it) and {@code PARTIAL} otherwise.
     *
     * @return the derived run status
     */
    public RunStatus getStatus() {
        int completed = getCompleted();
        int triaged = getTriaged();
        if (failureReason != null) {
            return completed == 0 && triaged == 0 ? RunStatus.FAILED : RunStatus.PARTIAL;
        }
        int total = getTotal();
        if (total == 0) {
            return RunStatus.COMPLETE;
        }
        int failed = getFailed();
        int skipped = getSkipped();
        int finished = completed + failed + skipped + triaged;

        if (finished < total) {
            return RunStatus.RUNNING;
        }
        if (failed == 0) {
            return RunStatus.COMPLETE;
        }
        if (completed == 0 && triaged == 0) {
            return RunStatus.FAILED;
        }
        return RunStatus.PARTIAL;
    }

    /**
     * Returns snapshots of all failed tasks.
     *
     * @return list of failed task snapshots
     */
    public List<LocationTaskSnapshot> getFailedTasks() {
        return tasks.values().stream()
                .filter(s -> s.state() == LocationTaskState.FAILED)
                .toList();
    }

    private int countByState(LocationTaskState state) {
        return (int) tasks.values().stream()
                .filter(s -> s.state() == state)
                .count();
    }
}
