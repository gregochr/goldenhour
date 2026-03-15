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

    /**
     * Constructs a new run progress tracker for a job run.
     *
     * @param jobRunId the job run ID
     */
    public RunProgress(long jobRunId) {
        this.jobRunId = jobRunId;
        this.startedAt = Instant.now();
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
     * Returns the number of tasks currently in progress (not PENDING/COMPLETE/FAILED/SKIPPED).
     *
     * @return in-progress task count
     */
    public int getInProgress() {
        return (int) tasks.values().stream()
                .map(LocationTaskSnapshot::state)
                .filter(s -> s != LocationTaskState.PENDING
                        && s != LocationTaskState.COMPLETE
                        && s != LocationTaskState.FAILED
                        && s != LocationTaskState.SKIPPED)
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
     * Derives the run status from the aggregated task states.
     *
     * @return the derived run status
     */
    public RunStatus getStatus() {
        int total = getTotal();
        if (total == 0) {
            return RunStatus.COMPLETE;
        }
        int completed = getCompleted();
        int failed = getFailed();
        int skipped = getSkipped();
        int finished = completed + failed + skipped;

        if (finished < total) {
            return RunStatus.RUNNING;
        }
        if (failed == 0) {
            return RunStatus.COMPLETE;
        }
        if (completed == 0 && skipped == 0) {
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
