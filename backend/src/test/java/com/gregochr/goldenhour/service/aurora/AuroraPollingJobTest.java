package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
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

    private AuroraPollingJob job;
    private AuroraProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuroraProperties();
        properties.setEnabled(true);
        job = new AuroraPollingJob(orchestrator, properties, solarCalculator);
    }

    // -------------------------------------------------------------------------
    // Daylight skip
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("executePoll skips orchestrator when it is daylight")
    void executePoll_daylight_skipsOrchestrator() {
        goDay();

        job.executePoll();

        verify(orchestrator, never()).run();
    }

    @Test
    @DisplayName("executePoll invokes orchestrator when it is dark")
    void executePoll_darkness_invokesOrchestrator() {
        goNight();
        when(orchestrator.run()).thenReturn(AuroraStateCache.Action.NONE);

        job.executePoll();

        verify(orchestrator, times(1)).run();
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
