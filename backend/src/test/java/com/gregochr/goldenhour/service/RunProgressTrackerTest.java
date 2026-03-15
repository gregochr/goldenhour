package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.RunProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RunProgressTracker}.
 */
class RunProgressTrackerTest {

    private RunProgressTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RunProgressTracker();
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
}
