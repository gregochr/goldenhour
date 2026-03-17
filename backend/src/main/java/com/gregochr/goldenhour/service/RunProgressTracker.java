package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskSnapshot;
import com.gregochr.goldenhour.model.RunProgress;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton service that tracks live progress of forecast runs and broadcasts
 * state changes via Server-Sent Events.
 *
 * <p>Listens for {@link LocationTaskEvent} application events, updates the
 * in-memory {@link RunProgress}, and pushes SSE messages to subscribed clients.
 * Stale entries are cleaned up after 30 minutes.
 */
@Service
@RequiredArgsConstructor
public class RunProgressTracker {

    private static final Logger LOG = LoggerFactory.getLogger(RunProgressTracker.class);
    private static final long STALE_TTL_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<Long, RunProgress> activeRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> runEmitters =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> notificationEmitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

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
     * @param jobRunId the job run ID
     */
    public void completeRun(long jobRunId) {
        RunProgress progress = activeRuns.get(jobRunId);
        if (progress == null) {
            return;
        }
        broadcastRunComplete(jobRunId, progress);
    }

    /**
     * Subscribes an SSE emitter to a specific run's progress updates.
     *
     * @param runId the job run ID
     * @return the SSE emitter, or null if the run is not tracked
     */
    public SseEmitter subscribe(long runId) {
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> emitters =
                runEmitters.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // Send current state snapshot immediately
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
            } catch (IOException e) {
                LOG.warn("Failed to send initial state to SSE subscriber: {}", e.getMessage());
                emitters.remove(emitter);
            }
        }

        return emitter;
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
    @Scheduled(fixedDelay = 300_000)
    public void cleanupStaleEntries() {
        Instant cutoff = Instant.now().minusMillis(STALE_TTL_MS);
        activeRuns.entrySet().removeIf(entry -> entry.getValue().getStartedAt().isBefore(cutoff));
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

    private void broadcastRunComplete(long jobRunId, RunProgress progress) {
        Map<String, Object> completeEvent = buildRunCompleteEvent(jobRunId, progress);

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

        return Map.ofEntries(
                Map.entry("jobRunId", jobRunId),
                Map.entry("status", progress.getStatus().name()),
                Map.entry("phase", progress.getPhase().name()),
                Map.entry("total", progress.getTotal()),
                Map.entry("completed", progress.getCompleted()),
                Map.entry("triaged", progress.getTriaged()),
                Map.entry("failed", progress.getFailed()),
                Map.entry("skipped", progress.getSkipped()),
                Map.entry("durationMs", progress.getElapsedMs()),
                Map.entry("failedTasks", failedTasks)
        );
    }
}
