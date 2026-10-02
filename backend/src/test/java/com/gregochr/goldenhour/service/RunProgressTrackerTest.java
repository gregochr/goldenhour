package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.RunProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link RunProgressTracker}.
 */
@ExtendWith(MockitoExtension.class)
class RunProgressTrackerTest {

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private RunProgressTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RunProgressTracker(dynamicSchedulerService);
    }

    private static List<String[]> tasks(String[]... entries) {
        List<String[]> list = new ArrayList<>();
        for (String[] entry : entries) {
            list.add(entry);
        }
        return list;
    }

    @Test
    @DisplayName("initRun registers all tasks as PENDING")
    void initRun_registersAllTasksAsPending() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"},
                new String[]{"Loc1|2026-03-15|SUNSET", "Loc1", "2026-03-15", "SUNSET"}
        ));

        RunProgress progress = tracker.getProgress(1L);
        assertThat(progress).isNotNull();
        assertThat(progress.getTotal()).isEqualTo(2);
        assertThat(progress.getCompleted()).isZero();
        assertThat(progress.getFailed()).isZero();
        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.RUNNING);
    }

    @Test
    @DisplayName("onTaskEvent updates task state and derives correct counts")
    void onTaskEvent_updatesState() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"},
                new String[]{"Loc1|2026-03-15|SUNSET", "Loc1", "2026-03-15", "SUNSET"}
        ));

        // Complete one task
        tracker.onTaskEvent(new LocationTaskEvent(
                this, 1L, "Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE",
                LocationTaskState.COMPLETE, null, null));

        RunProgress progress = tracker.getProgress(1L);
        assertThat(progress.getCompleted()).isEqualTo(1);
        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.RUNNING);

        // Complete second task
        tracker.onTaskEvent(new LocationTaskEvent(
                this, 1L, "Loc1|2026-03-15|SUNSET", "Loc1", "2026-03-15", "SUNSET",
                LocationTaskState.COMPLETE, null, null));

        assertThat(progress.getCompleted()).isEqualTo(2);
        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.COMPLETE);
    }

    @Test
    @DisplayName("FAILED task contributes to PARTIAL status")
    void failedTask_producesPartialStatus() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"},
                new String[]{"Loc1|2026-03-15|SUNSET", "Loc1", "2026-03-15", "SUNSET"}
        ));

        tracker.onTaskEvent(new LocationTaskEvent(
                this, 1L, "Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE",
                LocationTaskState.COMPLETE, null, null));
        tracker.onTaskEvent(new LocationTaskEvent(
                this, 1L, "Loc1|2026-03-15|SUNSET", "Loc1", "2026-03-15", "SUNSET",
                LocationTaskState.FAILED, "Open-Meteo 429", "FETCHING_WEATHER"));

        RunProgress progress = tracker.getProgress(1L);
        assertThat(progress.getCompleted()).isEqualTo(1);
        assertThat(progress.getFailed()).isEqualTo(1);
        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.PARTIAL);
        assertThat(progress.getFailedTasks()).hasSize(1);
        assertThat(progress.getFailedTasks().getFirst().errorMessage()).isEqualTo("Open-Meteo 429");
    }

    @Test
    @DisplayName("All tasks FAILED produces FAILED status")
    void allFailed_producesFailedStatus() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"}
        ));

        tracker.onTaskEvent(new LocationTaskEvent(
                this, 1L, "Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE",
                LocationTaskState.FAILED, "Error", "EVALUATING"));

        RunProgress progress = tracker.getProgress(1L);
        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.FAILED);
    }

    @Test
    @DisplayName("SKIPPED tasks count correctly")
    void skippedTasks_countCorrectly() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"},
                new String[]{"Loc1|2026-03-15|SUNSET", "Loc1", "2026-03-15", "SUNSET"}
        ));

        tracker.onTaskEvent(new LocationTaskEvent(
                this, 1L, "Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE",
                LocationTaskState.SKIPPED, null, null));
        tracker.onTaskEvent(new LocationTaskEvent(
                this, 1L, "Loc1|2026-03-15|SUNSET", "Loc1", "2026-03-15", "SUNSET",
                LocationTaskState.COMPLETE, null, null));

        RunProgress progress = tracker.getProgress(1L);
        assertThat(progress.getSkipped()).isEqualTo(1);
        assertThat(progress.getCompleted()).isEqualTo(1);
        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.COMPLETE);
    }

    @Test
    @DisplayName("In-progress tasks are counted correctly")
    void inProgressTasks_countedCorrectly() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"},
                new String[]{"Loc1|2026-03-15|SUNSET", "Loc1", "2026-03-15", "SUNSET"}
        ));

        tracker.onTaskEvent(new LocationTaskEvent(
                this, 1L, "Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE",
                LocationTaskState.FETCHING_WEATHER, null, null));

        RunProgress progress = tracker.getProgress(1L);
        assertThat(progress.getInProgress()).isEqualTo(1);
    }

    @Test
    @DisplayName("getProgress returns null for unknown run")
    void getProgress_returnsNullForUnknown() {
        assertThat(tracker.getProgress(999L)).isNull();
    }

    @Test
    @DisplayName("onTaskEvent ignores events for unknown runs")
    void onTaskEvent_ignoresUnknownRun() {
        // Should not throw
        tracker.onTaskEvent(new LocationTaskEvent(
                this, 999L, "Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE",
                LocationTaskState.COMPLETE, null, null));
    }

    @Test
    @DisplayName("cleanupStaleEntries removes old entries")
    void cleanupStaleEntries_removesOldEntries() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"}
        ));

        assertThat(tracker.getProgress(1L)).isNotNull();

        // Cleanup should NOT remove a fresh entry
        tracker.cleanupStaleEntries();
        assertThat(tracker.getProgress(1L)).isNotNull();
    }

    @Test
    @DisplayName("subscribe returns emitter for tracked run")
    void subscribe_returnsEmitterForTrackedRun() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"}
        ));

        var emitter = tracker.subscribe(1L);
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("subscribeNotifications returns emitter")
    void subscribeNotifications_returnsEmitter() {
        var emitter = tracker.subscribeNotifications();
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("completeRun on unknown run is a no-op")
    void completeRun_unknownRun_noOp() {
        // Should not throw
        tracker.completeRun(999L);
    }

    @Test
    @DisplayName("completeRun marks run as complete and removes from active")
    void completeRun_marksCompleteAndRemoves() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"}
        ));

        tracker.onTaskEvent(new LocationTaskEvent(
                this, 1L, "Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE",
                LocationTaskState.COMPLETE, null, null));

        tracker.completeRun(1L);

        // After completeRun, progress should still be accessible (kept for SSE replay)
        RunProgress progress = tracker.getProgress(1L);
        assertThat(progress).isNotNull();
        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.COMPLETE);
    }

    @Test
    @DisplayName("setPhase updates progress phase")
    void setPhase_updatesPhase() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"}
        ));

        tracker.setPhase(1L, com.gregochr.goldenhour.model.RunPhase.FULL_EVALUATION);

        RunProgress progress = tracker.getProgress(1L);
        assertThat(progress.getPhase())
                .isEqualTo(com.gregochr.goldenhour.model.RunPhase.FULL_EVALUATION);
    }

    @Test
    @DisplayName("setPhase on unknown run is a no-op")
    void setPhase_unknownRun_noOp() {
        // Should not throw
        tracker.setPhase(999L, com.gregochr.goldenhour.model.RunPhase.TRIAGE);
    }

    @Test
    @DisplayName("Multiple runs tracked independently")
    void multipleRuns_trackedIndependently() {
        tracker.initRun(1L, tasks(
                new String[]{"Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE"}
        ));
        tracker.initRun(2L, tasks(
                new String[]{"Loc2|2026-03-16|SUNSET", "Loc2", "2026-03-16", "SUNSET"}
        ));

        tracker.onTaskEvent(new LocationTaskEvent(
                this, 1L, "Loc1|2026-03-15|SUNRISE", "Loc1", "2026-03-15", "SUNRISE",
                LocationTaskState.COMPLETE, null, null));

        assertThat(tracker.getProgress(1L).getCompleted()).isEqualTo(1);
        assertThat(tracker.getProgress(2L).getCompleted()).isZero();
    }

    // -------------------------------------------------------------------------
    // Late and unknown subscribers
    // -------------------------------------------------------------------------

    /** An emitter that records every event sent to it as the text it would put on the wire. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> events = new ArrayList<>();
        private boolean completed;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            StringBuilder text = new StringBuilder();
            builder.build().forEach(part -> text.append(part.getData()));
            events.add(text.toString());
            super.send(builder);
        }

        @Override
        public synchronized void complete() {
            completed = true;
            super.complete();
        }

        /** The sent events, each as {@code name|payload}. */
        List<String> sent() {
            return events.stream()
                    .map(e -> e.replace("event:", "").replace("\ndata:", "|").replace("\n\n", ""))
                    .toList();
        }
    }

    private final List<RecordingEmitter> emitters = new ArrayList<>();
    private ScheduledExecutorService graceScheduler;

    /** A tracker whose emitters are recordable and whose unknown-run grace is the captured task. */
    private RunProgressTracker recordingTracker() {
        graceScheduler = mock(ScheduledExecutorService.class);
        return new RunProgressTracker(dynamicSchedulerService, graceScheduler, 5_000L) {
            @Override
            SseEmitter newEmitter() {
                RecordingEmitter emitter = new RecordingEmitter();
                emitters.add(emitter);
                return emitter;
            }
        };
    }

    private static final String SUNRISE_KEY = "Loc1|2026-03-15|SUNRISE";
    private static final String SUNSET_KEY = "Loc1|2026-03-15|SUNSET";

    private void startRunWithOneFailure(RunProgressTracker t) {
        t.initRun(7L, tasks(
                new String[]{SUNRISE_KEY, "Loc1", "2026-03-15", "SUNRISE"},
                new String[]{SUNSET_KEY, "Loc1", "2026-03-15", "SUNSET"}));
        t.onTaskEvent(new LocationTaskEvent(this, 7L, SUNRISE_KEY, "Loc1", "2026-03-15", "SUNRISE",
                LocationTaskState.COMPLETE, null, null));
        t.onTaskEvent(new LocationTaskEvent(this, 7L, SUNSET_KEY, "Loc1", "2026-03-15", "SUNSET",
                LocationTaskState.FAILED, "weather down", "FETCHING_WEATHER"));
    }

    private static String eventNamed(RecordingEmitter emitter, String name) {
        List<String> found = emitter.sent().stream().filter(e -> e.startsWith(name + "|")).toList();
        assertThat(found).as("events named %s in %s", name, emitter.sent()).hasSize(1);
        return found.get(0);
    }

    @Test
    @DisplayName("a subscriber arriving after completion is replayed the snapshots, then the identical "
            + "run-complete, and the stream ends")
    void subscribe_afterCompletion_replaysSnapshotsThenIdenticalRunComplete() throws Exception {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);
        t.completeRun(7L);
        String liveComplete = eventNamed(live, "run-complete");
        Thread.sleep(25); // an elapsed time recomputed at replay would now differ from the live one

        RecordingEmitter late = (RecordingEmitter) t.subscribe(7L);

        List<String> names = late.sent().stream().map(e -> e.substring(0, e.indexOf('|'))).toList();
        assertThat(names).containsExactly("task-update", "task-update", "run-summary", "run-complete");
        assertThat(eventNamed(late, "run-complete")).isEqualTo(liveComplete);
        assertThat(liveComplete).contains("\"failed\":1").contains("\"completed\":1")
                .contains("\"status\":\"PARTIAL\"").contains("\"jobRunId\":7")
                .contains(SUNSET_KEY).contains("weather down");
        assertThat(late.completed).isTrue();
    }

    @Test
    @DisplayName("a live subscriber still gets run-complete once, and the stream ends")
    void subscribe_beforeCompletion_getsRunCompleteOnce() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);
        assertThat(live.sent()).extracting(e -> e.substring(0, e.indexOf('|')))
                .containsExactly("task-update", "task-update", "run-summary");
        assertThat(live.completed).isFalse();

        t.completeRun(7L);

        assertThat(live.sent()).extracting(e -> e.substring(0, e.indexOf('|')))
                .containsExactly("task-update", "task-update", "run-summary", "run-complete");
        assertThat(live.completed).isTrue();
    }

    @Test
    @DisplayName("an unknown run id gets one run-expired event after the grace period, and the stream ends")
    void subscribe_unknownRun_getsRunExpiredAfterGrace() {
        RunProgressTracker t = recordingTracker();

        RecordingEmitter emitter = (RecordingEmitter) t.subscribe(404L);

        assertThat(emitter.sent()).isEmpty();
        assertThat(emitter.completed).isFalse();
        ArgumentCaptor<Runnable> expiry = ArgumentCaptor.forClass(Runnable.class);
        verify(graceScheduler).schedule(expiry.capture(), org.mockito.ArgumentMatchers.eq(5_000L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
        expiry.getValue().run();
        assertThat(emitter.sent()).containsExactly("run-expired|{\"jobRunId\":404}");
        assertThat(emitter.completed).isTrue();
    }

    @Test
    @DisplayName("a run that is registered within the grace period is NOT declared expired")
    void subscribe_runRegisteredDuringGrace_isNotExpired() {
        RunProgressTracker t = recordingTracker();
        RecordingEmitter emitter = (RecordingEmitter) t.subscribe(7L);
        ArgumentCaptor<Runnable> expiry = ArgumentCaptor.forClass(Runnable.class);
        verify(graceScheduler).schedule(expiry.capture(), anyLong(), org.mockito.ArgumentMatchers.any());

        startRunWithOneFailure(t); // the executor thread registered the run after the client subscribed
        expiry.getValue().run();

        assertThat(emitter.sent()).extracting(e -> e.substring(0, e.indexOf('|')))
                .doesNotContain("run-expired");
        assertThat(emitter.completed).isFalse();
        t.completeRun(7L);
        assertThat(emitter.sent()).extracting(e -> e.substring(0, e.indexOf('|'))).contains("run-complete");
    }

    @Test
    @DisplayName("an evicted run's retained completion goes with it, so a later subscriber is told it expired")
    void cleanupStaleEntries_dropsRetainedCompletion() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        t.completeRun(7L);
        org.springframework.test.util.ReflectionTestUtils.setField(t.getProgress(7L), "startedAt",
                java.time.Instant.now().minusSeconds(31 * 60));

        t.cleanupStaleEntries();
        RecordingEmitter late = (RecordingEmitter) t.subscribe(7L);

        assertThat(late.sent()).isEmpty(); // no replay: the completion was evicted with the run
        verify(graceScheduler).schedule(org.mockito.ArgumentMatchers.any(Runnable.class), anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    // -------------------------------------------------------------------------
    // Completion is idempotent, and carries a reason when the run failed as a whole
    // -------------------------------------------------------------------------

    private static final String GENERIC_REASON = "The run stopped unexpectedly. See the server log.";

    private static long runCompleteCount(RecordingEmitter emitter) {
        return emitter.sent().stream().filter(e -> e.startsWith("run-complete|")).count();
    }

    @Test
    @DisplayName("a second completeRun is a no-op: run-complete is broadcast once and the payload is unchanged")
    void completeRun_twice_broadcastsOnce() throws Exception {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);

        t.completeRun(7L);
        String first = eventNamed(live, "run-complete");
        t.completeRun(7L);

        assertThat(runCompleteCount(live)).isEqualTo(1);
        assertThat(eventNamed(live, "run-complete")).isEqualTo(first);
    }

    @Test
    @DisplayName("a normal completion carries a null reason, in the live and the retained payload")
    void completeRun_normal_reasonIsNull() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);

        t.completeRun(7L);
        RecordingEmitter late = (RecordingEmitter) t.subscribe(7L);

        assertThat(eventNamed(live, "run-complete")).contains("\"reason\":null");
        assertThat(eventNamed(late, "run-complete")).contains("\"reason\":null");
    }

    @Test
    @DisplayName("failRun on a run the tracker never held registers it and emits run-complete FAILED with the "
            + "reason to a subscriber who was waiting")
    void failRun_unregisteredRun_emitsFailedWithReason() {
        RunProgressTracker t = recordingTracker();
        RecordingEmitter waiting = (RecordingEmitter) t.subscribe(9L);

        t.failRun(9L, GENERIC_REASON);

        String complete = eventNamed(waiting, "run-complete");
        assertThat(complete).contains("\"status\":\"FAILED\"").contains("\"total\":0")
                .contains("\"failed\":0").contains("\"jobRunId\":9")
                .contains("\"reason\":\"" + GENERIC_REASON + "\"");
        assertThat(waiting.sent()).extracting(e -> e.substring(0, e.indexOf('|')))
                .doesNotContain("run-expired");
        assertThat(waiting.completed).isTrue();
        assertThat(t.getProgress(9L).getStatus()).isEqualTo(RunProgress.RunStatus.FAILED);
    }

    @Test
    @DisplayName("a subscriber arriving after failRun is replayed the identical run-complete, reason included")
    void failRun_lateSubscriber_isReplayedTheReason() {
        RunProgressTracker t = recordingTracker();
        t.failRun(9L, GENERIC_REASON);

        RecordingEmitter late = (RecordingEmitter) t.subscribe(9L);

        assertThat(late.sent()).extracting(e -> e.substring(0, e.indexOf('|')))
                .containsExactly("run-summary", "run-complete");
        assertThat(eventNamed(late, "run-complete")).contains("\"status\":\"FAILED\"")
                .contains("\"reason\":\"" + GENERIC_REASON + "\"");
        assertThat(late.completed).isTrue();
    }

    @Test
    @DisplayName("failRun on a registered run with a completed task reports PARTIAL with the reason")
    void failRun_registeredRunWithCompletedTask_isPartial() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);

        t.failRun(7L, GENERIC_REASON);

        assertThat(eventNamed(live, "run-complete")).contains("\"status\":\"PARTIAL\"")
                .contains("\"completed\":1").contains("\"failed\":1")
                .contains("\"reason\":\"" + GENERIC_REASON + "\"");
    }

    @Test
    @DisplayName("failRun after a completion is a no-op: first completion wins")
    void failRun_afterCompleteRun_isNoOp() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);
        t.completeRun(7L);
        String first = eventNamed(live, "run-complete");

        t.failRun(7L, GENERIC_REASON);

        assertThat(runCompleteCount(live)).isEqualTo(1);
        assertThat(eventNamed(live, "run-complete")).isEqualTo(first);
        assertThat(t.getProgress(7L).getFailureReason()).isNull();
    }

    @Test
    @DisplayName("noteFailure sets the reason that a later completeRun carries")
    void noteFailure_thenCompleteRun_carriesReason() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);

        t.noteFailure(7L, GENERIC_REASON);
        assertThat(runCompleteCount(live)).isZero(); // noting is not completing

        t.completeRun(7L);

        assertThat(eventNamed(live, "run-complete"))
                .contains("\"reason\":\"" + GENERIC_REASON + "\"").contains("\"status\":\"PARTIAL\"");
    }

    @Test
    @DisplayName("noteFailure on an unknown run is a no-op and does not register it")
    void noteFailure_unknownRun_noOp() {
        RunProgressTracker t = recordingTracker();

        t.noteFailure(404L, GENERIC_REASON);

        assertThat(t.getProgress(404L)).isNull();
    }

    // -------------------------------------------------------------------------
    // Stopping a run on a rejected key, and the retryable flag
    // -------------------------------------------------------------------------

    private static final String RUN_STOPPED =
            "Claude rejected the API key. The run was stopped; no further places were attempted.";

    @Test
    @DisplayName("every run-complete payload carries retryable: true, live and replayed, unless the run was stopped")
    void completeRun_normal_isRetryable() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);

        t.completeRun(7L);
        RecordingEmitter late = (RecordingEmitter) t.subscribe(7L);

        assertThat(eventNamed(live, "run-complete")).contains("\"retryable\":true");
        assertThat(eventNamed(late, "run-complete")).contains("\"retryable\":true");
    }

    @Test
    @DisplayName("stopRun records the run-level reason, flips isStopped, and broadcasts nothing until completion")
    void stopRun_recordsReasonAndBroadcastsNothing() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);

        boolean stopped = t.stopRun(7L);

        assertThat(stopped).isTrue();
        assertThat(t.isStopped(7L)).isTrue();
        assertThat(t.getProgress(7L).getFailureReason()).isEqualTo(RUN_STOPPED);
        assertThat(runCompleteCount(live)).isZero();
    }

    @Test
    @DisplayName("the completion after a stop carries the stop reason and retryable: false, live and replayed")
    void stopRun_thenCompleteRun_carriesReasonAndNotRetryable() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);

        t.stopRun(7L);
        t.completeRun(7L);
        RecordingEmitter late = (RecordingEmitter) t.subscribe(7L);

        assertThat(eventNamed(live, "run-complete")).contains("\"reason\":\"" + RUN_STOPPED + "\"")
                .contains("\"retryable\":false").contains("\"status\":\"PARTIAL\"");
        assertThat(eventNamed(late, "run-complete")).isEqualTo(eventNamed(live, "run-complete"));
    }

    @Test
    @DisplayName("a second stopRun is a no-op that reports it did not stop the run")
    void stopRun_twice_secondIsNoOp() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);

        assertThat(t.stopRun(7L)).isTrue();
        assertThat(t.stopRun(7L)).isFalse();
    }

    @Test
    @DisplayName("stopRun and isStopped on a run the tracker does not hold are false and register nothing")
    void stopRun_unknownRun_isFalse() {
        RunProgressTracker t = recordingTracker();

        assertThat(t.stopRun(404L)).isFalse();
        assertThat(t.isStopped(404L)).isFalse();
        assertThat(t.getProgress(404L)).isNull();
    }

    @Test
    @DisplayName("a stop belongs to one run: another run held at the same time is not stopped")
    void stopRun_isPerRun() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        t.initRun(8L, tasks(new String[]{"Loc2|2026-03-15|SUNSET", "Loc2", "2026-03-15", "SUNSET"}));

        t.stopRun(7L);

        assertThat(t.isStopped(7L)).isTrue();
        assertThat(t.isStopped(8L)).isFalse();
        assertThat(t.getProgress(8L).getFailureReason()).isNull();
        assertThat(t.getProgress(8L).isRetryable()).isTrue();
    }

    @Test
    @DisplayName("failRun after a stop keeps the stop reason (the more specific one) and the run stays non-retryable")
    void failRun_afterStop_keepsStopReason() {
        RunProgressTracker t = recordingTracker();
        startRunWithOneFailure(t);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);
        t.stopRun(7L);

        t.failRun(7L, GENERIC_REASON);

        assertThat(eventNamed(live, "run-complete")).contains("\"reason\":\"" + RUN_STOPPED + "\"")
                .contains("\"retryable\":false");
    }

    @Test
    @DisplayName("a stopped run with nothing completed or triaged reports FAILED")
    void stopRun_nothingCompleted_isFailed() {
        RunProgressTracker t = recordingTracker();
        t.initRun(8L, tasks(new String[]{"Loc2|2026-03-15|SUNSET", "Loc2", "2026-03-15", "SUNSET"}));
        t.onTaskEvent(new LocationTaskEvent(this, 8L, "Loc2|2026-03-15|SUNSET", "Loc2", "2026-03-15", "SUNSET",
                LocationTaskState.FAILED, "x", "EVALUATING"));

        t.stopRun(8L);

        assertThat(t.getProgress(8L).getStatus()).isEqualTo(RunProgress.RunStatus.FAILED);
    }

    @Test
    @DisplayName("events published from many threads at once are each delivered to the subscriber: none is "
            + "overtaken by a newer task's broadcast")
    void onTaskEvent_concurrentPublishers_everyTaskUpdateIsDelivered() throws Exception {
        RunProgressTracker t = recordingTracker();
        int count = 300;
        List<String[]> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            all.add(new String[]{"Loc" + i + "|2026-03-15|SUNSET", "Loc" + i, "2026-03-15", "SUNSET"});
        }
        t.initRun(7L, all);
        RecordingEmitter live = (RecordingEmitter) t.subscribe(7L);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (String[] task : all) {
            futures.add(pool.submit(() -> {
                go.await();
                t.onTaskEvent(new LocationTaskEvent(this, 7L, task[0], task[1], task[2], task[3],
                        LocationTaskState.FAILED, "x", "EVALUATING"));
                return null;
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        long failedDelivered = all.stream()
                .filter(task -> live.sent().stream().anyMatch(e -> e.startsWith("task-update|")
                        && e.contains("\"taskKey\":\"" + task[0] + "\"") && e.contains("\"state\":\"FAILED\"")))
                .count();
        assertThat(failedDelivered).isEqualTo(count);
    }
}
