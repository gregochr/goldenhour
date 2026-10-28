package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the status {@link RunProgress#getStatus()} derives, in particular for a run that
 * failed as a whole.
 */
class RunProgressTest {

    private static final String REASON = "The run stopped unexpectedly. See the server log.";

    private static RunProgress runWithTasks(LocationTaskState... states) {
        RunProgress progress = new RunProgress(1L);
        for (int i = 0; i < states.length; i++) {
            String key = "Loc" + i + "|2026-10-03|SUNSET";
            progress.registerTask(key, "Loc" + i, "2026-10-03", "SUNSET");
            progress.updateTask(new LocationTaskEvent(RunProgressTest.class, 1L, key, "Loc" + i,
                    "2026-10-03", "SUNSET", states[i], null, null));
        }
        return progress;
    }

    @Test
    @DisplayName("a run with no tasks and no failure reads COMPLETE")
    void noTasks_noFailure_isComplete() {
        assertThat(new RunProgress(1L).getStatus()).isEqualTo(RunProgress.RunStatus.COMPLETE);
        assertThat(new RunProgress(1L).getFailureReason()).isNull();
    }

    @Test
    @DisplayName("a run-level failure on a run with no tasks forces FAILED, not COMPLETE")
    void noTasks_withFailure_isFailed() {
        RunProgress progress = new RunProgress(1L);

        progress.markFailed(REASON);

        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.FAILED);
        assertThat(progress.getFailureReason()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("a run-level failure with a completed task reads PARTIAL")
    void failure_withCompletedTask_isPartial() {
        RunProgress progress = runWithTasks(LocationTaskState.COMPLETE, LocationTaskState.FAILED);

        progress.markFailed(REASON);

        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.PARTIAL);
    }

    @Test
    @DisplayName("a run-level failure with a triaged task reads PARTIAL: triage is an outcome")
    void failure_withTriagedTask_isPartial() {
        RunProgress progress = runWithTasks(LocationTaskState.TRIAGED, LocationTaskState.FAILED);

        progress.markFailed(REASON);

        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.PARTIAL);
    }

    @Test
    @DisplayName("a run-level failure with only failed and skipped tasks reads FAILED")
    void failure_withOnlyFailedAndSkipped_isFailed() {
        RunProgress progress = runWithTasks(LocationTaskState.FAILED, LocationTaskState.SKIPPED);

        progress.markFailed(REASON);

        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.FAILED);
    }

    @Test
    @DisplayName("a run-level failure never reads RUNNING, even with a task still in progress")
    void failure_withTaskStillInProgress_isNotRunning() {
        RunProgress progress = runWithTasks(LocationTaskState.EVALUATING);

        progress.markFailed(REASON);

        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.FAILED);
    }

    @Test
    @DisplayName("without a run-level failure, failed tasks beside only skipped ones read FAILED")
    void noFailure_failedAndSkipped_isFailed() {
        RunProgress progress = runWithTasks(LocationTaskState.FAILED, LocationTaskState.FAILED,
                LocationTaskState.SKIPPED);

        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.FAILED);
    }

    @Test
    @DisplayName("without a run-level failure, a completed task beside a failed one reads PARTIAL")
    void noFailure_completedAndFailed_isPartial() {
        RunProgress progress = runWithTasks(LocationTaskState.COMPLETE, LocationTaskState.FAILED);

        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.PARTIAL);
    }

    @Test
    @DisplayName("without a run-level failure, a triaged task beside a failed one reads PARTIAL")
    void noFailure_triagedAndFailed_isPartial() {
        RunProgress progress = runWithTasks(LocationTaskState.TRIAGED, LocationTaskState.FAILED);

        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.PARTIAL);
    }

    @Test
    @DisplayName("without a run-level failure, an unfinished task keeps the run RUNNING")
    void noFailure_taskInProgress_isRunning() {
        RunProgress progress = runWithTasks(LocationTaskState.COMPLETE, LocationTaskState.EVALUATING);

        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.RUNNING);
    }

    @Test
    @DisplayName("without a run-level failure, completed and skipped tasks read COMPLETE")
    void noFailure_completedAndSkipped_isComplete() {
        RunProgress progress = runWithTasks(LocationTaskState.COMPLETE, LocationTaskState.SKIPPED);

        assertThat(progress.getStatus()).isEqualTo(RunProgress.RunStatus.COMPLETE);
    }
}
