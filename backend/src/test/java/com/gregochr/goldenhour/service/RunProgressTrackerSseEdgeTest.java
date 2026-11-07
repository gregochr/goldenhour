package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.RunPhase;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Subscriber-lifecycle edges of {@link RunProgressTracker}: emitter callbacks, broken connections during replay,
 * broadcast and completion, the unknown-run expiry races, stale emitter lists and the production constructor.
 */
class RunProgressTrackerSseEdgeTest {

    private static final long RUN = 7L;
    private static final String KEY = "Loc1|2026-10-03|SUNSET";
    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");
    private static final long GRACE_MS = 5_000L;

    private ScheduledExecutorService graceScheduler;
    private RunProgressTracker tracker;
    private final List<ScriptedEmitter> emitters = new ArrayList<>();
    private int sendsAllowedForNextEmitter = Integer.MAX_VALUE;

    /** An emitter that records event names, can break after N sends, and exposes the callbacks registered on it. */
    private static final class ScriptedEmitter extends SseEmitter {
        private final int sendsAllowed;
        private final List<String> names = new ArrayList<>();
        private int attempts;
        private boolean completed;
        private Runnable completionCallback;
        private Runnable timeoutCallback;
        private Consumer<Throwable> errorCallback;

        ScriptedEmitter(int sendsAllowed) {
            super(0L);
            this.sendsAllowed = sendsAllowed;
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            attempts++;
            if (attempts > sendsAllowed) {
                throw new IOException("broken pipe");
            }
            StringBuilder text = new StringBuilder();
            builder.build().forEach(part -> text.append(part.getData()));
            names.add(text.toString().replace("event:", "").split("\n")[0]);
            super.send(builder);
        }

        @Override
        public synchronized void complete() {
            completed = true;
            super.complete();
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            completionCallback = callback;
            super.onCompletion(callback);
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
            timeoutCallback = callback;
            super.onTimeout(callback);
        }

        @Override
        public synchronized void onError(Consumer<Throwable> callback) {
            errorCallback = callback;
            super.onError(callback);
        }
    }

    @BeforeEach
    void setUp() {
        graceScheduler = mock(ScheduledExecutorService.class);
        tracker = new RunProgressTracker(mock(DynamicSchedulerService.class), graceScheduler, GRACE_MS,
                new MutableTestClock(T0)) {
            @Override
            SseEmitter newEmitter() {
                ScriptedEmitter emitter = new ScriptedEmitter(sendsAllowedForNextEmitter);
                emitters.add(emitter);
                return emitter;
            }
        };
        tracker.initRun(RUN, List.<String[]>of(new String[]{KEY, "Loc1", "2026-10-03", "SUNSET"}));
    }

    private void taskEvent(long run, LocationTaskState state) {
        tracker.onTaskEvent(new LocationTaskEvent(this, run, KEY, "Loc1", "2026-10-03", "SUNSET", state, null,
                null));
    }

    private ScriptedEmitter subscribe(long run) {
        return (ScriptedEmitter) tracker.subscribe(run);
    }

    @Test
    @DisplayName("each lifecycle callback removes the subscriber, so later task events are not sent to it")
    void callbacks_removeTheSubscriber() {
        ScriptedEmitter completion = subscribe(RUN);
        ScriptedEmitter timeout = subscribe(RUN);
        ScriptedEmitter error = subscribe(RUN);
        assertThat(completion.names).containsExactly("task-update", "run-summary");

        completion.completionCallback.run();
        timeout.timeoutCallback.run();
        error.errorCallback.accept(new IllegalStateException("client went away"));
        taskEvent(RUN, LocationTaskState.EVALUATING);

        assertThat(completion.names).hasSize(2);
        assertThat(timeout.names).hasSize(2);
        assertThat(error.names).hasSize(2);
    }

    @Test
    @DisplayName("a subscriber that breaks during the initial replay is dropped and gets nothing further")
    void subscribe_brokenDuringReplay_isDropped() {
        sendsAllowedForNextEmitter = 0;
        ScriptedEmitter broken = subscribe(RUN);
        sendsAllowedForNextEmitter = Integer.MAX_VALUE;

        taskEvent(RUN, LocationTaskState.EVALUATING);

        assertThat(broken.attempts).isEqualTo(1);
        assertThat(broken.names).isEmpty();
    }

    @Test
    @DisplayName("a subscriber that breaks on a live task update is dropped and not sent the completion")
    void broadcast_brokenSubscriberIsDropped() {
        sendsAllowedForNextEmitter = 2;
        ScriptedEmitter flaky = subscribe(RUN);
        sendsAllowedForNextEmitter = Integer.MAX_VALUE;
        ScriptedEmitter healthy = subscribe(RUN);

        taskEvent(RUN, LocationTaskState.EVALUATING);
        tracker.completeRun(RUN);

        assertThat(flaky.attempts).isEqualTo(3);
        assertThat(flaky.completed).isFalse();
        assertThat(healthy.names).contains("run-complete");
        assertThat(healthy.completed).isTrue();
    }

    @Test
    @DisplayName("a subscriber that breaks on the run-complete send is dropped without ending the others")
    void completeRun_brokenSubscriberIsDropped() {
        sendsAllowedForNextEmitter = 2;
        ScriptedEmitter flaky = subscribe(RUN);
        sendsAllowedForNextEmitter = Integer.MAX_VALUE;
        ScriptedEmitter healthy = subscribe(RUN);

        tracker.completeRun(RUN);

        assertThat(flaky.attempts).isEqualTo(3);
        assertThat(flaky.completed).isFalse();
        assertThat(healthy.names).containsExactly("task-update", "run-summary", "run-complete");
        assertThat(healthy.completed).isTrue();
    }

    private Runnable capturedExpiry() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(graceScheduler).schedule(captor.capture(), eq(GRACE_MS), eq(TimeUnit.MILLISECONDS));
        return captor.getValue();
    }

    @Test
    @DisplayName("an unknown run that appears before the grace period ends is not declared expired")
    void expiry_runRegisteredMeanwhile_leftAlone() {
        ScriptedEmitter waiting = subscribe(99L);
        Runnable expiry = capturedExpiry();
        tracker.initRun(99L, List.<String[]>of(new String[]{KEY, "Loc1", "2026-10-03", "SUNSET"}));

        expiry.run();

        assertThat(waiting.names).isEmpty();
        assertThat(waiting.completed).isFalse();
    }

    @Test
    @DisplayName("an unknown run whose subscriber already left is not sent run-expired")
    void expiry_subscriberAlreadyGone_noSend() {
        ScriptedEmitter waiting = subscribe(99L);
        Runnable expiry = capturedExpiry();
        waiting.completionCallback.run();

        expiry.run();

        assertThat(waiting.attempts).isZero();
        assertThat(waiting.completed).isFalse();
    }

    @Test
    @DisplayName("an unknown run whose subscriber cannot be told run-expired still ends its stream")
    void expiry_sendFails_streamStillEnded() {
        sendsAllowedForNextEmitter = 0;
        ScriptedEmitter waiting = subscribe(99L);
        Runnable expiry = capturedExpiry();

        expiry.run();

        assertThat(waiting.attempts).isEqualTo(1);
        assertThat(waiting.completed).isTrue();
    }

    @Test
    @DisplayName("an unknown run's waiting subscriber survives a cleanup and receives the run once it registers")
    void cleanup_keepsEmitterListsStillWaiting() {
        ScriptedEmitter waiting = subscribe(99L);
        ScriptedEmitter departed = subscribe(98L);
        departed.completionCallback.run();

        tracker.cleanupStaleEntries();
        tracker.initRun(99L, List.<String[]>of(new String[]{KEY, "Loc1", "2026-10-03", "SUNSET"}));
        taskEvent(99L, LocationTaskState.EVALUATING);

        assertThat(waiting.names).containsExactly("task-update", "run-summary");
        assertThat(departed.names).isEmpty();
    }

    @Test
    @DisplayName("a phase change on a run with no tasks broadcasts nothing")
    void setPhase_runWithNoTasks_broadcastsNothing() {
        tracker.initRun(55L, List.of());
        ScriptedEmitter watcher = subscribe(55L);
        int before = watcher.names.size();

        tracker.setPhase(55L, RunPhase.TRIAGE);

        assertThat(watcher.names).hasSize(before);
        assertThat(tracker.getProgress(55L).getPhase()).isEqualTo(RunPhase.TRIAGE);
    }

    @Test
    @DisplayName("the production constructor schedules its expiry on a daemon thread, and shutdown stops the scheduler")
    void productionConstructor_andShutdown() {
        RunProgressTracker production = new RunProgressTracker(mock(DynamicSchedulerService.class));

        SseEmitter emitter = production.subscribe(12345L);
        production.shutdown();

        assertThat(emitter).isNotNull();
        tracker.shutdown();
        verify(graceScheduler).shutdownNow();
    }
}
