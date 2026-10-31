package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.EnvironmentAgencyGaugeClient;
import com.gregochr.goldenhour.client.EnvironmentAgencyGaugeClient.GaugeReading;
import com.gregochr.goldenhour.config.TideGaugeProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideGaugeCheckEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TideGaugeCheckRepository;
import com.gregochr.goldenhour.repository.TideHighWater;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TideGaugeCheckService}: recording, the flag rule and alert-on-new-flag, skips
 * and failure isolation. No real Environment Agency call is made.
 */
@ExtendWith(MockitoExtension.class)
class TideGaugeCheckServiceTest {

    private static final String MEASURE = "E70939-level-tidal_level-Mean-15_min-mAOD";
    private static final String NAME = "Tynemouth";
    private static final Long LOCATION_ID = 7L;
    private static final Instant START = Instant.parse("2026-10-24T00:00:00Z");
    private static final Instant NOW = START.plus(Duration.ofDays(7));
    private static final double PERIOD_MINUTES = 12.42 * 60;
    private static final double FIRST_PEAK_MINUTE = 300;

    @Mock
    private EnvironmentAgencyGaugeClient gaugeClient;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private TideExtremeRepository tideExtremeRepository;
    @Mock
    private TideGaugeCheckRepository checkRepository;
    @Mock
    private AdminAlertService adminAlertService;

    private TideGaugeProperties properties;
    private TideGaugeCheckService service;

    @BeforeEach
    void setUp() {
        properties = new TideGaugeProperties();
        properties.setMeasures(List.of(measure(MEASURE, NAME)));
        service = new TideGaugeCheckService(gaugeClient, properties, locationRepository,
                tideExtremeRepository, checkRepository, adminAlertService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TideGaugeProperties.Measure measure(String id, String name) {
        TideGaugeProperties.Measure m = new TideGaugeProperties.Measure();
        m.setMeasureId(id);
        m.setLocationName(name);
        return m;
    }

    private static List<GaugeReading> sevenDaysOfReadings() {
        List<GaugeReading> readings = new ArrayList<>();
        for (int m = 0; m <= 7 * 24 * 60; m += 15) {
            double h = 3.0 + 2.0 * Math.cos(2 * Math.PI * (m - FIRST_PEAK_MINUTE) / PERIOD_MINUTES);
            readings.add(new GaugeReading(START.plus(Duration.ofMinutes(m)), h));
        }
        return readings;
    }

    /** Stored highs at every true peak, each {@code offsetMinutes} late. */
    private static List<TideHighWater> predictedHighs(double offsetMinutes) {
        List<TideHighWater> highs = new ArrayList<>();
        for (int n = -1; n < 15; n++) {
            long minute = Math.round(FIRST_PEAK_MINUTE + n * PERIOD_MINUTES + offsetMinutes);
            highs.add(new TideHighWater(LOCATION_ID,
                    LocalDateTime.ofInstant(START.plus(Duration.ofMinutes(minute)), ZoneOffset.UTC),
                    BigDecimal.valueOf(5.1)));
        }
        return highs;
    }

    private static TideGaugeCheckEntity prior(double median, boolean flagged) {
        return TideGaugeCheckEntity.builder().gaugeMeasureId(MEASURE).locationId(LOCATION_ID)
                .medianTimingErrorMinutes(median).flagged(flagged).build();
    }

    private void stubHappyPath(double offsetMinutes, List<TideGaugeCheckEntity> priors) {
        stubHappyPath(predictedHighs(offsetMinutes), priors);
    }

    private void stubHappyPath(List<TideHighWater> predicted, List<TideGaugeCheckEntity> priors) {
        when(locationRepository.findByName(NAME))
                .thenReturn(Optional.of(LocationEntity.builder().id(LOCATION_ID).name(NAME).build()));
        when(gaugeClient.fetchReadings(MEASURE, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 31)))
                .thenReturn(sevenDaysOfReadings());
        when(tideExtremeRepository.findHighWatersInWindow(eq(List.of(LOCATION_ID)), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(predicted);
        if (priors == null) {
            return;
        }
        when(checkRepository.findByLocationIdAndGaugeMeasureIdOrderByRunAtDesc(
                eq(LOCATION_ID), eq(MEASURE), eq(PageRequest.of(0, 4)))).thenReturn(priors);
        when(checkRepository.save(any(TideGaugeCheckEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static List<TideGaugeCheckEntity> priors(int count, double median, boolean newestFlagged) {
        List<TideGaugeCheckEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(prior(median, i == 0 && newestFlagged));
        }
        return list;
    }

    @Test
    @DisplayName("Records the median timing and height error of the paired high waters, unflagged with no history")
    void recordsRowWithMedians() {
        stubHappyPath(8.0, List.of());

        List<TideGaugeCheckEntity> recorded = service.runChecks();

        assertThat(recorded).hasSize(1);
        TideGaugeCheckEntity row = recorded.get(0);
        assertThat(row.getGaugeMeasureId()).isEqualTo(MEASURE);
        assertThat(row.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(row.getPairedHighs()).isGreaterThanOrEqualTo(8);
        assertThat(row.getMedianTimingErrorMinutes()).isCloseTo(8.0, within(0.5));
        assertThat(row.getMedianHeightErrorMetres()).isCloseTo(0.1, within(0.02));
        assertThat(row.getRunAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(row.isFlagged()).isFalse();
        verify(adminAlertService, never()).sendTideGaugeDriftAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("With only three earlier runs there is no flag however large the move")
    void threePriors_neverFlagged() {
        stubHappyPath(60.0, priors(3, 5.0, false));

        List<TideGaugeCheckEntity> recorded = service.runChecks();

        assertThat(recorded.get(0).isFlagged()).isFalse();
        verify(adminAlertService, never()).sendTideGaugeDriftAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("With four earlier runs a move above the threshold flags and alerts once, with attribution")
    void fourPriors_moveAboveThreshold_flagsAndAlerts() {
        stubHappyPath(16.0, priors(4, 5.0, false));

        List<TideGaugeCheckEntity> recorded = service.runChecks();

        assertThat(recorded.get(0).isFlagged()).isTrue();
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(adminAlertService).sendTideGaugeDriftAlert(eq(NAME), detail.capture());
        assertThat(detail.getValue()).contains(TideGaugeCheckService.OGL_ATTRIBUTION).contains(MEASURE);
    }

    @Test
    @DisplayName("With four earlier runs a move below the threshold does not flag")
    void fourPriors_moveBelowThreshold_notFlagged() {
        stubHappyPath(14.0, priors(4, 5.0, false));

        List<TideGaugeCheckEntity> recorded = service.runChecks();

        assertThat(recorded.get(0).isFlagged()).isFalse();
        verify(adminAlertService, never()).sendTideGaugeDriftAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("A gauge already flagged on its previous run is recorded flagged but not alerted again")
    void alreadyFlagged_noRepeatAlert() {
        stubHappyPath(16.0, priors(4, 5.0, true));

        List<TideGaugeCheckEntity> recorded = service.runChecks();

        assertThat(recorded.get(0).isFlagged()).isTrue();
        verify(adminAlertService, never()).sendTideGaugeDriftAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("trailing-runs of 0 never flags, records normally and does not fail on an empty history")
    void zeroTrailingRuns_neverFlags() {
        properties.setTrailingRuns(0);
        when(locationRepository.findByName(NAME))
                .thenReturn(Optional.of(LocationEntity.builder().id(LOCATION_ID).name(NAME).build()));
        when(gaugeClient.fetchReadings(MEASURE, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 31)))
                .thenReturn(sevenDaysOfReadings());
        when(tideExtremeRepository.findHighWatersInWindow(eq(List.of(LOCATION_ID)), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(predictedHighs(60.0));
        when(checkRepository.findByLocationIdAndGaugeMeasureIdOrderByRunAtDesc(
                eq(LOCATION_ID), eq(MEASURE), eq(PageRequest.of(0, 1)))).thenReturn(List.of());
        when(checkRepository.save(any(TideGaugeCheckEntity.class))).thenAnswer(i -> i.getArgument(0));

        List<TideGaugeCheckEntity> recorded = service.runChecks();

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).isFlagged()).isFalse();
        verify(adminAlertService, never()).sendTideGaugeDriftAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("The flag rule is strictly greater than the threshold, in either direction")
    void isFlagged_boundaries() {
        List<Double> priors = List.of(5.0, 5.0, 5.0, 5.0);

        assertThat(TideGaugeCheckService.isFlagged(15.0, priors, 4, 10.0)).isFalse();
        assertThat(TideGaugeCheckService.isFlagged(15.01, priors, 4, 10.0)).isTrue();
        assertThat(TideGaugeCheckService.isFlagged(-5.0, priors, 4, 10.0)).isFalse();
        assertThat(TideGaugeCheckService.isFlagged(-5.01, priors, 4, 10.0)).isTrue();
        assertThat(TideGaugeCheckService.isFlagged(100.0, List.of(5.0, 5.0, 5.0), 4, 10.0)).isFalse();
    }

    @Test
    @DisplayName("The baseline is the median of the previous medians, so one outlier does not move it")
    void isFlagged_baselineIsMedian() {
        // Median of {5, 5, 5, 40} is 5; a mean baseline would be 13.75 and would not flag 16.
        assertThat(TideGaugeCheckService.isFlagged(16.0, List.of(5.0, 5.0, 5.0, 40.0), 4, 10.0)).isTrue();
    }

    @Test
    @DisplayName("Fewer paired high waters than the minimum records nothing")
    void tooFewPairs_skipsPair() {
        stubHappyPath(predictedHighs(8.0).subList(0, 6), null);

        List<TideGaugeCheckEntity> recorded = service.runChecks();

        assertThat(recorded).isEmpty();
        verify(checkRepository, never()).save(any(TideGaugeCheckEntity.class));
    }

    @Test
    @DisplayName("An unknown location name is skipped without calling the Environment Agency")
    void unknownLocation_skipped() {
        when(locationRepository.findByName(NAME)).thenReturn(Optional.empty());

        assertThat(service.runChecks()).isEmpty();
        verifyNoInteractions(gaugeClient);
    }

    @Test
    @DisplayName("An Environment Agency failure for one gauge is logged and the next gauge still runs")
    void clientFailure_isolatedPerGauge() {
        properties.setMeasures(List.of(measure("broken", "Blyth Beach"), measure(MEASURE, NAME)));
        when(locationRepository.findByName("Blyth Beach"))
                .thenReturn(Optional.of(LocationEntity.builder().id(9L).name("Blyth Beach").build()));
        when(gaugeClient.fetchReadings(eq("broken"), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("EA down"));
        stubHappyPath(8.0, List.of());

        List<TideGaugeCheckEntity> recorded = service.runChecks();

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getGaugeMeasureId()).isEqualTo(MEASURE);
    }

    @Test
    @DisplayName("An empty readings response skips the gauge")
    void noReadings_skipped() {
        when(locationRepository.findByName(NAME))
                .thenReturn(Optional.of(LocationEntity.builder().id(LOCATION_ID).name(NAME).build()));
        when(gaugeClient.fetchReadings(eq(MEASURE), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        assertThat(service.runChecks()).isEmpty();
        verify(checkRepository, never()).save(any(TideGaugeCheckEntity.class));
    }

    @Test
    @DisplayName("Disabled does nothing")
    void disabled_doesNothing() {
        properties.setEnabled(false);

        assertThat(service.runChecks()).isEmpty();
        verifyNoInteractions(gaugeClient, locationRepository);
    }
}
