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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        order.verify(eventPublisher, times(5)).publishEvent(any(LocationTaskEvent.class));
        order.verify(jobRunService).completeRun(jobRun, 3, 1, dates);
        order.verify(progressTracker).completeRun(RUN_ID);
    }

    @Test
    @DisplayName("the date-less overload closes the job_run through the date-less service method")
    void complete_withoutDates_usesThreeArgumentServiceMethod() {
        completion.complete(jobRun, 2, 0);

        verify(jobRunService).completeRun(jobRun, 2, 0);
        verify(progressTracker).completeRun(RUN_ID);
    }

    @Test
    @DisplayName("when closing the job_run throws on the normal path the tracker is still completed, and the "
            + "exception still propagates to the caller's guard")
    void complete_jobRunCloseThrows_trackerStillCompletedAndExceptionPropagates() {
        List<LocalDate> dates = List.of(LocalDate.parse("2026-10-03"));
        doThrow(new IllegalStateException("db gone"))
                .when(jobRunService).completeRun(jobRun, 1, 0, dates);

        assertThatThrownBy(() -> completion.complete(jobRun, 1, 0, dates))
                .isInstanceOf(IllegalStateException.class).hasMessage("db gone");

        verify(progressTracker).completeRun(RUN_ID);
    }

    @Test
    @DisplayName("the date-less overload also completes the tracker when closing the job_run throws")
    void complete_withoutDates_jobRunCloseThrows_trackerStillCompleted() {
        doThrow(new IllegalStateException("db gone")).when(jobRunService).completeRun(jobRun, 1, 0);

        assertThatThrownBy(() -> completion.complete(jobRun, 1, 0))
                .isInstanceOf(IllegalStateException.class);

        verify(progressTracker).completeRun(RUN_ID);
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
    @DisplayName("abort does not touch a job_run that is already closed")
    void abort_closedJobRun_isNotClosedAgain() {
        jobRun.setCompletedAt(LocalDateTime.parse("2026-10-02T09:00:00"));

        completion.abort(jobRun, "reason");

        verify(progressTracker).failRun(RUN_ID, "reason");
        verify(jobRunService, never()).completeRun(any(JobRunEntity.class), any(int.class), any(int.class));
    }

    @Test
    @DisplayName("each abort step is independent: failures publishing, completing the tracker and closing "
            + "the job_run are each survived, and every step is still attempted")
    void abort_eachStepSurvivesTheOthersFailing() {
        when(progressTracker.getProgress(RUN_ID)).thenReturn(progressInEveryState());
        doThrow(new IllegalStateException("publisher down"))
                .when(eventPublisher).publishEvent(any(LocationTaskEvent.class));
        doThrow(new IllegalStateException("tracker down")).when(progressTracker).failRun(RUN_ID, "reason");
        doThrow(new IllegalStateException("db down")).when(jobRunService).completeRun(any(JobRunEntity.class),
                any(int.class), any(int.class));

        completion.abort(jobRun, "reason"); // must not throw

        // one failed publish did not stop the other four
        verify(eventPublisher, times(5)).publishEvent(any(LocationTaskEvent.class));
        verify(progressTracker).failRun(RUN_ID, "reason");
        verify(jobRunService).completeRun(jobRun, 1, 1); // the fixture holds one COMPLETE and one FAILED task
    }
}
