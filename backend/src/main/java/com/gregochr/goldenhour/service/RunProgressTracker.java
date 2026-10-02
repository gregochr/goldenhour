package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskSnapshot;
import com.gregochr.goldenhour.model.RunProgress;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
// Note: ObjectMapper is created inline with JavaTimeModule rather than injected,
// because this service only serialises SSE payloads and does not need the full
// Spring-configured ObjectMapper.
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Singleton service that tracks live progress of forecast runs and broadcasts
 * state changes via Server-Sent Events.
 *
 * <p>Listens for {@link LocationTaskEvent} application events, updates the
 * in-memory {@link RunProgress}, and pushes SSE messages to subscribed clients.
 * Entries are evicted by {@link #cleanupStaleEntries()}: a COMPLETED run
 * {@link #COMPLETED_RUN_RETENTION} after it completed, and a run that never completed
 * {@link #IDLE_RUN_TTL} after its last activity. A run that is still active is never evicted.
 *
 * <p>A subscriber that arrives after a run has finished is not left waiting for an event that
 * already went out: it is sent the retained task snapshots, the run summary and the SAME
 * {@code run-complete} payload live subscribers received. A subscriber to an id the tracker does
 * not hold (evicted, restarted away, or never a tracked run) is sent a terminal
 * {@code run-expired} event once a short grace period shows it is not merely a run about to start.
 *
 * <p><b>Locking.</b> Two kinds of lock, and one order. {@code completionLock} is global and orders
 * "a run completes" against "a subscriber arrives" and the expiry check. {@link RunProgress#streamLock()}
 * is per run and orders "a task changes and is broadcast" against "a subscriber's replay copies the
 * tasks and sends them", so a subscriber sees each task's states in the order they happened.
 * <b>Order: {@code completionLock} before the run's stream lock, never the reverse.</b>
 * {@code subscribe}, {@code completeRun} and {@code failRun} take both in that order; {@code onTaskEvent}
 * and {@code setPhase} take only the stream lock and never call anything that takes the completion lock
 * while holding it; the expiry check takes only the completion lock. So no thread holds a stream lock
 * while waiting for the completion lock, and there is no cycle. No lock is held across runs. The cost:
 * a subscriber whose socket stalls inside {@code send} holds its run's stream lock, delaying that run's
 * other task events (as a stalled emitter already delayed the worker that was sending to it).
 */
@Service
public class RunProgressTracker {

    private static final Logger LOG = LoggerFactory.getLogger(RunProgressTracker.class);

    /**
     * How long a COMPLETED run's entry is kept, measured from when the run completed (never from when
     * it started): the window in which a late subscriber is replayed the run and "Retry failed" can
     * still read its failed tasks. Unchanged from the 30 minutes every run used to get from its start.
     */
    static final Duration COMPLETED_RUN_RETENTION = Duration.ofMinutes(30);

    /**
     * How long a run that has NOT completed may go without any activity (a task event or a phase
     * change) before its entry is evicted; the only way such a run is ever evicted, since a run that is
     * still active is never evicted on age.
     *
     * <p>Deliberately long, because an entry evicted while its run is alive never gets its
     * {@code run-complete}. A live run has two silent phases. Between {@code initRun} and the first
     * {@code setPhase(TRIAGE)} the weather and cloud prefetch runs and records nothing: Open-Meteo is
     * called in small chunks with a 3 s gap and a 61 s backoff on each rate-limit response, so a large
     * prefetch under repeated 429s can be silent for a long time. During evaluation the silence is one
     * task's wait for a permit on the {@code claude} bulkhead (120 s) plus one call (90 s call timeout,
     * up to four attempts), about 8 minutes. The prefetch is the one with no tight ceiling, so the bound
     * is three hours rather than a multiple of the evaluation figure.
     *
     * <p>A generous bound costs nothing: a run always completes (its executor completes it whatever
     * happens), unless the JVM dies, and then the in-memory tracker is gone as well, so an entry that
     * never completes is almost impossible and this only bounds the memory of a leaked one. Cleanup runs
     * every five minutes, so eviction can be that much late.
     */
    static final Duration IDLE_RUN_TTL = Duration.ofHours(3);

    /**
     * How long a subscriber to an id the tracker does not (yet) hold waits before being told the run
     * is gone. A client subscribes the moment the run endpoint answers 202, and the executor thread
     * registers the run with {@link #initRun} a moment after that, so an unknown id at subscribe
     * time can be a run about to start; the grace tells the two apart.
     */
    static final long UNKNOWN_RUN_GRACE_MS = 5_000L;

    private final DynamicSchedulerService dynamicSchedulerService;
    private final ScheduledExecutorService graceScheduler;
    private final long unknownRunGraceMs;
    private final Clock clock;

    /**
     * The {@code run-complete} payload each finished run broadcast, kept until the run is evicted so a
     * late subscriber is replayed exactly what live subscribers got (the payload's elapsed time is
     * computed once, at completion, never again).
     */
    private final ConcurrentHashMap<Long, Map<String, Object>> completions = new ConcurrentHashMap<>();

    /** Orders "run completes" against "subscriber arrives" so neither is sent the event twice or never. */
    private final Object completionLock = new Object();

    private final ConcurrentHashMap<Long, RunProgress> activeRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> runEmitters =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> notificationEmitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Constructs the run progress tracker.
     *
     * @param dynamicSchedulerService the dynamic scheduler for job registration
     */
    @Autowired
    public RunProgressTracker(DynamicSchedulerService dynamicSchedulerService) {
        this(dynamicSchedulerService, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "run-progress-grace");
            t.setDaemon(true);
            return t;
        }), UNKNOWN_RUN_GRACE_MS);
    }

    /**
     * Constructs the tracker with an explicit grace scheduler (tests supply their own) and the system clock.
     *
     * @param dynamicSchedulerService the dynamic scheduler for job registration
     * @param graceScheduler          runs the unknown-run expiry check
     * @param unknownRunGraceMs       the grace period before an unknown run is declared expired
     */
    RunProgressTracker(DynamicSchedulerService dynamicSchedulerService,
            ScheduledExecutorService graceScheduler, long unknownRunGraceMs) {
        this(dynamicSchedulerService, graceScheduler, unknownRunGraceMs, Clock.systemUTC());
    }

    /**
     * Constructs the tracker with an explicit grace scheduler and clock (tests supply their own).
     *
     * @param dynamicSchedulerService the dynamic scheduler for job registration
     * @param graceScheduler          runs the unknown-run expiry check
     * @param unknownRunGraceMs       the grace period before an unknown run is declared expired
     * @param clock                   the time source for completion and activity instants, so eviction can
     *                                be tested without sleeping
     */
    RunProgressTracker(DynamicSchedulerService dynamicSchedulerService,
            ScheduledExecutorService graceScheduler, long unknownRunGraceMs, Clock clock) {
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.graceScheduler = graceScheduler;
        this.unknownRunGraceMs = unknownRunGraceMs;
        this.clock = clock;
    }

    /**
     * Stops the grace scheduler on shutdown.
     */
    @PreDestroy
    void shutdown() {
        graceScheduler.shutdownNow();
    }

    /**
     * Creates the emitter handed to a subscriber (overridable so tests can observe what is sent).
     *
     * @return a new emitter with no timeout
     */
    SseEmitter newEmitter() {
        return new SseEmitter(0L);
    }

    /**
     * Registers the cleanup job with the dynamic scheduler.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget("run_progress_cleanup",
                this::cleanupStaleEntries);
    }

    /**
     * Initialises tracking for a new run with all tasks set to PENDING.
     *
     * @param jobRunId the job run ID
     * @param tasks    list of [taskKey, locationName, targetDate, targetType] arrays
     */
    public void initRun(long jobRunId, List<String[]> tasks) {
        RunProgress progress = new RunProgress(jobRunId);
        progress.touch(clock.instant());
        for (String[] task : tasks) {
            progress.registerTask(task[0], task[1], task[2], task[3]);
        }
        activeRuns.put(jobRunId, progress);
        LOG.info("Run progress tracker initialised for jobRunId={} with {} tasks",
                jobRunId, tasks.size());
    }

    /**
     * Handles a location task event — updates state and broadcasts via SSE.
     *
     * @param event the task state transition event
     */
    @EventListener
    public void onTaskEvent(LocationTaskEvent event) {
        RunProgress progress = activeRuns.get(event.getJobRunId());
        if (progress == null) {
            return;
        }
        // Update and broadcast as one step under the run's stream lock: a subscriber's replay holds the
        // same lock across "copy the tasks, send them", so it can never send a task's older copy AFTER
        // this event's newer one.
        synchronized (progress.streamLock()) {
            progress.touch(clock.instant());
            progress.updateTask(event);
            broadcastTaskUpdate(event.getJobRunId(), progress, event.getTaskKey());
        }
    }

    /**
     * Marks a run as complete and broadcasts the final run-complete event.
     *
     * <p>Idempotent: a run that has already completed (normally, or through {@link #failRun}) is
     * left exactly as it was and nothing is broadcast a second time, so the executor's normal
     * completion and its abort guard can both call this without coordinating.
     *
     * @param jobRunId the job run ID
     */
    public void completeRun(long jobRunId) {
        RunProgress progress = activeRuns.get(jobRunId);
        if (progress == null) {
            return;
        }
        synchronized (completionLock) {
            if (completions.containsKey(jobRunId)) {
                return;
            }
            synchronized (progress.streamLock()) {
                progress.markCompleted(clock.instant());
                Map<String, Object> completeEvent = buildRunCompleteEvent(jobRunId, progress);
                completions.put(jobRunId, completeEvent);
                broadcastRunComplete(jobRunId, completeEvent);
            }
        }
    }

    /**
     * Records a run-level failure reason on a run that carries on (the run is not completed, and
     * nothing is broadcast until it is). Used when a shared step failed but the run can still
     * finish task by task, so the eventual {@code run-complete} says why. No-op for an unknown run.
     *
     * @param jobRunId the job run ID
     * @param reason   a fixed, safe phrase for the reader
     */
    public void noteFailure(long jobRunId, String reason) {
        RunProgress progress = activeRuns.get(jobRunId);
        if (progress != null) {
            progress.markFailed(reason);
        }
    }

    /**
     * Stops a run because Claude rejected the API key: records the run-level reason (carried by the
     * eventual {@code run-complete}, which then reports the run as not retryable) and makes
     * {@link #isStopped} true so no further Claude call is made for this run. Nothing is broadcast
     * until the run completes. No-op for an unknown run, and for a run already stopped.
     *
     * @param jobRunId the job run ID
     * @return {@code true} if this call stopped the run; {@code false} if it was already stopped or
     *         is not tracked
     */
    public boolean stopRun(long jobRunId) {
        RunProgress progress = activeRuns.get(jobRunId);
        return progress != null && progress.stop(EvaluationFailure.REASON_RUN_STOPPED);
    }

    /**
     * Whether a run has been stopped on a rejected key. Run-scoped: it reads this run's own progress,
     * so another run, or a run started later, is not affected.
     *
     * @param jobRunId the job run ID
     * @return {@code true} if the run is tracked and stopped
     */
    public boolean isStopped(long jobRunId) {
        RunProgress progress = activeRuns.get(jobRunId);
        return progress != null && progress.isStopped();
    }

    /**
     * Marks a run as having failed as a whole and broadcasts the final run-complete event carrying
     * that failure.
     *
     * <p>Works whether or not the run was ever registered: a run that failed before
     * {@link #initRun} is registered here with no tasks, so the panel is told the run failed
     * rather than being left to the {@code run-expired} path. A run that has already completed is
     * left untouched (first completion wins).
     *
     * @param jobRunId the job run ID
     * @param reason   a fixed, safe phrase for the reader (never a raw exception message)
     */
    public void failRun(long jobRunId, String reason) {
        synchronized (completionLock) {
            if (completions.containsKey(jobRunId)) {
                return;
            }
            RunProgress progress = activeRuns.computeIfAbsent(jobRunId, RunProgress::new);
            progress.markFailed(reason);
            synchronized (progress.streamLock()) {
                progress.markCompleted(clock.instant());
                Map<String, Object> completeEvent = buildRunCompleteEvent(jobRunId, progress);
                completions.put(jobRunId, completeEvent);
                broadcastRunComplete(jobRunId, completeEvent);
            }
        }
    }

    /**
     * Subscribes an SSE emitter to a specific run's progress updates.
     *
     * @param runId the job run ID
     * @return the SSE emitter, or null if the run is not tracked
     */
    public SseEmitter subscribe(long runId) {
        SseEmitter emitter = newEmitter();

        synchronized (completionLock) {
            // Taken under the completion lock, so cleanup (which drops a run's empty emitter list under
            // the same lock) cannot remove the list between this lookup and the add below.
            CopyOnWriteArrayList<SseEmitter> emitters =
                    runEmitters.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
            emitter.onCompletion(() -> emitters.remove(emitter));
            emitter.onTimeout(() -> emitters.remove(emitter));
            emitter.onError(e -> emitters.remove(emitter));
            emitters.add(emitter);
            RunProgress progress = activeRuns.get(runId);
            if (progress != null) {
                // Copy and replay under the run's stream lock: a task event is applied and broadcast
                // under the same lock, so the copy cannot go stale while it is being sent (a worker's
                // live FAILED used to overtake the replay's older PENDING for the same task, and the
                // panel kept the last one it received).
                synchronized (progress.streamLock()) {
                    try {
                        for (LocationTaskSnapshot snapshot : progress.getTasks().values()) {
                            emitter.send(SseEmitter.event()
                                    .name("task-update")
                                    .data(objectMapper.writeValueAsString(snapshot)));
                        }
                        emitter.send(SseEmitter.event()
                                .name("run-summary")
                                .data(objectMapper.writeValueAsString(buildSummary(runId, progress))));
                        Map<String, Object> completeEvent = completions.get(runId);
                        if (completeEvent != null) {
                            // Already finished: the live broadcast went out before this subscriber
                            // existed, so replay the identical payload and end the stream as a live
                            // completion does.
                            emitter.send(SseEmitter.event()
                                    .name("run-complete")
                                    .data(objectMapper.writeValueAsString(completeEvent)));
                            emitters.remove(emitter);
                            emitter.complete();
                        }
                    } catch (IOException e) {
                        LOG.warn("Failed to send initial state to SSE subscriber: {}", e.getMessage());
                        emitters.remove(emitter);
                    }
                }
            } else {
                scheduleExpiry(runId, emitter, emitters);
            }
        }

        return emitter;
    }

    /**
     * After the grace period, tells a subscriber whose run is still unknown that it is gone. A run
     * that has been registered in the meantime is left alone: it is live and will broadcast to it.
     */
    private void scheduleExpiry(long runId, SseEmitter emitter, CopyOnWriteArrayList<SseEmitter> emitters) {
        graceScheduler.schedule(() -> {
            synchronized (completionLock) {
                if (activeRuns.containsKey(runId) || !emitters.contains(emitter)) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event()
                            .name("run-expired")
                            .data(objectMapper.writeValueAsString(Map.of("jobRunId", runId))));
                } catch (IOException e) {
                    LOG.debug("run-expired not delivered for run {}: {}", runId, e.getMessage());
                }
                emitters.remove(emitter);
                emitter.complete();
            }
        }, unknownRunGraceMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Subscribes an SSE emitter to run-complete notifications (all runs).
     *
     * @return the SSE emitter
     */
    public SseEmitter subscribeNotifications() {
        SseEmitter emitter = new SseEmitter(0L);
        notificationEmitters.add(emitter);
        emitter.onCompletion(() -> notificationEmitters.remove(emitter));
        emitter.onTimeout(() -> notificationEmitters.remove(emitter));
        emitter.onError(e -> notificationEmitters.remove(emitter));
        return emitter;
    }

    /**
     * Returns the progress for a given run, or null if not tracked.
     *
     * @param jobRunId the job run ID
     * @return the run progress, or null
     */
    public RunProgress getProgress(long jobRunId) {
        return activeRuns.get(jobRunId);
    }

    /**
     * Whether a run has completed (normally, or through {@link #failRun}) and its entry is still held.
     *
     * @param jobRunId the job run ID
     * @return {@code true} once the run's {@code run-complete} has been produced; {@code false} while it
     *         is still going, and for an id the tracker does not hold
     */
    public boolean isComplete(long jobRunId) {
        return completions.containsKey(jobRunId);
    }

    /**
     * Evicts the entries that have outlived their keep.
     *
     * <p>A run that has completed is evicted {@link #COMPLETED_RUN_RETENTION} after it COMPLETED (so
     * "Retry failed" is available for that long after the run finished, however long it ran). A run that
     * has not completed is never evicted while it is active; it is evicted only after
     * {@link #IDLE_RUN_TTL} with no task event or phase change, and any subscriber still attached to it
     * is sent {@code run-expired} and its stream ended, so no panel is left waiting.
     *
     * <p>Candidates are found without locking, then each is re-checked under the locks (completion lock,
     * then the run's stream lock: the documented order), so an event or a completion that arrives in
     * between saves the run.
     */
    public void cleanupStaleEntries() {
        Instant now = clock.instant();
        List<RunProgress> candidates = new ArrayList<>();
        for (RunProgress progress : activeRuns.values()) {
            if (isEvictable(progress, now)) {
                candidates.add(progress);
            }
        }
        for (RunProgress progress : candidates) {
            evict(progress, now);
        }
        synchronized (completionLock) {
            // Emitter lists of ids with no run: left empty by subscribers that have since been told the
            // run is gone. A list still holding a subscriber is waiting out its grace period; leave it.
            runEmitters.entrySet().removeIf(entry -> !activeRuns.containsKey(entry.getKey())
                    && entry.getValue().isEmpty());
        }
    }

    private static boolean isEvictable(RunProgress progress, Instant now) {
        Instant completedAt = progress.getCompletedAt();
        if (completedAt != null) {
            return !now.isBefore(completedAt.plus(COMPLETED_RUN_RETENTION));
        }
        return !now.isBefore(progress.getLastActivityAt().plus(IDLE_RUN_TTL));
    }

    private void evict(RunProgress progress, Instant now) {
        long jobRunId = progress.getJobRunId();
        synchronized (completionLock) {
            synchronized (progress.streamLock()) {
                if (activeRuns.get(jobRunId) != progress || !isEvictable(progress, now)) {
                    return;
                }
                boolean completed = progress.getCompletedAt() != null;
                activeRuns.remove(jobRunId, progress);
                completions.remove(jobRunId);
                CopyOnWriteArrayList<SseEmitter> emitters = runEmitters.remove(jobRunId);
                if (!completed && emitters != null) {
                    // A completed run's subscribers were all ended by its run-complete; only a run that
                    // never completed can have a panel still waiting.
                    expireSubscribers(jobRunId, emitters);
                }
                LOG.info("Run progress for jobRunId={} evicted ({})", jobRunId,
                        completed ? "completed, retention elapsed" : "idle, never completed");
            }
        }
    }

    private void expireSubscribers(long jobRunId, CopyOnWriteArrayList<SseEmitter> emitters) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("run-expired")
                        .data(objectMapper.writeValueAsString(Map.of("jobRunId", jobRunId))));
            } catch (IOException | IllegalStateException e) {
                LOG.debug("run-expired not delivered for run {}: {}", jobRunId, e.getMessage());
            }
            emitter.complete();
        }
        emitters.clear();
    }

    /**
     * Broadcasts the most recently updated task (used for a phase change, which names no task).
     */
    private void broadcastTaskUpdate(long jobRunId, RunProgress progress) {
        broadcastTaskUpdate(jobRunId, progress, null);
    }

    /**
     * Broadcasts the task an event just changed, with the run summary.
     *
     * <p>⚠️ <b>The event's OWN task, not "the most recently updated one".</b> Events are published from
     * many threads at once (the evaluation phase runs in parallel), and each handler used to broadcast
     * whichever snapshot had the newest timestamp when it got to the broadcast. Two threads that each
     * put a task and then looked for the newest would both find the later one, and the earlier task's
     * update was never sent: a finished run's panel then showed that place frozen in the state before
     * its last event (seen as a place left on "Cloud" in a run that had completed). Broadcasting the
     * event's own snapshot means every event is delivered once, whatever the interleaving.
     *
     * @param taskKey the task the event changed, or null to broadcast the most recently updated one
     */
    private void broadcastTaskUpdate(long jobRunId, RunProgress progress, String taskKey) {
        Map<String, LocationTaskSnapshot> snapshots = progress.getTasks();
        LocationTaskSnapshot latestSnapshot = taskKey != null ? snapshots.get(taskKey) : null;
        if (latestSnapshot == null) {
            latestSnapshot = snapshots.values().stream()
                    .max((a, b) -> a.lastUpdated().compareTo(b.lastUpdated()))
                    .orElse(null);
        }
        if (latestSnapshot == null) {
            return;
        }

        Map<String, Object> summary = buildSummary(jobRunId, progress);

        CopyOnWriteArrayList<SseEmitter> emitters = runEmitters.get(jobRunId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("task-update")
                            .data(objectMapper.writeValueAsString(latestSnapshot)));
                    emitter.send(SseEmitter.event()
                            .name("run-summary")
                            .data(objectMapper.writeValueAsString(summary)));
                } catch (IOException e) {
                    emitters.remove(emitter);
                }
            }
        }
    }

    private void broadcastRunComplete(long jobRunId, Map<String, Object> completeEvent) {
        // Send to run-specific emitters
        CopyOnWriteArrayList<SseEmitter> emitters = runEmitters.get(jobRunId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("run-complete")
                            .data(objectMapper.writeValueAsString(completeEvent)));
                    emitter.complete();
                } catch (IOException e) {
                    emitters.remove(emitter);
                }
            }
        }

        // Send to notification emitters (map view)
        for (SseEmitter emitter : notificationEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("run-complete")
                        .data(objectMapper.writeValueAsString(completeEvent)));
            } catch (IOException e) {
                notificationEmitters.remove(emitter);
            }
        }
    }

    /**
     * Updates the phase of an active run and broadcasts the change.
     *
     * @param jobRunId the job run ID
     * @param phase    the new phase
     */
    public void setPhase(long jobRunId, com.gregochr.goldenhour.model.RunPhase phase) {
        RunProgress progress = activeRuns.get(jobRunId);
        if (progress != null) {
            // Under the stream lock: the phase change re-sends the most recently updated task from a
            // copy, which must not be older than a task event another worker is broadcasting.
            synchronized (progress.streamLock()) {
                progress.touch(clock.instant());
                progress.setPhase(phase);
                broadcastTaskUpdate(jobRunId, progress);
            }
        }
    }

    private Map<String, Object> buildSummary(long jobRunId, RunProgress progress) {
        return Map.ofEntries(
                Map.entry("jobRunId", jobRunId),
                Map.entry("phase", progress.getPhase().name()),
                Map.entry("total", progress.getTotal()),
                Map.entry("completed", progress.getCompleted()),
                Map.entry("triaged", progress.getTriaged()),
                Map.entry("failed", progress.getFailed()),
                Map.entry("inProgress", progress.getInProgress()),
                Map.entry("skipped", progress.getSkipped()),
                Map.entry("status", progress.getStatus().name()),
                Map.entry("elapsedMs", progress.getElapsedMs())
        );
    }

    private Map<String, Object> buildRunCompleteEvent(long jobRunId, RunProgress progress) {
        List<Map<String, String>> failedTasks = progress.getFailedTasks().stream()
                .map(t -> Map.of(
                        "taskKey", t.taskKey(),
                        "locationName", t.locationName(),
                        "errorMessage", t.errorMessage() != null ? t.errorMessage() : ""))
                .toList();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("jobRunId", jobRunId);
        event.put("status", progress.getStatus().name());
        event.put("phase", progress.getPhase().name());
        event.put("total", progress.getTotal());
        event.put("completed", progress.getCompleted());
        event.put("triaged", progress.getTriaged());
        event.put("failed", progress.getFailed());
        event.put("skipped", progress.getSkipped());
        event.put("durationMs", progress.getElapsedMs());
        event.put("failedTasks", failedTasks);
        event.put("reason", progress.getFailureReason());
        RunProgress.RetryBlock retryBlock = progress.getRetryBlock();
        event.put("retryable", retryBlock == null);
        // Why Retry is not offered, so the panel can say the right thing for each case: null when it is.
        event.put("retryBlockedReason", retryBlock != null ? retryBlock.name() : null);
        return event;
    }
}
