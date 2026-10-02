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
import java.time.Instant;
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
 * Stale entries are cleaned up after 30 minutes.
 *
 * <p>A subscriber that arrives after a run has finished is not left waiting for an event that
 * already went out: it is sent the retained task snapshots, the run summary and the SAME
 * {@code run-complete} payload live subscribers received. A subscriber to an id the tracker does
 * not hold (evicted, restarted away, or never a tracked run) is sent a terminal
 * {@code run-expired} event once a short grace period shows it is not merely a run about to start.
 */
@Service
public class RunProgressTracker {

    private static final Logger LOG = LoggerFactory.getLogger(RunProgressTracker.class);
    private static final long STALE_TTL_MS = 30 * 60 * 1000L;

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
     * Constructs the tracker with an explicit grace scheduler (tests supply their own).
     *
     * @param dynamicSchedulerService the dynamic scheduler for job registration
     * @param graceScheduler          runs the unknown-run expiry check
     * @param unknownRunGraceMs       the grace period before an unknown run is declared expired
     */
    RunProgressTracker(DynamicSchedulerService dynamicSchedulerService,
            ScheduledExecutorService graceScheduler, long unknownRunGraceMs) {
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.graceScheduler = graceScheduler;
        this.unknownRunGraceMs = unknownRunGraceMs;
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
        progress.updateTask(event);
        broadcastTaskUpdate(event.getJobRunId(), progress);
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
            Map<String, Object> completeEvent = buildRunCompleteEvent(jobRunId, progress);
            completions.put(jobRunId, completeEvent);
            broadcastRunComplete(jobRunId, completeEvent);
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
            Map<String, Object> completeEvent = buildRunCompleteEvent(jobRunId, progress);
            completions.put(jobRunId, completeEvent);
            broadcastRunComplete(jobRunId, completeEvent);
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
        CopyOnWriteArrayList<SseEmitter> emitters =
                runEmitters.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        synchronized (completionLock) {
            emitters.add(emitter);
            RunProgress progress = activeRuns.get(runId);
            if (progress != null) {
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
     * Removes stale run entries older than 30 minutes.
     */
    public void cleanupStaleEntries() {
        Instant cutoff = Instant.now().minusMillis(STALE_TTL_MS);
        activeRuns.entrySet().removeIf(entry -> entry.getValue().getStartedAt().isBefore(cutoff));
        completions.keySet().removeIf(id -> !activeRuns.containsKey(id));
        runEmitters.entrySet().removeIf(entry -> !activeRuns.containsKey(entry.getKey()));
    }

    private void broadcastTaskUpdate(long jobRunId, RunProgress progress) {
        LocationTaskSnapshot latestSnapshot = progress.getTasks().values().stream()
                .max((a, b) -> a.lastUpdated().compareTo(b.lastUpdated()))
                .orElse(null);
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
            progress.setPhase(phase);
            broadcastTaskUpdate(jobRunId, progress);
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
        return event;
    }
}
