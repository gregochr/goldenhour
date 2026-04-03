package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraPollingJob} logic (excluding the {@code @Scheduled} trigger).
 */
@ExtendWith(MockitoExtension.class)
class AuroraPollingJobTest {

    @Mock private AuroraOrchestrator orchestrator;
    @Mock private SolarCalculator solarCalculator;
    @Mock private DynamicSchedulerService dynamicSchedulerService;

    private AuroraPollingJob job;
    private AuroraProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuroraProperties();
        properties.setEnabled(true);
        job = new AuroraPollingJob(orchestrator, properties, solarCalculator,
                dynamicSchedulerService);
    }

    // -------------------------------------------------------------------------
    // executePoll — dual-path behaviour
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("executePoll always runs forecast lookahead, even during daylight")
    void executePoll_daylight_runsForecastLookaheadOnly() {
        goDay();
        when(orchestrator.runForecastLookahead(any())).thenReturn(AuroraStateCache.Action.NONE);

        job.executePoll();

        verify(orchestrator, times(1)).runForecastLookahead(any());
        verify(orchestrator, never()).run();
    }

    @Test
    @DisplayName("executePoll runs both paths at night")
    void executePoll_darkness_runsBothPaths() {
        goNight();
        when(orchestrator.runForecastLookahead(any())).thenReturn(AuroraStateCache.Action.NONE);
        when(orchestrator.run()).thenReturn(AuroraStateCache.Action.NONE);

        job.executePoll();

        verify(orchestrator, times(1)).runForecastLookahead(any());
        verify(orchestrator, times(1)).run();
    }

    @Test
    @DisplayName("executePoll skips real-time path during daylight regardless of forecast result")
    void executePoll_daylight_skipsRealTimePath() {
        goDay();
        when(orchestrator.runForecastLookahead(any())).thenReturn(AuroraStateCache.Action.NOTIFY);

        job.executePoll();

        verify(orchestrator, never()).run();
    }

    // -------------------------------------------------------------------------
    // isDaylight
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isDaylight returns true when civil dawn was recent and civil dusk is upcoming")
    void isDaylight_daytime_true() {
        goDay();
        assertThat(job.isDaylight()).isTrue();
    }

    @Test
    @DisplayName("isDaylight returns false when civil dusk was hours ago (well into night)")
    void isDaylight_night_false() {
        goNight();
        assertThat(job.isDaylight()).isFalse();
    }

    @Test
    @DisplayName("isDaylight returns false when civil dusk was more than NAUTICAL_BUFFER_MINUTES ago")
    void isDaylight_justAfterDusk_false() {
        // dusk was 36 minutes ago — just past the nautical buffer
        LocalDateTime dusk = LocalDateTime.now(ZoneId.of("UTC")).minusMinutes(36);
        LocalDateTime dawn = LocalDateTime.now(ZoneId.of("UTC")).plusHours(10);
        when(solarCalculator.civilDawn(anyDouble(), anyDouble(), any(), any())).thenReturn(dawn);
        when(solarCalculator.civilDusk(anyDouble(), anyDouble(), any(), any())).thenReturn(dusk);
        assertThat(job.isDaylight()).isFalse();
    }

    @Test
    @DisplayName("isDaylight returns true when civil dusk is less than NAUTICAL_BUFFER_MINUTES away")
    void isDaylight_justBeforeDusk_true() {
        // dusk is 20 minutes from now — still within the nautical buffer
        LocalDateTime dusk = LocalDateTime.now(ZoneId.of("UTC")).plusMinutes(20);
        LocalDateTime dawn = LocalDateTime.now(ZoneId.of("UTC")).minusHours(10);
        when(solarCalculator.civilDawn(anyDouble(), anyDouble(), any(), any())).thenReturn(dawn);
        when(solarCalculator.civilDusk(anyDouble(), anyDouble(), any(), any())).thenReturn(dusk);
        assertThat(job.isDaylight()).isTrue();
    }

    // -------------------------------------------------------------------------
    // calculateTonightWindow
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("calculateTonightWindow returns today-dusk to tomorrow-dawn when it is daytime")
    void calculateTonightWindow_daytime_returnsTodayDuskToTomorrowDawn() {
        // Simulate daytime: today's dawn was 6 hours ago, dusk is in 6 hours
        ZoneId utc = ZoneId.of("UTC");
        LocalDate today = LocalDate.now(utc);
        LocalDateTime now = LocalDateTime.now(utc);

        LocalDateTime todayDawn = now.minusHours(6);
        LocalDateTime todayDusk = now.plusHours(6);
        LocalDateTime tomorrowDawn = now.plusHours(20);

        // civilDawn(today) → todayDawn, civilDawn(today+1) → tomorrowDawn
        // civilDusk(today) → todayDusk
        when(solarCalculator.civilDawn(anyDouble(), anyDouble(), eq(today), any()))
                .thenReturn(todayDawn);
        when(solarCalculator.civilDawn(anyDouble(), anyDouble(), eq(today.plusDays(1)), any()))
                .thenReturn(tomorrowDawn);
        when(solarCalculator.civilDusk(anyDouble(), anyDouble(), eq(today), any()))
                .thenReturn(todayDusk);

        TonightWindow window = job.calculateTonightWindow();

        // Dusk = todayDusk + NAUTICAL_BUFFER, Dawn = tomorrowDawn - NAUTICAL_BUFFER
        ZonedDateTime expectedDusk = todayDusk.plusMinutes(AuroraPollingJob.NAUTICAL_BUFFER_MINUTES).atZone(utc);
        ZonedDateTime expectedDawn = tomorrowDawn.minusMinutes(AuroraPollingJob.NAUTICAL_BUFFER_MINUTES).atZone(utc);

        assertThat(window.dusk()).isEqualTo(expectedDusk);
        assertThat(window.dawn()).isEqualTo(expectedDawn);
        assertThat(window.dusk()).isBefore(window.dawn());
    }

    @Test
    @DisplayName("calculateTonightWindow returns yesterday-dusk to today-dawn when it is post-midnight")
    void calculateTonightWindow_postMidnight_returnsCurrentDarkPeriod() {
        // Simulate post-midnight: today's dawn is 3 hours from now (we are in the overnight period)
        ZoneId utc = ZoneId.of("UTC");
        LocalDate today = LocalDate.now(utc);
        LocalDateTime now = LocalDateTime.now(utc);

        LocalDateTime todayDawn = now.plusHours(3);    // dawn hasn't happened yet
        LocalDateTime yesterdayDusk = now.minusHours(5); // dusk was 5 hours ago

        when(solarCalculator.civilDawn(anyDouble(), anyDouble(), eq(today), any()))
                .thenReturn(todayDawn);
        when(solarCalculator.civilDusk(anyDouble(), anyDouble(), eq(today.minusDays(1)), any()))
                .thenReturn(yesterdayDusk);

        TonightWindow window = job.calculateTonightWindow();

        ZonedDateTime expectedDusk = yesterdayDusk.plusMinutes(AuroraPollingJob.NAUTICAL_BUFFER_MINUTES).atZone(utc);
        ZonedDateTime expectedDawn = todayDawn.minusMinutes(AuroraPollingJob.NAUTICAL_BUFFER_MINUTES).atZone(utc);

        assertThat(window.dusk()).isEqualTo(expectedDusk);
        assertThat(window.dawn()).isEqualTo(expectedDawn);
    }

    @Test
    @DisplayName("tonight window dusk is always before dawn")
    void calculateTonightWindow_duskAlwaysBeforeDawn() {
        // Simulate midday: dawn 8h ago, dusk in 8h, tomorrow dawn in 20h
        ZoneId utc = ZoneId.of("UTC");
        LocalDate today = LocalDate.now(utc);
        LocalDateTime now = LocalDateTime.now(utc);

        when(solarCalculator.civilDawn(anyDouble(), anyDouble(), eq(today), any()))
                .thenReturn(now.minusHours(8));
        when(solarCalculator.civilDusk(anyDouble(), anyDouble(), eq(today), any()))
                .thenReturn(now.plusHours(8));
        when(solarCalculator.civilDawn(anyDouble(), anyDouble(), eq(today.plusDays(1)), any()))
                .thenReturn(now.plusHours(20));

        TonightWindow window = job.calculateTonightWindow();

        assertThat(window.dusk()).isBefore(window.dawn());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Configures the solar calculator so {@code isDaylight()} returns {@code false}. */
    private void goNight() {
        // dawn 3 hours from now, dusk 3 hours ago → well past the nautical buffer
        LocalDateTime dawn = LocalDateTime.now(ZoneId.of("UTC")).plusHours(3);
        LocalDateTime dusk = LocalDateTime.now(ZoneId.of("UTC")).minusHours(3);
        when(solarCalculator.civilDawn(anyDouble(), anyDouble(), any(), any())).thenReturn(dawn);
        when(solarCalculator.civilDusk(anyDouble(), anyDouble(), any(), any())).thenReturn(dusk);
    }

    /** Configures the solar calculator so {@code isDaylight()} returns {@code true}. */
    private void goDay() {
        // dawn 6 hours ago, dusk 6 hours from now → clearly daytime
        LocalDateTime dawn = LocalDateTime.now(ZoneId.of("UTC")).minusHours(6);
        LocalDateTime dusk = LocalDateTime.now(ZoneId.of("UTC")).plusHours(6);
        when(solarCalculator.civilDawn(anyDouble(), anyDouble(), any(), any())).thenReturn(dawn);
        when(solarCalculator.civilDusk(anyDouble(), anyDouble(), any(), any())).thenReturn(dusk);
    }
}
