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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /** 09:00 UTC on Friday 2 Oct 2026, the day before the fixtures' slots. */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);

    private RunProgressTracker tracker;
    private ForecastCommandFactory factory;
    private FailedSlotRetryService service;

    @BeforeEach
    void setUp() {
        tracker = new RunProgressTracker(dynamicSchedulerService);
        Map<EvaluationModel, EvaluationStrategy> strategies = Map.of(
                EvaluationModel.HAIKU, haikuStrategy,
                EvaluationModel.SONNET, sonnetStrategy,
                EvaluationModel.OPUS, opusStrategy);
        factory = new ForecastCommandFactory(modelSelectionService, strategies, FIXED_CLOCK);
        service = new FailedSlotRetryService(tracker, jobRunService, locationService, factory,
                commandExecutor, Runnable::run, FIXED_CLOCK);
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
        playRunUnfinished(runId, taskDescriptors, states);
        tracker.completeRun(runId); // a retry is offered only for a run that has finished
    }

    /** As {@link #playRun}, but the run is left going: it has not completed. */
    private void playRunUnfinished(long runId, List<String[]> taskDescriptors, List<LocationTaskState> states) {
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
    private void playEightSlotRunWithTwoFailed() {
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
    @DisplayName("eight slots (two places, dates, events), two failed: exactly those two are handed "
            + "to the executor, not the 2 places x 2 dates x 2 events product")
    void retry_eightSlotRunWithTwoFailed_runsExactlyTheTwo() {
        playEightSlotRunWithTwoFailed();
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
        playEightSlotRunWithTwoFailed();
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
        playEightSlotRunWithTwoFailed();
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
        playEightSlotRunWithTwoFailed();
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
        playEightSlotRunWithTwoFailed();
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
        playEightSlotRunWithTwoFailed();
        when(jobRunService.findRun(ORIGINAL_RUN)).thenReturn(Optional.empty());

        assertThat(service.retry(ORIGINAL_RUN)).isEqualTo(new FailedSlotRetryService.NothingToRetry());
        verifyNothingStarted();
    }

    @Test
    @DisplayName("a failed slot whose place has been disabled or deleted is left out and reported; "
            + "the other failed slot still runs")
    void retry_placeDisabled_skippedAndReported() {
        playEightSlotRunWithTwoFailed();
        stubOriginalRunType(RunType.SHORT_TERM);
        LocationEntity durham = sky(1L, "Durham");
        stubRoster(durham); // Bamburgh is no longer enabled
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 1,
                List.of(new FailedSlotRetryService.SkippedSlot("Bamburgh", "2026-10-04", "SUNRISE",
                        "The place is disabled, renamed or no longer exists."))));
        ForecastCommand command = executedCommand();
        assertThat(command.locations()).containsExactly(durham);
        assertThat(command.slots()).containsExactly(new ForecastSlot("Durham", SATURDAY, TargetType.SUNSET));
    }

    @Test
    @DisplayName("a failed slot whose place is no longer a sky location is left out and reported")
    void retry_placeNoLongerSky_skippedAndReported() {
        playEightSlotRunWithTwoFailed();
        stubOriginalRunType(RunType.SHORT_TERM);
        LocationEntity durham = sky(1L, "Durham");
        stubRoster(durham, hide(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(((FailedSlotRetryService.Started) outcome).skipped()).containsExactly(
                new FailedSlotRetryService.SkippedSlot("Bamburgh", "2026-10-04", "SUNRISE",
                        "The place is no longer a sky location."));
        ForecastCommand command = executedCommand();
        assertThat(command.locations()).containsExactly(durham);
        assertThat(command.slots()).containsExactly(new ForecastSlot("Durham", SATURDAY, TargetType.SUNSET));
    }

    @Test
    @DisplayName("when every failed place is still enabled but none is a sky location there is nothing to "
            + "retry and nothing is started")
    void retry_everyFailedPlaceNoLongerSky_nothingToRetry() {
        playEightSlotRunWithTwoFailed();
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster(hide(1L, "Durham"), hide(2L, "Bamburgh"));

        assertThat(service.retry(ORIGINAL_RUN)).isEqualTo(new FailedSlotRetryService.NothingToRetry());
        verifyNothingStarted();
    }

    // ------------------------------------------------------- slots dated before today (UK civil date)

    private FailedSlotRetryService serviceAt(String instant) {
        return new FailedSlotRetryService(tracker, jobRunService, locationService, factory, commandExecutor,
                Runnable::run, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    /** A run in which Durham's 2026-10-02 sunset and 2026-10-03 sunrise failed. */
    private void playRunFailedOnThe2ndAnd3rd() {
        playRun(ORIGINAL_RUN,
                List.of(task("Durham", "2026-10-02", "SUNSET"), task("Durham", "2026-10-03", "SUNRISE")),
                List.of(LocationTaskState.FAILED, LocationTaskState.FAILED));
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster(sky(1L, "Durham"));
    }

    private void stubStartUnderSonnet() {
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);
    }

    @Test
    @DisplayName("22:30 UTC on 2 Oct is 23:30 in London, still the 2nd: a slot dated the 2nd is today's and is "
            + "retried, the 3rd too")
    void retry_beforeLondonMidnight_todaysSlotIsRetried() {
        playRunFailedOnThe2ndAnd3rd();
        stubStartUnderSonnet();

        FailedSlotRetryService.Outcome outcome = serviceAt("2026-10-02T22:30:00Z").retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 2, List.of()));
    }

    @Test
    @DisplayName("23:30 UTC on 2 Oct is 00:30 on the 3rd in London (BST): the 2nd is yesterday, so its slot "
            + "is left out as already happened though the UTC date is still the 2nd; the 3rd is retried")
    void retry_afterLondonMidnight_yesterdaysSlotIsLeftOut() {
        playRunFailedOnThe2ndAnd3rd();
        stubStartUnderSonnet();

        FailedSlotRetryService.Outcome outcome = serviceAt("2026-10-02T23:30:00Z").retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 1,
                List.of(new FailedSlotRetryService.SkippedSlot("Durham", "2026-10-02", "SUNSET",
                        "The event has already happened."))));
        assertThat(executedCommand().slots())
                .containsExactly(new ForecastSlot("Durham", SATURDAY, TargetType.SUNRISE));
    }

    @Test
    @DisplayName("when every failed slot is dated before today there is nothing to retry and nothing is started")
    void retry_everySlotInThePast_nothingToRetry() {
        playRunFailedOnThe2ndAnd3rd();

        assertThat(serviceAt("2026-10-04T09:00:00Z").retry(ORIGINAL_RUN))
                .isEqualTo(new FailedSlotRetryService.NothingToRetry());
        verifyNothingStarted();
    }

    @Test
    @DisplayName("when every failed place is gone there is nothing to retry and nothing is started")
    void retry_everyPlaceGone_nothingToRetry() {
        playEightSlotRunWithTwoFailed();
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

    // ------------------------------------------------- only a finished run, and only once

    @Test
    @DisplayName("a run that is still going is refused with the literal sentence and nothing is started")
    void retry_runStillGoing_refusedAndNothingStarted() {
        playRunUnfinished(ORIGINAL_RUN,
                List.of(task("Durham", "2026-10-03", "SUNSET"), task("Bamburgh", "2026-10-04", "SUNRISE")),
                List.of(LocationTaskState.FAILED, LocationTaskState.EVALUATING));

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Refused(
                "This run is still going. Retry is offered when it has finished."));
        verifyNothingStarted();
        verify(jobRunService, never()).findRun(anyLong());
    }

    @Test
    @DisplayName("a second retry of the same run is refused with a sentence naming the first retry's run, "
            + "and starts nothing")
    void retry_twice_secondRefusedNamingTheFirstRetry() {
        playEightSlotRunWithTwoFailed();
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster(sky(1L, "Durham"), sky(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        FailedSlotRetryService.Outcome first = service.retry(ORIGINAL_RUN);
        FailedSlotRetryService.Outcome second = service.retry(ORIGINAL_RUN);

        assertThat(first).isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 2, List.of()));
        assertThat(second).isEqualTo(new FailedSlotRetryService.Refused(
                "This run has already been retried as run 8. Retry that run's failures instead."));
        verify(jobRunService, times(1)).startRun(RunType.SHORT_TERM, true, null, null);
        executedCommand(); // verifies exactly one execution, of the new run
    }

    @Test
    @DisplayName("two concurrent retry requests for one run start exactly ONE run; the other is refused "
            + "naming it")
    void retry_twoConcurrentRequests_startExactlyOneRun() throws Exception {
        playEightSlotRunWithTwoFailed();
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster(sky(1L, "Durham"), sky(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        CountDownLatch insideFirstStart = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        when(jobRunService.startRun(RunType.SHORT_TERM, true, null, null)).thenAnswer(invocation -> {
            insideFirstStart.countDown();
            releaseStart.await();
            return jobRunRow(NEW_RUN, RunType.SHORT_TERM);
        });
        AtomicReference<FailedSlotRetryService.Outcome> fromFirst = new AtomicReference<>();
        AtomicReference<FailedSlotRetryService.Outcome> fromSecond = new AtomicReference<>();
        Thread first = new Thread(() -> fromFirst.set(service.retry(ORIGINAL_RUN)));
        Thread second = new Thread(() -> fromSecond.set(service.retry(ORIGINAL_RUN)));

        first.start();
        assertThat(insideFirstStart.await(10, TimeUnit.SECONDS)).isTrue();
        second.start();
        // The second request is now racing the first, which is held inside the start of its run. Wait
        // until the second has either finished or is parked (on the retry lock, or inside a second start).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (second.getState() == Thread.State.NEW || second.getState() == Thread.State.RUNNABLE) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            second.join(5);
        }
        releaseStart.countDown();
        first.join(10_000);
        second.join(10_000);

        verify(jobRunService, times(1)).startRun(RunType.SHORT_TERM, true, null, null);
        executedCommand(); // verifies exactly one execution, of the new run
        assertThat(List.of(fromFirst.get(), fromSecond.get())).containsExactlyInAnyOrder(
                new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 2, List.of()),
                new FailedSlotRetryService.Refused(
                        "This run has already been retried as run 8. Retry that run's failures instead."));
    }

    @Test
    @DisplayName("a retry that came to nothing is not recorded: the run can still be retried afterwards")
    void retry_thatStartedNothing_leavesTheRunRetryable() {
        playEightSlotRunWithTwoFailed();
        stubOriginalRunType(RunType.SHORT_TERM);
        LocationEntity durham = sky(1L, "Durham");
        LocationEntity bamburgh = sky(2L, "Bamburgh");
        when(locationService.findAllEnabled()).thenReturn(List.of(), List.of(durham, bamburgh));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        FailedSlotRetryService.Outcome nothing = service.retry(ORIGINAL_RUN);
        FailedSlotRetryService.Outcome later = service.retry(ORIGINAL_RUN);

        assertThat(nothing).isEqualTo(new FailedSlotRetryService.NothingToRetry());
        assertThat(later).isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 2, List.of()));
    }

    @Test
    @DisplayName("a retry of a retry still works after the original has been retried: it chains from the "
            + "retry run")
    void retry_chain_originalThenRetryRun_bothStart() {
        playEightSlotRunWithTwoFailed();
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster(sky(1L, "Durham"), sky(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        when(jobRunService.startRun(RunType.SHORT_TERM, true, null, null))
                .thenReturn(jobRunRow(NEW_RUN, RunType.SHORT_TERM), jobRunRow(9L, RunType.SHORT_TERM));
        assertThat(service.retry(ORIGINAL_RUN))
                .isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 2, List.of()));
        // The retry run (8) held the two retried slots, and one of them failed again.
        playRun(NEW_RUN,
                List.of(task("Durham", "2026-10-03", "SUNSET"), task("Bamburgh", "2026-10-04", "SUNRISE")),
                List.of(LocationTaskState.COMPLETE, LocationTaskState.FAILED));
        when(jobRunService.findRun(NEW_RUN)).thenReturn(Optional.of(jobRunRow(NEW_RUN, RunType.SHORT_TERM)));

        FailedSlotRetryService.Outcome chained = service.retry(NEW_RUN);

        assertThat(chained).isEqualTo(new FailedSlotRetryService.Started(9L, RunType.SHORT_TERM, 1, List.of()));
    }

    // ------------------------------------------------- a retry that never ran does not block

    private void stubTwoStarts() {
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster(sky(1L, "Durham"), sky(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        when(jobRunService.startRun(RunType.SHORT_TERM, true, null, null))
                .thenReturn(jobRunRow(NEW_RUN, RunType.SHORT_TERM), jobRunRow(9L, RunType.SHORT_TERM));
    }

    @Test
    @DisplayName("a retry run that failed before it registered any task (completed with no task) does not "
            + "block: the original can be retried again")
    void retry_afterTheRetryRunFailedBeforeItHadAnyTask_originalIsRetriedAgain() {
        playEightSlotRunWithTwoFailed();
        stubTwoStarts();
        assertThat(service.retry(ORIGINAL_RUN))
                .isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 2, List.of()));
        // The executor never got as far as initRun for run 8: its guard registers it failed, with no tasks.
        tracker.failRun(NEW_RUN, "The run stopped unexpectedly. See the server log.");

        FailedSlotRetryService.Outcome again = service.retry(ORIGINAL_RUN);

        assertThat(again).isEqualTo(new FailedSlotRetryService.Started(9L, RunType.SHORT_TERM, 2, List.of()));
        verify(jobRunService, times(2)).startRun(RunType.SHORT_TERM, true, null, null);
    }

    @Test
    @DisplayName("a retry run that ran and has failures of its own still takes the retry: the 409 stands")
    void retry_afterTheRetryRunRanWithFailures_stillRefused() {
        playEightSlotRunWithTwoFailed();
        stubTwoStarts();
        service.retry(ORIGINAL_RUN);
        playRun(NEW_RUN,
                List.of(task("Durham", "2026-10-03", "SUNSET"), task("Bamburgh", "2026-10-04", "SUNRISE")),
                List.of(LocationTaskState.COMPLETE, LocationTaskState.FAILED));

        FailedSlotRetryService.Outcome again = service.retry(ORIGINAL_RUN);

        assertThat(again).isEqualTo(new FailedSlotRetryService.Refused(
                "This run has already been retried as run 8. Retry that run's failures instead."));
        verify(jobRunService, times(1)).startRun(RunType.SHORT_TERM, true, null, null);
    }

    @Test
    @DisplayName("a retry run that is registered and still going still takes the retry: the 409 stands")
    void retry_whileTheRetryRunIsGoing_stillRefused() {
        playEightSlotRunWithTwoFailed();
        stubTwoStarts();
        service.retry(ORIGINAL_RUN);
        playRunUnfinished(NEW_RUN,
                List.of(task("Durham", "2026-10-03", "SUNSET"), task("Bamburgh", "2026-10-04", "SUNRISE")),
                List.of(LocationTaskState.EVALUATING, LocationTaskState.PENDING));

        FailedSlotRetryService.Outcome again = service.retry(ORIGINAL_RUN);

        assertThat(again).isEqualTo(new FailedSlotRetryService.Refused(
                "This run has already been retried as run 8. Retry that run's failures instead."));
    }

    @Test
    @DisplayName("a retry run whose tracker entry is gone blocks only during the registration grace period "
            + "(5 minutes), then the original can be retried again")
    void retry_afterTheRetryRunsEntryIsGone_originalIsRetriedAgainOnceTheGracePasses() {
        MutableTestClock serviceClock = new MutableTestClock(Instant.parse("2026-10-02T09:00:00Z"));
        service = new FailedSlotRetryService(tracker, jobRunService, locationService, factory,
                commandExecutor, Runnable::run, serviceClock);
        playEightSlotRunWithTwoFailed();
        stubTwoStarts();
        service.retry(ORIGINAL_RUN); // the mocked executor never registers run 8 in the tracker

        serviceClock.advance(Duration.ofMinutes(4).plusSeconds(59));
        FailedSlotRetryService.Outcome withinGrace = service.retry(ORIGINAL_RUN);
        serviceClock.advance(Duration.ofSeconds(1));
        FailedSlotRetryService.Outcome afterGrace = service.retry(ORIGINAL_RUN);

        assertThat(withinGrace).isEqualTo(new FailedSlotRetryService.Refused(
                "This run has already been retried as run 8. Retry that run's failures instead."));
        assertThat(afterGrace).isEqualTo(new FailedSlotRetryService.Started(9L, RunType.SHORT_TERM, 2, List.of()));
    }

    // ------------------------------------------------- the retry window follows COMPLETION

    private MutableTestClock useClockedTracker() {
        MutableTestClock trackerClock = new MutableTestClock(Instant.parse("2026-10-02T09:00:00Z"));
        tracker = new RunProgressTracker(dynamicSchedulerService, mock(ScheduledExecutorService.class),
                5_000L, trackerClock);
        service = new FailedSlotRetryService(tracker, jobRunService, locationService, factory,
                commandExecutor, Runnable::run, FIXED_CLOCK);
        return trackerClock;
    }

    @Test
    @DisplayName("a retry is still possible just before a completed run's eviction, 30 minutes after it "
            + "finished, even though the run started far longer ago")
    void retry_justBeforeEvictionOfACompletedRun_stillStarts() {
        MutableTestClock trackerClock = useClockedTracker();
        playRunUnfinished(ORIGINAL_RUN,
                List.of(task("Durham", "2026-10-03", "SUNSET"), task("Bamburgh", "2026-10-04", "SUNRISE")),
                List.of(LocationTaskState.FAILED, LocationTaskState.COMPLETE));
        trackerClock.advance(Duration.ofMinutes(50)); // a long run
        tracker.completeRun(ORIGINAL_RUN);
        trackerClock.advance(Duration.ofMinutes(29).plusSeconds(59));
        tracker.cleanupStaleEntries();
        stubOriginalRunType(RunType.SHORT_TERM);
        stubRoster(sky(1L, "Durham"), sky(2L, "Bamburgh"));
        when(modelSelectionService.getActiveModel(RunType.SHORT_TERM)).thenReturn(EvaluationModel.SONNET);
        stubStart(RunType.SHORT_TERM);

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.Started(NEW_RUN, RunType.SHORT_TERM, 1, List.of()));
    }

    @Test
    @DisplayName("a retry just after a completed run's eviction answers nothing to retry (the 404)")
    void retry_justAfterEvictionOfACompletedRun_nothingToRetry() {
        MutableTestClock trackerClock = useClockedTracker();
        playRunUnfinished(ORIGINAL_RUN,
                List.of(task("Durham", "2026-10-03", "SUNSET"), task("Bamburgh", "2026-10-04", "SUNRISE")),
                List.of(LocationTaskState.FAILED, LocationTaskState.COMPLETE));
        tracker.completeRun(ORIGINAL_RUN);
        trackerClock.advance(Duration.ofMinutes(30));
        tracker.cleanupStaleEntries();

        FailedSlotRetryService.Outcome outcome = service.retry(ORIGINAL_RUN);

        assertThat(outcome).isEqualTo(new FailedSlotRetryService.NothingToRetry());
        verifyNothingStarted();
    }
}
