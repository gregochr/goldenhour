package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.service.ask.AskReadyPrecompute;
import com.gregochr.goldenhour.service.ask.ReadyQuestion;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AstroConditionsRefreshJob}. The night selector is the real
 * {@link AuroraForecastRunService} over the real solar-utils calculator and a fixed clock, so the
 * two nights asserted are the ones the production rule picks at that instant, not a stubbed date.
 */
@ExtendWith(MockitoExtension.class)
class AstroConditionsRefreshJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Mock
    private AstroConditionsService astroConditionsService;

    @Mock
    private JobRunService jobRunService;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    @Mock
    private AskReadyPrecompute askReadyPrecompute;

    private final SolarCalculator sun = new SolarCalculator();
    private JobRunEntity jobRun;

    @BeforeEach
    void setUp() {
        jobRun = JobRunEntity.builder().id(91L).build();
    }

    private AuroraForecastRunService selectorAt(Instant instant) {
        return new AuroraForecastRunService(null, null, null, null, null, new AuroraProperties(), sun,
                mock(AuroraStateCache.class), null, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private AstroConditionsRefreshJob jobAt(Instant instant) {
        return jobAt(instant, Runnable::run);
    }

    /** The job with the given executor standing in for the background one the precompute runs on. */
    private AstroConditionsRefreshJob jobAt(Instant instant, Executor executor) {
        return new AstroConditionsRefreshJob(astroConditionsService, selectorAt(instant), jobRunService,
                dynamicSchedulerService, askReadyPrecompute, executor, Clock.fixed(instant, ZoneOffset.UTC));
    }

    /** Nautical dawn on {@link #TODAY}: the instant the selector itself says the small-hours night ends. */
    private Instant nauticalDawn() {
        return selectorAt(Instant.parse("2026-10-11T01:00:00Z")).currentNight().endsAt();
    }

    private void stubRun(boolean manual) {
        when(jobRunService.startRun(RunType.WEATHER, manual, null)).thenReturn(jobRun);
    }

    @Test
    @DisplayName("registers a manual-aware target under the key V170 seeds, and it runs this job")
    void registerJob_registersUnderTheSeededKey() {
        AstroConditionsRefreshJob job = jobAt(Instant.parse("2026-10-11T15:30:00Z"));
        job.registerJob();

        // The literal is asserted, not JOB_KEY: the key is the join to the scheduler_job_config row
        // V170 seeds, and a schedule whose key matches no target fires silently forever.
        ArgumentCaptor<Consumer<Boolean>> target = ArgumentCaptor.captor();
        verify(dynamicSchedulerService)
                .registerManualAwareJobTarget(eq("astro_conditions_refresh"), target.capture());

        stubRun(true);
        when(astroConditionsService.evaluateAndPersist(List.of(TODAY, TODAY.plusDays(1)))).thenReturn(4);
        target.getValue().accept(true);

        // The manual flag reaches the job run: the Scheduler screen's Run now is recorded as manual.
        verify(jobRunService).startRun(RunType.WEATHER, true, null);
    }

    @Test
    @DisplayName("in the afternoon the nights are tonight and tomorrow night")
    void afternoon_scoresTonightAndTomorrowNight() {
        stubRun(false);
        List<LocalDate> expected = List.of(TODAY, TODAY.plusDays(1));
        when(astroConditionsService.evaluateAndPersist(expected)).thenReturn(526);

        jobAt(Instant.parse("2026-10-11T15:30:00Z")).run(false);

        verify(astroConditionsService).evaluateAndPersist(expected);
        verify(jobRunService).completeRun(jobRun, 526, 0, expected);
    }

    @Test
    @DisplayName("after midnight and before dawn the running night is skipped, not rescored")
    void smallHours_skipsTheRunningNight() {
        stubRun(true);
        List<LocalDate> expected = List.of(TODAY, TODAY.plusDays(1));
        when(astroConditionsService.evaluateAndPersist(expected)).thenReturn(3);

        // 01:00 UTC on the 11th. currentNightDate() alone says the 10th (its dawn has not happened),
        // but the batch forecast starts at 00:00 UTC today, so the 10th would be scored on its
        // post-midnight hours only and overwrite the afternoon run's complete row.
        jobAt(Instant.parse("2026-10-11T01:00:00Z")).run(true);

        verify(astroConditionsService).evaluateAndPersist(expected);
        verify(astroConditionsService, never()).evaluateAndPersist(List.of(YESTERDAY, TODAY));
    }

    @Test
    @DisplayName("the max holds across nautical dawn: the nights do not flip at the boundary")
    void nauticalDawn_doesNotChangeTheNights() {
        Instant dawn = nauticalDawn();
        List<LocalDate> expected = List.of(TODAY, TODAY.plusDays(1));
        when(jobRunService.startRun(RunType.WEATHER, false, null)).thenReturn(jobRun);
        when(astroConditionsService.evaluateAndPersist(expected)).thenReturn(1);

        // A second before dawn the selector still names yesterday's night; at dawn it names today's.
        assertThat(selectorAt(dawn.minusSeconds(1)).currentNightDate()).isEqualTo(YESTERDAY);
        assertThat(selectorAt(dawn).currentNightDate()).isEqualTo(TODAY);

        jobAt(dawn.minusSeconds(1)).run(false);
        jobAt(dawn).run(false);

        verify(astroConditionsService, times(2)).evaluateAndPersist(expected);
    }

    @Test
    @DisplayName("just after UK midnight in BST the UK date leads the selector, and the later one wins")
    void bstJustAfterUkMidnight_usesTheUkCivilDate() {
        stubRun(false);
        LocalDate tomorrow = TODAY.plusDays(1);
        List<LocalDate> expected = List.of(tomorrow, tomorrow.plusDays(1));
        when(astroConditionsService.evaluateAndPersist(expected)).thenReturn(2);

        // 23:30 UTC on the 11th is 00:30 BST on the 12th: the selector (UTC date, after dawn) says the
        // 11th; the UK civil date is the 12th, and the 11th's night is the one in progress.
        Instant instant = Instant.parse("2026-10-11T23:30:00Z");
        assertThat(selectorAt(instant).currentNightDate()).isEqualTo(TODAY);

        jobAt(instant).run(false);

        verify(astroConditionsService).evaluateAndPersist(expected);
    }

    @Test
    @DisplayName("a service failure fails the run, records the reason and rethrows nothing")
    void serviceThrows_failsTheRunAndKeepsPreviousRows() {
        stubRun(false);
        List<LocalDate> dates = List.of(TODAY, TODAY.plusDays(1));
        when(astroConditionsService.evaluateAndPersist(dates))
                .thenThrow(new IllegalStateException("database unavailable"));

        jobAt(Instant.parse("2026-10-11T15:30:00Z")).run(false);

        assertThat(jobRun.getNotes()).isEqualTo("database unavailable");
        verify(jobRunService).completeRun(jobRun, 0, 2, dates);
    }

    @Test
    @DisplayName("an exception with no message and an over-long one both fit the notes column")
    void failureNotes_areBoundedAndNeverNull() {
        List<LocalDate> dates = List.of(TODAY, TODAY.plusDays(1));
        when(jobRunService.startRun(RunType.WEATHER, false, null)).thenReturn(jobRun);
        doThrow(new IllegalStateException("x".repeat(900))).when(astroConditionsService)
                .evaluateAndPersist(dates);
        jobAt(Instant.parse("2026-10-11T15:30:00Z")).run(false);
        assertThat(jobRun.getNotes()).hasSize(400);

        JobRunEntity second = JobRunEntity.builder().id(92L).build();
        when(jobRunService.startRun(RunType.WEATHER, false, null)).thenReturn(second);
        doThrow(new IllegalStateException()).when(astroConditionsService).evaluateAndPersist(dates);
        jobAt(Instant.parse("2026-10-11T15:30:00Z")).run(false);
        assertThat(second.getNotes()).isEqualTo("null");
    }

    @Test
    @DisplayName("a run that scores nothing still completes cleanly, with zero written")
    void nothingScored_completesWithZero() {
        stubRun(false);
        List<LocalDate> dates = List.of(TODAY, TODAY.plusDays(1));
        when(astroConditionsService.evaluateAndPersist(dates)).thenReturn(0);

        jobAt(Instant.parse("2026-10-11T15:30:00Z")).run(false);

        verify(jobRunService).completeRun(jobRun, 0, 0, dates);
    }

    @Test
    @DisplayName("a trigger that arrives while a run is in progress is skipped, and the guard resets")
    void concurrentTrigger_isSkippedAndGuardReleases() throws Exception {
        stubRun(false);
        List<LocalDate> dates = List.of(TODAY, TODAY.plusDays(1));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(astroConditionsService.evaluateAndPersist(dates)).thenAnswer(inv -> {
            calls.incrementAndGet();
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 2;
        });
        AstroConditionsRefreshJob job = jobAt(Instant.parse("2026-10-11T15:30:00Z"));

        Thread first = new Thread(() -> job.run(false));
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        job.run(false);

        release.countDown();
        first.join(5000);
        assertThat(calls.get()).isEqualTo(1);
        verify(jobRunService, times(1)).startRun(RunType.WEATHER, false, null);

        // The guard is released once the first run ends, so the next scheduled fire is not skipped.
        job.run(false);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the guard is released even when the run fails")
    void failedRun_releasesTheGuard() {
        when(jobRunService.startRun(RunType.WEATHER, false, null)).thenReturn(jobRun);
        List<LocalDate> dates = List.of(TODAY, TODAY.plusDays(1));
        when(astroConditionsService.evaluateAndPersist(dates))
                .thenThrow(new IllegalStateException("boom")).thenReturn(1);
        AstroConditionsRefreshJob job = jobAt(Instant.parse("2026-10-11T15:30:00Z"));

        job.run(false);
        job.run(false);

        verify(astroConditionsService, times(2)).evaluateAndPersist(dates);
    }

    @Test
    @DisplayName("construction alone touches nothing: no job run, no scoring")
    void construction_doesNothing() {
        jobAt(Instant.parse("2026-10-11T15:30:00Z"));

        verifyNoInteractions(astroConditionsService, jobRunService, dynamicSchedulerService,
                askReadyPrecompute);
    }

    // -- the Ready precompute that follows a successful run (ask-night plan section 2.0 (3)) ------------------

    private static final Instant AFTERNOON = Instant.parse("2026-10-11T15:30:00Z");
    /** The only question the dispatched precompute may carry: the one the astro run makes stale. */
    private static final Set<ReadyQuestion> NIGHT_ONLY = Set.of(ReadyQuestion.NIGHT_SKY);
    private static final List<LocalDate> NIGHTS = List.of(TODAY, TODAY.plusDays(1));

    @Test
    @DisplayName("a scheduled run that scored something dispatches the COUNTED precompute, after the job run "
            + "is complete")
    void scheduledSuccess_dispatchesTheCountedPrecomputeAfterTheRun() {
        stubRun(false);
        when(astroConditionsService.evaluateAndPersist(NIGHTS)).thenReturn(526);
        when(askReadyPrecompute.precompute(null, NIGHT_ONLY)).thenReturn(new AskReadyPrecompute.Result(14, 3, 0, null));

        jobAt(AFTERNOON).run(false);

        InOrder order = inOrder(jobRunService, askReadyPrecompute);
        order.verify(jobRunService).completeRun(jobRun, 526, 0, NIGHTS);
        order.verify(askReadyPrecompute).precompute(null, NIGHT_ONLY);
        verify(askReadyPrecompute, never()).precomputeOnDemand(NIGHT_ONLY);
    }

    @Test
    @DisplayName("a manual run takes the on-demand route the admin endpoint takes: not counted against the "
            + "per-day ceiling")
    void manualSuccess_dispatchesTheOnDemandPrecompute() {
        stubRun(true);
        when(astroConditionsService.evaluateAndPersist(NIGHTS)).thenReturn(3);
        when(askReadyPrecompute.precomputeOnDemand(NIGHT_ONLY))
                .thenReturn(new AskReadyPrecompute.Result(1, 0, 0, null));

        jobAt(AFTERNOON).run(true);

        verify(askReadyPrecompute).precomputeOnDemand(NIGHT_ONLY);
        verify(askReadyPrecompute, never()).precompute(null, NIGHT_ONLY);
    }

    @Test
    @DisplayName("a run that scored nothing wrote no new generation, so nothing is dispatched")
    void nothingScored_dispatchesNothing() {
        stubRun(false);
        when(astroConditionsService.evaluateAndPersist(NIGHTS)).thenReturn(0);

        jobAt(AFTERNOON).run(false);

        verifyNoInteractions(askReadyPrecompute);
    }

    @Test
    @DisplayName("a run that threw dispatches nothing")
    void failedRun_dispatchesNothing() {
        stubRun(false);
        when(astroConditionsService.evaluateAndPersist(NIGHTS)).thenThrow(new IllegalStateException("boom"));

        jobAt(AFTERNOON).run(false);

        verifyNoInteractions(askReadyPrecompute);
    }

    @Test
    @DisplayName("the precompute is queued, never run on the job's own thread: the run returns before it starts")
    void dispatch_isQueuedOffTheJobsThread() {
        stubRun(false);
        when(astroConditionsService.evaluateAndPersist(NIGHTS)).thenReturn(2);
        when(askReadyPrecompute.precompute(null, NIGHT_ONLY)).thenReturn(new AskReadyPrecompute.Result(1, 0, 0, null));
        List<Runnable> queued = new ArrayList<>();

        jobAt(AFTERNOON, queued::add).run(false);

        assertThat(queued).hasSize(1);
        verifyNoInteractions(askReadyPrecompute);
        queued.getFirst().run();
        verify(askReadyPrecompute).precompute(null, NIGHT_ONLY);
    }

    @Test
    @DisplayName("a precompute that is refused (Ask off, a simulation, no fresh briefing) is logged, not thrown")
    void refusedPrecompute_isHarmless() {
        stubRun(false);
        when(astroConditionsService.evaluateAndPersist(NIGHTS)).thenReturn(2);
        when(askReadyPrecompute.precompute(null, NIGHT_ONLY))
                .thenReturn(new AskReadyPrecompute.Result(0, 0, 0, "Ask is switched off"));

        jobAt(AFTERNOON).run(false);

        verify(askReadyPrecompute).precompute(null, NIGHT_ONLY);
        verify(jobRunService).completeRun(jobRun, 2, 0, NIGHTS);
    }

    @Test
    @DisplayName("an exception inside the precompute never reaches the job, and the job's guard is released")
    void precomputeThrows_neverReachesTheJob() {
        stubRun(false);
        when(astroConditionsService.evaluateAndPersist(NIGHTS)).thenReturn(2);
        when(askReadyPrecompute.precompute(null, NIGHT_ONLY)).thenThrow(new IllegalStateException("model down"));
        AstroConditionsRefreshJob job = jobAt(AFTERNOON);

        job.run(false);
        job.run(false);

        verify(jobRunService, times(2)).startRun(RunType.WEATHER, false, null);
        verify(jobRunService, times(2)).completeRun(jobRun, 2, 0, NIGHTS);
        verify(jobRunService, never()).completeRun(jobRun, 0, 2, NIGHTS);
    }

    @Test
    @DisplayName("the dispatch carries NIGHT_SKY and nothing else, on both routes, never the unfiltered precompute")
    void dispatch_carriesOnlyTheNightQuestion() {
        stubRun(false);
        when(astroConditionsService.evaluateAndPersist(NIGHTS)).thenReturn(2);
        jobAt(AFTERNOON).run(false);
        ArgumentCaptor<Set<ReadyQuestion>> scheduled = ArgumentCaptor.captor();
        verify(askReadyPrecompute).precompute(eq(null), scheduled.capture());
        assertThat(scheduled.getValue()).containsExactly(ReadyQuestion.NIGHT_SKY);

        stubRun(true);
        jobAt(AFTERNOON).run(true);
        ArgumentCaptor<Set<ReadyQuestion>> manual = ArgumentCaptor.captor();
        verify(askReadyPrecompute).precomputeOnDemand(manual.capture());
        assertThat(manual.getValue()).containsExactly(ReadyQuestion.NIGHT_SKY);
        verify(askReadyPrecompute, never()).precompute(null);
        verify(askReadyPrecompute, never()).precomputeOnDemand();
    }

    @Test
    @DisplayName("an executor that refuses the work is logged and the run still ends complete, not failed")
    void executorRejects_runStillComplete() {
        stubRun(false);
        when(astroConditionsService.evaluateAndPersist(NIGHTS)).thenReturn(2);
        Executor rejecting = task -> {
            throw new RejectedExecutionException("shut down");
        };

        jobAt(AFTERNOON, rejecting).run(false);

        verify(jobRunService).completeRun(jobRun, 2, 0, NIGHTS);
        verify(jobRunService, never()).completeRun(jobRun, 0, 2, NIGHTS);
        verifyNoInteractions(askReadyPrecompute);
    }
}
