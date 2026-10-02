package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.RunProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RunCompletion}: the sweep of unfinished tasks, the order and independence
 * of the abort steps, and the fixed reason phrases.
 */
@ExtendWith(MockitoExtension.class)
class RunCompletionTest {

    private static final long RUN_ID = 5L;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private RunProgressTracker progressTracker;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RunCompletion completion;
    private JobRunEntity jobRun;

    @BeforeEach
    void setUp() {
        completion = new RunCompletion(jobRunService, progressTracker, eventPublisher);
        jobRun = new JobRunEntity();
        jobRun.setId(RUN_ID);
    }

    /** A progress holding one task per state, keyed {@code <STATE>|2026-10-03|SUNSET}. */
    private static RunProgress progressInEveryState() {
        RunProgress progress = new RunProgress(RUN_ID);
        for (LocationTaskState state : LocationTaskState.values()) {
            String key = state.name() + "|2026-10-03|SUNSET";
            progress.registerTask(key, state.name(), "2026-10-03", "SUNSET");
            progress.updateTask(new LocationTaskEvent(RunCompletionTest.class, RUN_ID, key,
                    state.name(), "2026-10-03", "SUNSET", state, null, null));
        }
        return progress;
    }

    private List<LocationTaskEvent> publishedEvents(int expected) {
        ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
        verify(eventPublisher, times(expected)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("an Open-Meteo style failure anywhere in the cause chain gets the weather reason")
    void reasonForForecastRun_weatherFailures_getWeatherReason() {
        assertThat(RunCompletion.reasonForForecastRun(
                new WeatherDataFetchException("x", "Durham", "SUNSET", null)))
                .isEqualTo("Weather data (Open-Meteo) could not be fetched; nothing was updated.");
        assertThat(RunCompletion.reasonForForecastRun(
                new IllegalStateException("wrapper", new ResourceAccessException("I/O error"))))
                .isEqualTo("Weather data (Open-Meteo) could not be fetched; nothing was updated.");
    }

    @Test
    @DisplayName("any other failure gets the generic reason, never the exception's own text")
    void reasonForForecastRun_otherFailures_getGenericReasonWithoutRawMessage() {
        String reason = RunCompletion.reasonForForecastRun(
                new IllegalStateException("jdbc:postgresql://secret-host/db password=hunter2"));

        assertThat(reason).isEqualTo("The run stopped unexpectedly. See the server log.");
        assertThat(reason).doesNotContain("hunter2").doesNotContain("IllegalStateException");
    }

    /** A chain of plain exceptions with a RestClientException at {@code index} (0 = the outermost). */
    private static Throwable chainWithWeatherCauseAt(int index) {
        Throwable inner = new ResourceAccessException("I/O error");
        for (int i = index - 1; i >= 0; i--) {
            inner = new IllegalStateException("level " + i, inner);
        }
        return inner;
    }

    @Test
    @DisplayName("a RestClientException at chain index 9 is found")
    void reasonForForecastRun_weatherCauseAtDepthNine_isFound() {
        assertThat(RunCompletion.reasonForForecastRun(chainWithWeatherCauseAt(9)))
                .isEqualTo("Weather data (Open-Meteo) could not be fetched; nothing was updated.");
    }

    @Test
    @DisplayName("a RestClientException at chain index 10 is beyond the search depth and not found")
    void reasonForForecastRun_weatherCauseAtDepthTen_isNotFound() {
        assertThat(RunCompletion.reasonForForecastRun(chainWithWeatherCauseAt(10)))
                .isEqualTo("The run stopped unexpectedly. See the server log.");
    }

    @Test
    @DisplayName("a cause chain that loops back on itself still terminates")
    void reasonForForecastRun_cyclicCauseChain_terminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(RunCompletion.reasonForForecastRun(a))
                .isEqualTo("The run stopped unexpectedly. See the server log.");
    }

    @Test
    @DisplayName("abort publishes FAILED once for each of the five non-terminal states, with the state as "
            + "failedStep, and leaves COMPLETE, SKIPPED, TRIAGED and FAILED alone")
    void abort_failsEveryNonTerminalTaskOnceAndLeavesTerminalOnesAlone() {
        when(progressTracker.getProgress(RUN_ID)).thenReturn(progressInEveryState());

        completion.abort(jobRun, "The run stopped unexpectedly. See the server log.");

        List<LocationTaskEvent> events = publishedEvents(5);
        assertThat(events).extracting(LocationTaskEvent::getTaskKey).containsExactlyInAnyOrder(
                "PENDING|2026-10-03|SUNSET", "FETCHING_WEATHER|2026-10-03|SUNSET",
                "FETCHING_CLOUD|2026-10-03|SUNSET", "FETCHING_TIDES|2026-10-03|SUNSET",
                "EVALUATING|2026-10-03|SUNSET");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getState()).isEqualTo(LocationTaskState.FAILED);
            assertThat(e.getJobRunId()).isEqualTo(RUN_ID);
            assertThat(e.getErrorMessage())
                    .isEqualTo("Did not finish: the run stopped before this place was evaluated.");
            assertThat(e.getFailedStep()).isEqualTo(e.getLocationName());
            assertThat(e.getTargetDate()).isEqualTo("2026-10-03");
            assertThat(e.getTargetType()).isEqualTo("SUNSET");
        });
    }

    @Test
    @DisplayName("normal completion fails a task left in EVALUATING with the evaluation reason and every "
            + "other unfinished state with the not-finished reason")
    void complete_sweepsWithTheReasonForEachState() {
        when(progressTracker.getProgress(RUN_ID)).thenReturn(progressInEveryState());

        completion.complete(jobRun, 3, 1, List.of(LocalDate.parse("2026-10-03")));

        List<LocationTaskEvent> events = publishedEvents(5);
        assertThat(events).filteredOn(e -> e.getFailedStep().equals("EVALUATING"))
                .singleElement().extracting(LocationTaskEvent::getErrorMessage)
                .isEqualTo("Evaluation failed (see server log).");
        assertThat(events).filteredOn(e -> !e.getFailedStep().equals("EVALUATING"))
                .hasSize(4)
                .extracting(LocationTaskEvent::getErrorMessage)
                .containsOnly("Did not finish (see server log).");
    }

    @Test
    @DisplayName("normal completion sweeps first, then closes the job_run, then completes the tracker")
    void complete_ordersSweepThenJobRunThenTracker() {
        when(progressTracker.getProgress(RUN_ID)).thenReturn(progressInEveryState());
        List<LocalDate> dates = List.of(LocalDate.parse("2026-10-03"));

        completion.complete(jobRun, 3, 1, dates);

        InOrder order = inOrder(eventPublisher, jobRunService, progressTracker);
        ArgumentCaptor<LocationTaskEvent> swept = ArgumentCaptor.forClass(LocationTaskEvent.class);
        order.verify(eventPublisher, times(5)).publishEvent(swept.capture());
        // The tracker's own counts (one COMPLETE task, one FAILED task), not the 3 and 1 passed in.
        order.verify(jobRunService).completeRun(jobRun, 1, 1, dates);
        order.verify(progressTracker).completeRun(RUN_ID);
        assertThat(swept.getAllValues()).extracting(LocationTaskEvent::getFailedStep)
                .containsExactlyInAnyOrder("PENDING", "FETCHING_WEATHER", "FETCHING_CLOUD",
                        "FETCHING_TIDES", "EVALUATING");
    }

    @Test
    @DisplayName("the date-less overload closes the job_run through the date-less service method")
    void complete_withoutDates_usesThreeArgumentServiceMethod() {
        completion.complete(jobRun, 2, 0);

        verify(jobRunService).completeRun(jobRun, 2, 0);
        verify(progressTracker).completeRun(RUN_ID);
    }

    /**
     * A {@code completeRun} that behaves like the real one: it stamps {@code completedAt} on the
     * in-memory entity and THEN the save fails, so the entity claims to be closed while the row is not.
     */
    private void failsAfterStampingCompletedAt(JobRunEntity entity, int succeeded, int failed,
            List<LocalDate> dates) {
        doAnswer(inv -> {
            JobRunEntity run = inv.getArgument(0);
            run.setCompletedAt(LocalDateTime.parse("2026-10-02T09:00:00"));
            throw new IllegalStateException("save failed");
        }).doAnswer(inv -> {
            JobRunEntity run = inv.getArgument(0);
            run.setCompletedAt(LocalDateTime.parse("2026-10-02T09:00:01"));
            return null;
        }).when(jobRunService).completeRun(entity, succeeded, failed, dates);
    }

    @Test
    @DisplayName("a close whose save fails after completedAt was stamped is retried once, succeeds, and the "
            + "tracker is completed without the failure reaching the caller")
    void complete_saveFailsAfterStampingCompletedAt_isRetriedOnce() {
        List<LocalDate> dates = List.of(LocalDate.parse("2026-10-03"));
        failsAfterStampingCompletedAt(jobRun, 1, 0, dates);

        completion.complete(jobRun, 1, 0, dates);

        verify(jobRunService, times(2)).completeRun(jobRun, 1, 0, dates);
        verify(progressTracker).completeRun(RUN_ID);
        assertThat(jobRun.getCompletedAt()).isEqualTo(LocalDateTime.parse("2026-10-02T09:00:01"));
    }

    @Test
    @DisplayName("when the retry fails too the close is logged at ERROR and abandoned (two attempts, no more), "
            + "the tracker is still completed, and nothing propagates")
    void complete_saveFailsTwice_trackerStillCompletedAndNothingPropagates() {
        List<LocalDate> dates = List.of(LocalDate.parse("2026-10-03"));
        doAnswer(inv -> {
            JobRunEntity run = inv.getArgument(0);
            run.setCompletedAt(LocalDateTime.parse("2026-10-02T09:00:00"));
            throw new IllegalStateException("db gone");
        }).when(jobRunService).completeRun(jobRun, 1, 0, dates);

        completion.complete(jobRun, 1, 0, dates); // must not throw

        verify(jobRunService, times(2)).completeRun(jobRun, 1, 0, dates);
        verify(progressTracker).completeRun(RUN_ID);
    }

    @Test
    @DisplayName("the date-less overload retries a failed close once and completes the tracker")
    void complete_withoutDates_closeFailsOnce_retriedAndTrackerCompleted() {
        doThrow(new IllegalStateException("db gone")).doNothing()
                .when(jobRunService).completeRun(jobRun, 1, 0);

        completion.complete(jobRun, 1, 0);

        verify(jobRunService, times(2)).completeRun(jobRun, 1, 0);
        verify(progressTracker).completeRun(RUN_ID);
    }

    @Test
    @DisplayName("abort does not close again a job_run this helper already closed")
    void abort_afterSuccessfulComplete_doesNotCloseAgain() {
        completion.complete(jobRun, 2, 1);

        completion.abort(jobRun, "reason");

        verify(jobRunService, times(1)).completeRun(jobRun, 2, 1);
        verify(jobRunService, never()).completeRun(jobRun, 0, 0);
    }

    @Test
    @DisplayName("abort closes a job_run that merely CLAIMS to be closed: an in-memory completedAt is not "
            + "proof the row was saved")
    void abort_inMemoryCompletedAtIsNotProofOfClosure() {
        jobRun.setCompletedAt(LocalDateTime.parse("2026-10-02T09:00:00"));

        completion.abort(jobRun, "reason");

        verify(progressTracker).failRun(RUN_ID, "reason");
        verify(jobRunService).completeRun(jobRun, 0, 0);
    }

    @Test
    @DisplayName("a close that failed during complete() is tried again by a later abort, because it never succeeded")
    void abort_afterCompleteWhoseCloseNeverSucceeded_triesAgain() {
        doThrow(new IllegalStateException("db gone")).when(jobRunService).completeRun(jobRun, 2, 1);
        completion.complete(jobRun, 2, 1);
        verify(jobRunService, times(2)).completeRun(jobRun, 2, 1);

        completion.abort(jobRun, "reason");

        verify(jobRunService).completeRun(jobRun, 0, 0); // abort made its own attempt: nothing had closed the row
    }

    @Test
    @DisplayName("abort runs sweep, then tracker, then job_run, closing it with the tracker's own counts")
    void abort_ordersSweepTrackerJobRun_andUsesTrackerCounts() {
        RunProgress progress = new RunProgress(RUN_ID);
        progress.registerTask("A|2026-10-03|SUNSET", "A", "2026-10-03", "SUNSET");
        progress.registerTask("B|2026-10-03|SUNSET", "B", "2026-10-03", "SUNSET");
        progress.registerTask("C|2026-10-03|SUNSET", "C", "2026-10-03", "SUNSET");
        progress.updateTask(new LocationTaskEvent(this, RUN_ID, "A|2026-10-03|SUNSET", "A",
                "2026-10-03", "SUNSET", LocationTaskState.COMPLETE, null, null));
        progress.updateTask(new LocationTaskEvent(this, RUN_ID, "B|2026-10-03|SUNSET", "B",
                "2026-10-03", "SUNSET", LocationTaskState.COMPLETE, null, null));
        progress.updateTask(new LocationTaskEvent(this, RUN_ID, "C|2026-10-03|SUNSET", "C",
                "2026-10-03", "SUNSET", LocationTaskState.FAILED, "weather", "FETCHING_WEATHER"));
        when(progressTracker.getProgress(RUN_ID)).thenReturn(progress);

        completion.abort(jobRun, "reason");

        InOrder order = inOrder(progressTracker, jobRunService);
        order.verify(progressTracker).failRun(RUN_ID, "reason");
        order.verify(jobRunService).completeRun(jobRun, 2, 1);
        verify(eventPublisher, never()).publishEvent(any(LocationTaskEvent.class));
    }

    @Test
    @DisplayName("abort on a run the tracker never held closes the job_run with zero counts")
    void abort_untrackedRun_closesJobRunWithZeroCounts() {
        when(progressTracker.getProgress(RUN_ID)).thenReturn(null);

        completion.abort(jobRun, "reason");

        verify(progressTracker).failRun(RUN_ID, "reason");
        verify(jobRunService).completeRun(jobRun, 0, 0);
    }

    @Test
    @DisplayName("each abort step is independent: failures publishing, completing the tracker and closing "
            + "the job_run are each survived, and every step is still attempted")
    void abort_eachStepSurvivesTheOthersFailing() {
        when(progressTracker.getProgress(RUN_ID)).thenReturn(progressInEveryState());
        doThrow(new IllegalStateException("publisher down"))
                .when(eventPublisher).publishEvent(any(LocationTaskEvent.class));
        doThrow(new IllegalStateException("tracker down")).when(progressTracker).failRun(RUN_ID, "reason");
        doThrow(new IllegalStateException("db down")).when(jobRunService).completeRun(jobRun, 1, 1);

        completion.abort(jobRun, "reason"); // must not throw

        // one failed publish did not stop the other four
        ArgumentCaptor<LocationTaskEvent> attempted = ArgumentCaptor.forClass(LocationTaskEvent.class);
        verify(eventPublisher, times(5)).publishEvent(attempted.capture());
        assertThat(attempted.getAllValues()).extracting(LocationTaskEvent::getFailedStep)
                .containsExactlyInAnyOrder("PENDING", "FETCHING_WEATHER", "FETCHING_CLOUD",
                        "FETCHING_TIDES", "EVALUATING");
        verify(progressTracker).failRun(RUN_ID, "reason");
        // the fixture holds one COMPLETE and one FAILED task; the failing close is tried twice
        verify(jobRunService, times(2)).completeRun(jobRun, 1, 1);
    }

    // -------------------------------------------------------------------------
    // job_run counts are the tracker's; the run-level reason goes to notes
    // -------------------------------------------------------------------------

    private static final String KEY_REJECTED_RUN =
            "Claude rejected the API key. The run was stopped; no further places were attempted.";

    /** Two COMPLETE, three FAILED, one TRIAGED, one SKIPPED, and one task still EVALUATING. */
    private static RunProgress progressWithOutcomes() {
        RunProgress progress = new RunProgress(RUN_ID);
        String[][] outcomes = {
                {"a", "COMPLETE"}, {"b", "COMPLETE"}, {"c", "FAILED"}, {"d", "FAILED"}, {"e", "FAILED"},
                {"f", "TRIAGED"}, {"g", "SKIPPED"}, {"h", "EVALUATING"}};
        for (String[] outcome : outcomes) {
            String key = outcome[0] + "|2026-10-03|SUNSET";
            progress.registerTask(key, outcome[0], "2026-10-03", "SUNSET");
            progress.updateTask(new LocationTaskEvent(RunCompletionTest.class, RUN_ID, key, outcome[0],
                    "2026-10-03", "SUNSET", LocationTaskState.valueOf(outcome[1]), null, null));
        }
        return progress;
    }

    @Test
    @DisplayName("the job_run closes with the tracker's completed and failed counts, not the caller's: a triaged "
            + "or skipped task is neither, and the task still evaluating is failed by the sweep first")
    void complete_closesWithTheTrackersCounts() {
        RunProgress progress = progressWithOutcomes();
        when(progressTracker.getProgress(RUN_ID)).thenReturn(progress);
        // The real tracker applies each published event to its progress; replay that here.
        org.mockito.Mockito.doAnswer(inv -> {
            progress.updateTask(inv.getArgument(0));
            return null;
        }).when(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(LocationTaskEvent.class));
        List<LocalDate> dates = List.of(LocalDate.parse("2026-10-03"));

        completion.complete(jobRun, 99, 99, dates);

        verify(jobRunService).completeRun(jobRun, 2, 4, dates);
    }

    @Test
    @DisplayName("the date-less overload takes the tracker's counts too")
    void complete_withoutDates_closesWithTheTrackersCounts() {
        RunProgress progress = progressWithOutcomes();
        when(progressTracker.getProgress(RUN_ID)).thenReturn(progress);

        completion.complete(jobRun, 99, 99);

        // The sweep's event goes to a mocked publisher here, so the EVALUATING task is not yet FAILED.
        verify(jobRunService).completeRun(jobRun, 2, 3);
    }

    @Test
    @DisplayName("a run-level reason is written to the job_run's notes")
    void complete_writesTheRunReasonToNotes() {
        RunProgress progress = new RunProgress(RUN_ID);
        progress.stop(KEY_REJECTED_RUN);
        when(progressTracker.getProgress(RUN_ID)).thenReturn(progress);

        completion.complete(jobRun, 0, 0, null);

        assertThat(jobRun.getNotes()).isEqualTo(KEY_REJECTED_RUN);
    }

    @Test
    @DisplayName("a run with no reason leaves the job_run's notes as they were")
    void complete_withoutReason_leavesNotes() {
        jobRun.setNotes("existing note");
        when(progressTracker.getProgress(RUN_ID)).thenReturn(progressWithOutcomes());

        completion.complete(jobRun, 0, 0, null);

        assertThat(jobRun.getNotes()).isEqualTo("existing note");
    }

    @Test
    @DisplayName("abort writes its reason to notes, and keeps the tracker's reason when the run was already stopped")
    void abort_writesReasonToNotes() {
        completion.abort(jobRun, "The run stopped unexpectedly. See the server log.");
        assertThat(jobRun.getNotes()).isEqualTo("The run stopped unexpectedly. See the server log.");

        JobRunEntity stoppedRun = new JobRunEntity();
        stoppedRun.setId(RUN_ID + 1);
        RunProgress stopped = new RunProgress(RUN_ID + 1);
        stopped.stop(KEY_REJECTED_RUN);
        when(progressTracker.getProgress(RUN_ID + 1)).thenReturn(stopped);

        completion.abort(stoppedRun, "The run stopped unexpectedly. See the server log.");

        assertThat(stoppedRun.getNotes()).isEqualTo(KEY_REJECTED_RUN);
    }
}
