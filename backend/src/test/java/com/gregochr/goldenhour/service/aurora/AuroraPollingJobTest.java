package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraPollingJob}: which kind of poll runs when, the window it hands over,
 * and the guard that keeps two cycles from running at once.
 *
 * <p>The clock is pinned in January 2027 and the sun stubbed per date — civil dawn 08:00 and civil
 * dusk 16:00 every day, so nautical dawn is 07:25 and nautical dusk 16:35 — which keeps every instant
 * here independent of the day the suite runs.
 */
@ExtendWith(MockitoExtension.class)
class AuroraPollingJobTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    /** The job's twilight reference point (Durham). */
    private static final double DURHAM_LAT = 54.776;
    private static final double DURHAM_LON = -1.575;

    private static final LocalDate JAN_14 = LocalDate.of(2027, 1, 14);
    private static final LocalDate JAN_15 = LocalDate.of(2027, 1, 15);

    /** The night of the 14th: nautical dusk 16:35 on the 14th to nautical dawn 07:25 on the 15th. */
    private static final TonightWindow NIGHT_OF_14TH =
            new TonightWindow(utc("2027-01-14T16:35"), utc("2027-01-15T07:25"));

    @Mock
    private AuroraOrchestrator orchestrator;
    @Mock
    private SolarCalculator solarCalculator;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private AuroraProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuroraProperties();
        properties.setEnabled(true);
    }

    // -------------------------------------------------------------------------
    // executePoll — daylight runs the lookahead alone, darkness runs the night poll
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("in daylight the poll runs the lookahead alone, for tonight, at the clock's instant")
    void executePoll_daylight_runsTheLookaheadAlone() {
        sunForAnEveningOn(JAN_14);
        ZonedDateTime noon = utc("2027-01-14T12:00");
        when(orchestrator.runForecastLookahead(NIGHT_OF_14TH, noon))
                .thenReturn(AuroraStateCache.Action.NOTIFY);

        AuroraOrchestrator.PollOutcome outcome = jobAt(noon).executePoll();

        assertThat(outcome).isEqualTo(
                new AuroraOrchestrator.PollOutcome(AuroraStateCache.Action.NOTIFY, null));
        verify(orchestrator, never()).runNightPoll(any(), any());
    }

    @Test
    @DisplayName("in the evening the poll runs the night poll for tonight, and nothing else")
    void executePoll_evening_runsTheNightPoll() {
        sunForAnEveningOn(JAN_14);
        ZonedDateTime evening = utc("2027-01-14T22:00");
        AuroraOrchestrator.PollOutcome night = new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.SUPPRESS, AuroraStateCache.Action.SUPPRESS);
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, evening)).thenReturn(night);

        assertThat(jobAt(evening).executePoll()).isSameAs(night);
        verify(orchestrator, never()).runForecastLookahead(any(), any());
    }

    @Test
    @DisplayName("in the small hours the poll runs the night poll for the night still in progress")
    void executePoll_smallHours_runsTheNightPollForTheNightInProgress() {
        sunForSmallHoursOn(JAN_15);
        ZonedDateTime twoAm = utc("2027-01-15T02:00");
        AuroraOrchestrator.PollOutcome night = new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.CLEAR);
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, twoAm)).thenReturn(night);

        assertThat(jobAt(twoAm).executePoll()).isSameAs(night);
    }

    @Test
    @DisplayName("at nautical dawn itself the night is over: a daylight poll for the next night")
    void executePoll_atNauticalDawn_isADaylightPollForTheNextNight() {
        sunForAnEveningOn(JAN_15);
        ZonedDateTime dawn = utc("2027-01-15T07:25");
        TonightWindow nightOf15th =
                new TonightWindow(utc("2027-01-15T16:35"), utc("2027-01-16T07:25"));
        when(orchestrator.runForecastLookahead(nightOf15th, dawn))
                .thenReturn(AuroraStateCache.Action.NONE);

        assertThat(jobAt(dawn).executePoll().dark()).isFalse();
    }

    @Test
    @DisplayName("a second before nautical dawn it is still the night of the 14th")
    void executePoll_aSecondBeforeNauticalDawn_isStillTheNight() {
        sunForSmallHoursOn(JAN_15);
        ZonedDateTime beforeDawn = utc("2027-01-15T07:24:59");
        AuroraOrchestrator.PollOutcome night = new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE);
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, beforeDawn)).thenReturn(night);

        assertThat(jobAt(beforeDawn).executePoll()).isSameAs(night);
    }

    @Test
    @DisplayName("at nautical dusk itself the night has begun")
    void executePoll_atNauticalDusk_isANightPoll() {
        sunForAnEveningOn(JAN_14);
        ZonedDateTime dusk = utc("2027-01-14T16:35");
        AuroraOrchestrator.PollOutcome night = new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE);
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, dusk)).thenReturn(night);

        assertThat(jobAt(dusk).executePoll()).isSameAs(night);
    }

    @Test
    @DisplayName("a second before nautical dusk it is still daylight")
    void executePoll_aSecondBeforeNauticalDusk_isADaylightPoll() {
        sunForAnEveningOn(JAN_14);
        ZonedDateTime beforeDusk = utc("2027-01-14T16:34:59");
        when(orchestrator.runForecastLookahead(NIGHT_OF_14TH, beforeDusk))
                .thenReturn(AuroraStateCache.Action.NONE);

        assertThat(jobAt(beforeDusk).executePoll().dark()).isFalse();
    }

    // -------------------------------------------------------------------------
    // calculateTonightWindow
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("in the daytime tonight is today's nautical dusk to tomorrow's nautical dawn")
    void calculateTonightWindow_daytime_isTodaysDuskToTomorrowsDawn() {
        sunForAnEveningOn(JAN_14);

        assertThat(jobAt(utc("2027-01-14T12:00")).calculateTonightWindow(utc("2027-01-14T12:00")))
                .isEqualTo(NIGHT_OF_14TH);
    }

    @Test
    @DisplayName("after midnight tonight is still the window that opened at yesterday's dusk")
    void calculateTonightWindow_postMidnight_isTheNightInProgress() {
        sunForSmallHoursOn(JAN_15);

        assertThat(jobAt(utc("2027-01-15T02:00")).calculateTonightWindow(utc("2027-01-15T02:00")))
                .isEqualTo(NIGHT_OF_14TH);
    }

    @Test
    @DisplayName("the window is the one the instant names, whatever the job's own clock reads")
    void calculateTonightWindow_usesTheInstantGiven_notTheClock() {
        sunForSmallHoursOn(JAN_14);
        // The clock says the 15th; the instant asked about is 02:00 on the 14th.
        AuroraPollingJob job = jobAt(utc("2027-01-15T12:00"));

        assertThat(job.calculateTonightWindow(utc("2027-01-14T02:00")))
                .isEqualTo(new TonightWindow(utc("2027-01-13T16:35"), utc("2027-01-14T07:25")));
    }

    // -------------------------------------------------------------------------
    // The cycle guard: never two cycles at once, whichever route starts them
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("while the admin route's cycle runs, the schedule and the admin route are both refused")
    void runCycleIfIdle_holdingTheCycle_refusesBothRoutes() {
        sunForAnEveningOn(JAN_14);
        ZonedDateTime evening = utc("2027-01-14T22:00");
        AuroraPollingJob job = jobAt(evening);
        List<Optional<AuroraOrchestrator.PollOutcome>> refusals = new ArrayList<>();
        AuroraOrchestrator.PollOutcome night = new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE);
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, evening)).thenAnswer(invocation -> {
            if (refusals.isEmpty()) {
                // Inside the running cycle. Ask twice: a refusal that released a guard it never
                // took would let the second attempt through.
                refusals.add(job.runCycleIfIdle());
                job.poll();
                refusals.add(job.runCycleIfIdle());
            }
            return night;
        });

        assertThat(job.runCycleIfIdle()).contains(night);

        assertThat(refusals).containsExactly(Optional.empty(), Optional.empty());
        verify(orchestrator, times(1)).runNightPoll(NIGHT_OF_14TH, evening);
        assertThat(job.runCycleIfIdle())
                .as("the guard is free again once the cycle has finished")
                .contains(night);
        verify(orchestrator, times(2)).runNightPoll(NIGHT_OF_14TH, evening);
    }

    @Test
    @DisplayName("while the schedule's cycle runs, the admin route and the schedule are both refused")
    void poll_holdingTheCycle_refusesBothRoutes() {
        sunForAnEveningOn(JAN_14);
        ZonedDateTime evening = utc("2027-01-14T22:00");
        AuroraPollingJob job = jobAt(evening);
        List<Optional<AuroraOrchestrator.PollOutcome>> refusals = new ArrayList<>();
        AuroraOrchestrator.PollOutcome night = new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE);
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, evening)).thenAnswer(invocation -> {
            if (refusals.isEmpty()) {
                refusals.add(job.runCycleIfIdle());
                job.poll();
                refusals.add(job.runCycleIfIdle());
            }
            return night;
        });

        job.poll();

        assertThat(refusals).containsExactly(Optional.empty(), Optional.empty());
        verify(orchestrator, times(1)).runNightPoll(NIGHT_OF_14TH, evening);
        assertThat(job.runCycleIfIdle()).contains(night);
    }

    @Test
    @DisplayName("a cycle that throws still frees the guard for the next")
    void runCycleIfIdle_afterACycleThrows_runsTheNext() {
        sunForAnEveningOn(JAN_14);
        ZonedDateTime evening = utc("2027-01-14T22:00");
        AuroraPollingJob job = jobAt(evening);
        AuroraOrchestrator.PollOutcome night = new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE);
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, evening))
                .thenThrow(new IllegalStateException("triage exploded"))
                .thenReturn(night);

        assertThatThrownBy(job::runCycleIfIdle).hasMessage("triage exploded");

        assertThat(job.runCycleIfIdle()).contains(night);
    }

    @Test
    @DisplayName("a scheduled poll that throws still frees the guard for the next")
    void poll_afterACycleThrows_runsTheNext() {
        sunForAnEveningOn(JAN_14);
        ZonedDateTime evening = utc("2027-01-14T22:00");
        AuroraPollingJob job = jobAt(evening);
        AuroraOrchestrator.PollOutcome night = new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE);
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, evening))
                .thenThrow(new IllegalStateException("triage exploded"))
                .thenReturn(night);

        assertThatThrownBy(job::poll).hasMessage("triage exploded");

        assertThat(job.runCycleIfIdle()).contains(night);
    }

    @Test
    @DisplayName("with aurora disabled the schedule does nothing, and takes no guard")
    void poll_disabled_doesNothing() {
        properties.setEnabled(false);
        sunForAnEveningOn(JAN_14);
        ZonedDateTime evening = utc("2027-01-14T22:00");
        AuroraPollingJob job = jobAt(evening);
        AuroraOrchestrator.PollOutcome night = new AuroraOrchestrator.PollOutcome(
                AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE);
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, evening)).thenReturn(night);

        job.poll();
        verifyNoInteractions(orchestrator, solarCalculator);

        assertThat(job.runCycleIfIdle())
                .as("the admin route ignores aurora.enabled, as it always has")
                .contains(night);
    }

    @Test
    @DisplayName("with aurora enabled the schedule runs one cycle")
    void poll_enabled_runsOneCycle() {
        sunForAnEveningOn(JAN_14);
        ZonedDateTime evening = utc("2027-01-14T22:00");
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, evening)).thenReturn(
                new AuroraOrchestrator.PollOutcome(
                        AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE));

        jobAt(evening).poll();

        verify(orchestrator).runNightPoll(NIGHT_OF_14TH, evening);
    }

    @Test
    @DisplayName("the job registers poll() as the aurora_polling target")
    void registerJob_registersPollAsTheTarget() {
        sunForAnEveningOn(JAN_14);
        ZonedDateTime evening = utc("2027-01-14T22:00");
        when(orchestrator.runNightPoll(NIGHT_OF_14TH, evening)).thenReturn(
                new AuroraOrchestrator.PollOutcome(
                        AuroraStateCache.Action.NONE, AuroraStateCache.Action.NONE));
        AuroraPollingJob job = jobAt(evening);

        job.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(dynamicSchedulerService).registerJobTarget(eq("aurora_polling"), target.capture());
        target.getValue().run();
        verify(orchestrator).runNightPoll(NIGHT_OF_14TH, evening);
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private AuroraPollingJob jobAt(ZonedDateTime instant) {
        return new AuroraPollingJob(orchestrator, properties, solarCalculator,
                dynamicSchedulerService, Clock.fixed(instant.toInstant(), UTC));
    }

    private static ZonedDateTime utc(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(UTC);
    }

    /**
     * An instant at or after {@code day}'s nautical dawn: the job reads that day's dawn (to see the
     * night is over), its dusk, and the next day's dawn.
     */
    private void sunForAnEveningOn(LocalDate day) {
        civilDawnOn(day);
        civilDuskOn(day);
        civilDawnOn(day.plusDays(1));
    }

    /**
     * An instant before {@code day}'s nautical dawn: the job reads that day's dawn and the previous
     * day's dusk.
     */
    private void sunForSmallHoursOn(LocalDate day) {
        civilDawnOn(day);
        civilDuskOn(day.minusDays(1));
    }

    private void civilDawnOn(LocalDate date) {
        when(solarCalculator.civilDawn(DURHAM_LAT, DURHAM_LON, date, UTC))
                .thenReturn(LocalDateTime.of(date, LocalTime.of(8, 0)));
    }

    private void civilDuskOn(LocalDate date) {
        when(solarCalculator.civilDusk(DURHAM_LAT, DURHAM_LON, date, UTC))
                .thenReturn(LocalDateTime.of(date, LocalTime.of(16, 0)));
    }
}
