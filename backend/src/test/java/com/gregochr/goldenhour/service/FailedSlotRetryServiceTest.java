package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.service.evaluation.EvaluationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FailedSlotRetryService}, over a REAL {@link RunProgressTracker} and a REAL
 * {@link ForecastCommandFactory}: the claim is about which slots are retried and under what, so the
 * run's failed tasks come from the tracker the way they do in production and the command that reaches
 * the executor is the one the factory builds. The executor, the job-run service and the location
 * roster are mocked, and the {@code forecastExecutor} runs the command inline so the command can be
 * captured.
 */
@ExtendWith(MockitoExtension.class)
class FailedSlotRetryServiceTest {

    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 3);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 4);
    private static final long ORIGINAL_RUN = 7L;
    private static final long NEW_RUN = 8L;

    @Mock private DynamicSchedulerService dynamicSchedulerService;
    @Mock private JobRunService jobRunService;
    @Mock private LocationService locationService;
    @Mock private ForecastCommandExecutor commandExecutor;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private EvaluationStrategy haikuStrategy;
    @Mock private EvaluationStrategy sonnetStrategy;
    @Mock private EvaluationStrategy opusStrategy;

    private RunProgressTracker tracker;
    private FailedSlotRetryService service;

    @BeforeEach
    void setUp() {
        tracker = new RunProgressTracker(dynamicSchedulerService);
        Map<EvaluationModel, EvaluationStrategy> strategies = Map.of(
                EvaluationModel.HAIKU, haikuStrategy,
                EvaluationModel.SONNET, sonnetStrategy,
                EvaluationModel.OPUS, opusStrategy);
        ForecastCommandFactory factory = new ForecastCommandFactory(modelSelectionService, strategies,
                Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC));
        service = new FailedSlotRetryService(tracker, jobRunService, locationService, factory,
                commandExecutor, Runnable::run);
    }

    // ---------------------------------------------------------------- fixtures

    private static LocationEntity sky(long id, String name) {
        return LocationEntity.builder().id(id).name(name)
                .locationType(Set.of(LocationType.LANDSCAPE)).build();
    }

    private static LocationEntity hide(long id, String name) {
        return LocationEntity.builder().id(id).name(name)
                .locationType(Set.of(LocationType.WILDLIFE)).build();
    }

    private static String key(String name, String date, String type) {
        return name + "|" + date + "|" + type;
    }

    /** Registers a run's tasks as PENDING, then settles each one to the given state. */
    private void playRun(long runId, List<String[]> taskDescriptors, List<LocationTaskState> states) {
        tracker.initRun(runId, taskDescriptors);
        for (int i = 0; i < taskDescriptors.size(); i++) {
            String[] t = taskDescriptors.get(i);
            LocationTaskState state = states.get(i);
            tracker.onTaskEvent(new LocationTaskEvent(this, runId, t[0], t[1], t[2], t[3], state,
                    state == LocationTaskState.FAILED ? "weather down" : null,
                    state == LocationTaskState.FAILED ? "FETCHING_WEATHER" : null));
        }
    }

    private static String[] task(String name, String date, String type) {
        return new String[]{key(name, date, type), name, date, type};
    }

    /** Two places by two dates by two events; only Durham's Saturday sunset and Bamburgh's Sunday sunrise failed. */
    private void playTheFourSlotRun() {
        List<String[]> tasks = new ArrayList<>();
        List<LocationTaskState> states = new ArrayList<>();
        for (String name : List.of("Durham", "Bamburgh")) {
            for (String date : List.of("2026-10-03", "2026-10-04")) {
                for (String type : List.of("SUNRISE", "SUNSET")) {
                    tasks.add(task(name, date, type));
                    boolean failed = (name.equals("Durham") && date.equals("2026-10-03") && type.equals("SUNSET"))
                            || (name.equals("Bamburgh") && date.equals("2026-10-04") && type.equals("SUNRISE"));
                    states.add(failed ? LocationTaskState.FAILED : LocationTaskState.COMPLETE);
                }
            }
        }
        playRun(ORIGINAL_RUN, tasks, states);
    }

    private JobRunEntity jobRunRow(long id, RunType type) {
        JobRunEntity row = new JobRunEntity();
        row.setId(id);
        row.setRunType(type);
        return row;
    }

    private void stubOriginalRunType(RunType type) {
        when(jobRunService.findRun(ORIGINAL_RUN)).thenReturn(Optional.of(jobRunRow(ORIGINAL_RUN, type)));
    }

    private void stubRoster(LocationEntity... enabled) {
        when(locationService.findAllEnabled()).thenReturn(List.of(enabled));
    }

    private void stubStart(RunType type) {
        when(jobRunService.startRun(type, true, null, null)).thenReturn(jobRunRow(NEW_RUN, type));
    }

    private ForecastCommand executedCommand() {
        return executedCommand(NEW_RUN);
    }

    /** The command the executor was given, asserting it was given the new run's own job_run. */
    private ForecastCommand executedCommand(long expectedJobRunId) {
        ArgumentCaptor<ForecastCommand> command = ArgumentCaptor.forClass(ForecastCommand.class);
        ArgumentCaptor<JobRunEntity> run = ArgumentCaptor.forClass(JobRunEntity.class);
        verify(commandExecutor).execute(command.capture(), run.capture());
        assertThat(run.getValue().getId()).isEqualTo(expectedJobRunId);
        return command.getValue();
    }

    private void verifyNothingStarted() {
        verify(jobRunService, never()).startRun(any(), anyBoolean(), any(), any());
        verify(commandExecutor, never()).execute(any(ForecastCommand.class), any(JobRunEntity.class));
    }

    // ------------------------------------------------------- exact slots

    @Test
    @DisplayName("four slots failed across two places and two dates: exactly those two slots are handed "
            + "to the executor, not the 2 places x 2 dates x 2 events product")
    void retry_runsExactlyTheFailedSlots() {
        playTheFourSlotRun();
        stubOriginalRunType(RunType.SHORT_TERM);
        LocationEntity durham = sky(1L, "Durham");
        LocationEntity bamburgh = sky(2L, "Bamburgh");
        stubRoster(durham, bamburgh);
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 2, List.of()));
        ForecastCommand command = executedCommand();
        assertThat(command.slots()).containsExactlyInAnyOrder(
                new ForecastSlot("Durham", SATURDAY, TargetType.SUNSET),
                new ForecastSlot("Bamburgh", SUNDAY, TargetType.SUNRISE));
        assertThat(command.locations()).containsExactlyInAnyOrder(durham, bamburgh);
        assertThat(command.dates()).containsExactly(SATURDAY, SUNDAY);
        assertThat(command.excludedSlots()).isEmpty();
        assertThat(command.excludedLocations()).isEmpty();
        assertThat(command.triggeredManually()).isTrue();
    }

    @Test
    @DisplayName("a single-location run (Run Forecast on one place) retries as a single-location command")
    void retry_singleLocationRun_staysSingleLocation() {
        playRun(ORIGINAL_RUN,
                List.of(task("Durham", "2026-10-03", "SUNRISE"), task("Durham", "2026-10-03", "SUNSET")),
                List.of(LocationTaskState.FAILED, LocationTaskState.COMPLETE));
        stubOriginalRunType(RunType.SHORT_TERM);
        LocationEntity durham = sky(1L, "Durham");
        stubRoster(durham, sky(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        service.retry(ORIGINAL_RUN);

        ForecastCommand command = executedCommand();
        assertThat(command.locations()).containsExactly(durham);
        assertThat(command.slots()).containsExactly(new ForecastSlot("Durham", SATURDAY, TargetType.SUNRISE));
    }

    @Test
    @DisplayName("the same slot reported failed twice is retried once")
    void retry_duplicateFailedSlot_retriedOnce() {
        // The tracker keys tasks by task key, so a repeat can only come from two keys naming one triple.
        playRun(ORIGINAL_RUN,
                List.of(new String[]{"a", "Durham", "2026-10-03", "SUNSET"},
                        new String[]{"b", "Durham", "2026-10-03", "SUNSET"}),
                List.of(LocationTaskState.FAILED, LocationTaskState.FAILED));
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster(sky(1L, "Durham"));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 1, List.of()));
        assertThat(executedCommand().slots())
                .containsExactly(new ForecastSlot("Durham", SATURDAY, TargetType.SUNSET));
    }

    // ------------------------------------------------------- original run type

    @Test
    @DisplayName("a Very-Short-Term original is retried as VERY_SHORT_TERM: the job_run, the command and "
            + "the strategy are Very-Short-Term's, never Short-Term's")
    void retry_veryShortTermOriginal_usesVeryShortTermConfig() {
        playTheFourSlotRun();
        stubOriginalRunType(RunType.VERY_SHORT_TERM);
        stubRoster(sky(1L, "Durham"), sky(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.VERY_SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        stubStart(RunType.VERY_SHORT_TERM);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isInstanceOf(FailedSlotRetryService.Started.class);
        assertThat(((FailedSlotRetryService.Started) outcome).runType()).isEqualTo(RunType.VERY_SHORT_TERM);
        ForecastCommand command = executedCommand();
        assertThat(command.runType()).isEqualTo(RunType.VERY_SHORT_TERM);
        assertThat(command.strategy()).isSameAs(haikuStrategy);
        verify(jobRunService).startRun(RunType.VERY_SHORT_TERM, true, null, null);
        verify(modelSelectionService, never()).getActiveModel(RunType.SHORT_TERM);
    }

    @Test
    @DisplayName("a Long-Term original is retried as LONG_TERM with Long-Term's model")
    void retry_longTermOriginal_usesLongTermConfig() {
        playTheFourSlotRun();
        stubOriginalRunType(RunType.LONG_TERM);
        stubRoster(sky(1L, "Durham"), sky(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.LONG_TERM)).thenReturn(EvaluationModel.OPUS);
        stubStart(RunType.LONG_TERM);

        service.retry(ORIGINAL_RUN);

        ForecastCommand command = executedCommand();
        assertThat(command.runType()).isEqualTo(RunType.LONG_TERM);
        assertThat(command.strategy()).isSameAs(opusStrategy);
        verify(jobRunService).startRun(RunType.LONG_TERM, true, null, null);
    }

    @Test
    @DisplayName("a retry of a retry stays under the original run type and holds only the slot that failed again")
    void retry_ofARetry_keepsTheRunTypeAndOnlyTheSlotThatFailedAgain() {
        // The retry run (8) held the two retried slots, and one of them failed again.
        playRun(NEW_RUN,
                List.of(task("Durham", "2026-10-03", "SUNSET"), task("Bamburgh", "2026-10-04", "SUNRISE")),
                List.of(LocationTaskState.COMPLETE, LocationTaskState.FAILED));
        when(jobRunService.findRun(NEW_RUN)).thenReturn(Optional.of(jobRunRow(NEW_RUN, RunType.VERY_SHORT_TERM)));
        stubRoster(sky(1L, "Durham"), sky(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.VERY_SHORT_TERM)).thenReturn(EvaluationModel.HAIKU);
        when(jobRunService.startRun(RunType.VERY_SHORT_TERM, true, null, null))
                .thenReturn(jobRunRow(9L, RunType.VERY_SHORT_TERM));

        FailedSlotRetryService.Outcome outcome = service.retry(NEW_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Started(9L, RunType.VERY_SHORT_TERM, 1, List.of()));
        ForecastCommand command = executedCommand(9L);
        assertThat(command.runType()).isEqualTo(RunType.VERY_SHORT_TERM);
        assertThat(command.slots()).containsExactly(new ForecastSlot("Bamburgh", SUNDAY, TargetType.SUNRISE));
    }

    // ------------------------------------------------------- refusals

    @Test
    @DisplayName("a run stopped on a rejected key is refused with the literal sentence and nothing is started")
    void retry_stoppedRun_refusedAndNothingStarted() {
        playTheFourSlotRun();
        tracker.stopRun(ORIGINAL_RUN);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Refused(
                "This run was stopped because Claude rejected the API key. Fix the key, then start the run again."));
        verifyNothingStarted();
    }

    @Test
    @DisplayName("a light-pollution run (failed tasks dated \"–\") is refused with the literal sentence, "
            + "not a 500 from parsing the date, and nothing is started")
    void retry_lightPollutionRun_refusedAndNothingStarted() {
        playRun(ORIGINAL_RUN,
                List.of(new String[]{"Hill|BORTLE", "Hill", "–", "BORTLE"},
                        new String[]{"Fell|BORTLE", "Fell", "–", "BORTLE"}),
                List.of(LocationTaskState.FAILED, LocationTaskState.COMPLETE));

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Refused(
                "Light-pollution failures are retried by pressing Refresh Light Pollution again."));
        verifyNothingStarted();
    }

    @Test
    @DisplayName("a run whose failed tasks are neither forecast slots nor Bortle (hourly) is refused")
    void retry_hourlyRun_refused() {
        playRun(ORIGINAL_RUN, List.<String[]>of(new String[]{"Hide|2026-10-03|HOURLY", "Hide", "2026-10-03", "HOURLY"}),
                List.of(LocationTaskState.FAILED));

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Refused(
                "This run's failed tasks are not forecast slots, so there is nothing to retry here."));
        verifyNothingStarted();
    }

    @Test
    @DisplayName("a job_run of a type that evaluates no forecast slots is refused")
    void retry_nonForecastRunType_refused() {
        playTheFourSlotRun();
        stubOriginalRunType(RunType.WEATHER);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Refused("This kind of run cannot be retried here."));
        verifyNothingStarted();
    }

    // ------------------------------------------------------- edge cases

    @Test
    @DisplayName("a run the tracker no longer holds (evicted, or never tracked) has nothing to retry")
    void retry_evictedRun_nothingToRetry() {
        assertThat(service.retry(404L)).isEqualTo(new FailedSlotRetryService.NothingToRetry());
        verifyNothingStarted();
    }

    @Test
    @DisplayName("a run with no FAILED task has nothing to retry")
    void retry_noFailedTasks_nothingToRetry() {
        playRun(ORIGINAL_RUN, List.<String[]>of(task("Durham", "2026-10-03", "SUNSET")),
                List.of(LocationTaskState.COMPLETE));

        assertThat(service.retry(ORIGINAL_RUN)).isEqualTo(new FailedSlotRetryService.NothingToRetry());
        verifyNothingStarted();
    }

    @Test
    @DisplayName("a run whose job_run row is gone has nothing to retry: its run type is unknown and is not guessed")
    void retry_jobRunRowGone_nothingToRetry() {
        playTheFourSlotRun();
        when(jobRunService.findRun(ORIGINAL_RUN)).thenReturn(Optional.empty());

        assertThat(service.retry(ORIGINAL_RUN)).isEqualTo(new FailedSlotRetryService.NothingToRetry());
        verifyNothingStarted();
    }

    @Test
    @DisplayName("a failed slot whose place has been disabled or deleted is left out and reported; "
            + "the other failed slot still runs")
    void retry_placeDisabled_skippedAndReported() {
        playTheFourSlotRun();
        stubOriginalRunType(RunType.SHORT_TERM);
        LocationEntity durham = sky(1L, "Durham");
        stubRoster(durham); // Bamburgh is no longer enabled
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 1,
                List.of(new FailedSlotRetryService.SkippedSlot("Bamburgh", "2026-10-04", "SUNRISE",
                        "The place is disabled or no longer exists."))));
        ForecastCommand command = executedCommand();
        assertThat(command.locations()).containsExactly(durham);
        assertThat(command.slots()).containsExactly(new ForecastSlot("Durham", SATURDAY, TargetType.SUNSET));
    }

    @Test
    @DisplayName("a failed slot whose place is no longer a sky location is left out and reported")
    void retry_placeNoLongerSky_skippedAndReported() {
        playTheFourSlotRun();
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster(sky(1L, "Durham"), hide(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(((FailedSlotRetryService.Started) outcome).skipped()).containsExactly(
                new FailedSlotRetryService.SkippedSlot("Bamburgh", "2026-10-04", "SUNRISE",
                        "The place is no longer a sky location."));
    }

    @Test
    @DisplayName("when every failed place is gone there is nothing to retry and nothing is started")
    void retry_everyPlaceGone_nothingToRetry() {
        playTheFourSlotRun();
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster();

        assertThat(service.retry(ORIGINAL_RUN)).isEqualTo(new FailedSlotRetryService.NothingToRetry());
        verifyNothingStarted();
    }

    @Test
    @DisplayName("a failed task that names no valid date is left out and reported, never a parse error")
    void retry_malformedDate_skippedAndReported() {
        playRun(ORIGINAL_RUN,
                List.of(new String[]{"a", "Durham", "not-a-date", "SUNSET"},
                        task("Bamburgh", "2026-10-04", "SUNRISE")),
                List.of(LocationTaskState.FAILED, LocationTaskState.FAILED));
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster(sky(1L, "Durham"), sky(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 1,
                List.of(new FailedSlotRetryService.SkippedSlot("Durham", "not-a-date", "SUNSET",
                        "The failed task does not name a date and event."))));
    }

    @Test
    @DisplayName("an unknown run is answered from the tracker alone: the job_run table is not queried")
    void retry_unknownRun_doesNotQueryTheJobRunTable() {
        service.retry(404L);

        verify(jobRunService, never()).findRun(anyLong());
    }
}
