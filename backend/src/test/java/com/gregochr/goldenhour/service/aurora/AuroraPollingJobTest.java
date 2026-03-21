package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.AuroraWatchClient;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraStatus;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.solarutils.SolarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraPollingJob} logic (excluding the {@code @Scheduled} trigger).
 */
@ExtendWith(MockitoExtension.class)
class AuroraPollingJobTest {

    @Mock private AuroraWatchClient auroraWatchClient;
    @Mock private AuroraStateCache stateCache;
    @Mock private AuroraScorer scorer;
    @Mock private AuroraTransectFetcher transectFetcher;
    @Mock private LocationRepository locationRepository;
    @Mock private SolarCalculator solarCalculator;

    private AuroraPollingJob job;
    private AuroraProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuroraProperties();
        properties.setEnabled(true);
        job = new AuroraPollingJob(
                auroraWatchClient, stateCache, scorer, transectFetcher,
                locationRepository, properties, solarCalculator);
    }

    // -------------------------------------------------------------------------
    // Daylight skip
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("executePoll skips when it is daylight (civil dawn recent, civil dusk upcoming)")
    void executePoll_daylight_skips() {
        goDay();

        job.executePoll();

        verify(auroraWatchClient, never()).fetchStatus();
    }

    @Test
    @DisplayName("executePoll proceeds when it is properly dark (civil dusk several hours ago)")
    void executePoll_darkness_proceeds() {
        goNight();
        when(auroraWatchClient.fetchStatus()).thenReturn(null);

        job.executePoll();

        verify(auroraWatchClient, times(1)).fetchStatus();
    }

    // -------------------------------------------------------------------------
    // Null status handling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("executePoll does nothing when status is null (first-call failure)")
    void executePoll_nullStatus_doesNothing() {
        goNight();
        when(auroraWatchClient.fetchStatus()).thenReturn(null);

        job.executePoll();

        verify(stateCache, never()).evaluate(any());
    }

    // -------------------------------------------------------------------------
    // Action routing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("NOTIFY action triggers location scoring and cache update")
    void executePoll_notify_scoresAndCaches() {
        goNight();
        stubStatus(AlertLevel.AMBER);
        when(stateCache.evaluate(AlertLevel.AMBER))
                .thenReturn(new AuroraStateCache.Evaluation(
                        AuroraStateCache.Action.NOTIFY, AlertLevel.AMBER, null));

        var candidate = locationWithBortle(3);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(candidate));
        when(transectFetcher.fetchTransectCloud(any()))
                .thenReturn(Map.of("Test", 20));
        var scores = List.of(new AuroraForecastScore(
                candidate, 4, AlertLevel.AMBER, 20, "★★★★ Good", "detail"));
        when(scorer.score(any(), any(), any())).thenReturn(scores);

        job.executePoll();

        verify(scorer, times(1)).score(any(), any(), any());
        verify(stateCache, times(1)).updateScores(scores);
    }

    @Test
    @DisplayName("NOTIFY with no eligible locations updates cache with empty list")
    void executePoll_notify_noEligibleLocations() {
        goNight();
        stubStatus(AlertLevel.AMBER);
        when(stateCache.evaluate(AlertLevel.AMBER))
                .thenReturn(new AuroraStateCache.Evaluation(
                        AuroraStateCache.Action.NOTIFY, AlertLevel.AMBER, null));
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of());

        job.executePoll();

        verify(scorer, never()).score(any(), any(), any());
        verify(stateCache, times(1)).updateScores(List.of());
    }

    @Test
    @DisplayName("SUPPRESS action does not trigger scoring")
    void executePoll_suppress_noScoring() {
        goNight();
        stubStatus(AlertLevel.AMBER);
        when(stateCache.evaluate(AlertLevel.AMBER))
                .thenReturn(new AuroraStateCache.Evaluation(
                        AuroraStateCache.Action.SUPPRESS, AlertLevel.AMBER, null));

        job.executePoll();

        verify(scorer, never()).score(any(), any(), any());
        verify(stateCache, never()).updateScores(any());
    }

    @Test
    @DisplayName("CLEAR action does not trigger scoring (state machine handles clearing)")
    void executePoll_clear_noScoring() {
        goNight();
        stubStatus(AlertLevel.GREEN);
        when(stateCache.evaluate(AlertLevel.GREEN))
                .thenReturn(new AuroraStateCache.Evaluation(
                        AuroraStateCache.Action.CLEAR, null, AlertLevel.AMBER));

        job.executePoll();

        verify(scorer, never()).score(any(), any(), any());
        verify(stateCache, never()).updateScores(any());
    }

    @Test
    @DisplayName("NONE action does nothing")
    void executePoll_none_doesNothing() {
        goNight();
        stubStatus(AlertLevel.GREEN);
        when(stateCache.evaluate(AlertLevel.GREEN))
                .thenReturn(new AuroraStateCache.Evaluation(
                        AuroraStateCache.Action.NONE, null, null));

        job.executePoll();

        verify(scorer, never()).score(any(), any(), any());
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

    private void stubStatus(AlertLevel level) {
        var status = new AuroraStatus(level, ZonedDateTime.now(),
                "Test Station", ZonedDateTime.now().plusMinutes(10));
        when(auroraWatchClient.fetchStatus()).thenReturn(status);
    }

    private LocationEntity locationWithBortle(int bortleClass) {
        return LocationEntity.builder()
                .id(1L).name("Test").lat(54.78).lon(-1.58)
                .enabled(true).bortleClass(bortleClass).build();
    }
}
