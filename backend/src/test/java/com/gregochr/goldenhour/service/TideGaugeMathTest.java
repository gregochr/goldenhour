package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.EnvironmentAgencyGaugeClient.GaugeReading;
import com.gregochr.goldenhour.repository.TideHighWater;
import com.gregochr.goldenhour.service.TideGaugeMath.ObservedHigh;
import com.gregochr.goldenhour.service.TideGaugeMath.PairedHigh;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link TideGaugeMath}: peak refinement, local-maximum detection, pairing and medians.
 */
class TideGaugeMathTest {

    private static final Instant T0 = Instant.parse("2026-10-26T00:00:00Z");
    private static final Duration STEP = Duration.ofMinutes(15);
    private static final double TIDAL_PERIOD_HOURS = 12.42;

    /** A 15-minute series from T0 for {@code hours}, with height supplied by minutes-since-T0. */
    private static List<GaugeReading> series(int hours, java.util.function.DoubleUnaryOperator heightAtMinute) {
        List<GaugeReading> readings = new ArrayList<>();
        for (int m = 0; m <= hours * 60; m += 15) {
            readings.add(new GaugeReading(T0.plus(Duration.ofMinutes(m)), heightAtMinute.applyAsDouble(m)));
        }
        return readings;
    }

    private static java.util.function.DoubleUnaryOperator tide(double peakMinute) {
        double periodMinutes = TIDAL_PERIOD_HOURS * 60;
        return m -> 3.0 + 2.0 * Math.cos(2 * Math.PI * (m - peakMinute) / periodMinutes);
    }

    private static TideHighWater predicted(Instant time, double height) {
        return new TideHighWater(1L, LocalDateTime.ofInstant(time, ZoneOffset.UTC), BigDecimal.valueOf(height));
    }

    @Test
    @DisplayName("Five-point quadratic recovers the vertex of an exact parabola between samples")
    void refinePeak_exactParabola_recoversVertex() {
        // y = 5 - 0.001 (x - 4)^2 with x in minutes since T0+30min centre; vertex at +4 min, height 5.
        List<GaugeReading> five = new ArrayList<>();
        for (int k = -2; k <= 2; k++) {
            double x = k * 15.0;
            five.add(new GaugeReading(T0.plus(Duration.ofMinutes(30L + k * 15L)), 5.0 - 0.001 * (x - 4) * (x - 4)));
        }

        ObservedHigh peak = TideGaugeMath.refinePeak(five, 2, STEP);

        assertThat(Duration.between(T0.plus(Duration.ofMinutes(30)), peak.time()).toMillis() / 60000.0)
                .isCloseTo(4.0, within(0.001));
        assertThat(peak.height()).isCloseTo(5.0, within(1e-9));
    }

    @Test
    @DisplayName("A flat fit is degenerate and falls back to the raw maximum")
    void refinePeak_flat_fallsBackToRaw() {
        List<GaugeReading> five = new ArrayList<>();
        for (int k = -2; k <= 2; k++) {
            five.add(new GaugeReading(T0.plus(Duration.ofMinutes(30L + k * 15L)), 3.0));
        }

        ObservedHigh peak = TideGaugeMath.refinePeak(five, 2, STEP);

        assertThat(peak.time()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
        assertThat(peak.height()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("A convex fit (a trough) falls back to the raw reading")
    void refinePeak_convex_fallsBackToRaw() {
        List<GaugeReading> five = new ArrayList<>();
        for (int k = -2; k <= 2; k++) {
            five.add(new GaugeReading(T0.plus(Duration.ofMinutes(30L + k * 15L)), 1.0 + 0.01 * k * k));
        }

        ObservedHigh peak = TideGaugeMath.refinePeak(five, 2, STEP);

        assertThat(peak.height()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A vertex outside the fitted span falls back to the raw reading")
    void refinePeak_vertexOutsideSpan_fallsBackToRaw() {
        // Concave but still rising across the span: vertex far to the right of the last sample.
        List<GaugeReading> five = new ArrayList<>();
        for (int k = -2; k <= 2; k++) {
            five.add(new GaugeReading(T0.plus(Duration.ofMinutes(30L + k * 15L)), 1.0 + 0.5 * k - 0.001 * k * k));
        }

        ObservedHigh peak = TideGaugeMath.refinePeak(five, 2, STEP);

        assertThat(peak.time()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("Extraction finds each interior high water of a tidal curve, refined to within a minute")
    void extractHighWaters_findsInteriorPeaks() {
        // Peaks at 5h07m (307 min), 17h32m (1052 min) and 29h57m (1797 min) in a 36 h series.
        double period = TIDAL_PERIOD_HOURS * 60;
        List<GaugeReading> readings = series(36, tide(307));

        List<ObservedHigh> highs = TideGaugeMath.extractHighWaters(readings);

        assertThat(highs).hasSize(3);
        for (int i = 0; i < highs.size(); i++) {
            double expectedMinute = 307 + i * period;
            double actualMinute = Duration.between(T0, highs.get(i).time()).toMillis() / 60000.0;
            assertThat(actualMinute).isCloseTo(expectedMinute, within(1.0));
            assertThat(highs.get(i).height()).isCloseTo(5.0, within(0.01));
        }
    }

    @Test
    @DisplayName("A peak within two hours of the start of the data is not reported: its window is not covered")
    void extractHighWaters_peakTooCloseToEdge_isSkipped() {
        List<GaugeReading> readings = series(8, tide(60));

        assertThat(TideGaugeMath.extractHighWaters(readings)).isEmpty();
    }

    @Test
    @DisplayName("A gap inside the two-hour window disqualifies the peak")
    void extractHighWaters_gapInWindow_isSkipped() {
        List<GaugeReading> full = series(12, tide(360));
        List<GaugeReading> gapped = new ArrayList<>();
        for (GaugeReading r : full) {
            long minute = Duration.between(T0, r.time()).toMinutes();
            boolean inGap = minute >= 420 && minute <= 480;
            if (!inGap) {
                gapped.add(r);
            }
        }

        assertThat(TideGaugeMath.extractHighWaters(gapped)).isEmpty();
    }

    @Test
    @DisplayName("Readings that are not equally spaced around the peak disqualify it")
    void extractHighWaters_unequalSpacing_isSkipped() {
        List<GaugeReading> full = series(12, tide(360));
        List<GaugeReading> uneven = new ArrayList<>(full);
        // Remove one reading adjacent to the peak so the five points are not equally spaced.
        uneven.removeIf(r -> Duration.between(T0, r.time()).toMinutes() == 345);

        assertThat(TideGaugeMath.extractHighWaters(uneven)).isEmpty();
    }

    @Test
    @DisplayName("A flat-topped plateau yields a single high water")
    void extractHighWaters_plateau_yieldsOne() {
        List<GaugeReading> readings = series(12, m -> {
            double d = Math.abs(m - 360);
            return d <= 15 ? 5.0 : 5.0 - 0.0002 * (d - 15) * (d - 15);
        });

        assertThat(TideGaugeMath.extractHighWaters(readings)).hasSize(1);
    }

    @Test
    @DisplayName("Pairing matches the nearest prediction within two hours and reports predicted minus observed")
    void pair_matchesWithinTwoHours() {
        Instant observedTime = T0.plus(Duration.ofHours(6));
        List<ObservedHigh> observed = List.of(new ObservedHigh(observedTime, 4.80));
        List<TideHighWater> predicted = List.of(
                predicted(observedTime.plus(Duration.ofMinutes(12)), 4.95),
                predicted(observedTime.plus(Duration.ofMinutes(90)), 4.00));

        List<PairedHigh> pairs = TideGaugeMath.pair(observed, predicted);

        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).timingErrorMinutes()).isCloseTo(12.0, within(1e-9));
        assertThat(pairs.get(0).heightErrorMetres()).isCloseTo(0.15, within(1e-9));
    }

    @Test
    @DisplayName("A prediction more than two hours away does not pair; an early prediction gives a negative error")
    void pair_outsideLimit_noPair_andSign() {
        Instant observedTime = T0.plus(Duration.ofHours(6));
        List<ObservedHigh> observed = List.of(new ObservedHigh(observedTime, 4.8));

        assertThat(TideGaugeMath.pair(observed,
                List.of(predicted(observedTime.plus(Duration.ofMinutes(121)), 4.8)))).isEmpty();
        assertThat(TideGaugeMath.pair(observed,
                List.of(predicted(observedTime.minus(Duration.ofMinutes(20)), 4.8))).get(0).timingErrorMinutes())
                .isCloseTo(-20.0, within(1e-9));
    }

    @Test
    @DisplayName("A prediction is used for at most one measured high water")
    void pair_predictionUsedOnce() {
        Instant t = T0.plus(Duration.ofHours(6));
        List<ObservedHigh> observed = List.of(new ObservedHigh(t, 4.8), new ObservedHigh(t.plusSeconds(600), 4.8));

        assertThat(TideGaugeMath.pair(observed, List.of(predicted(t, 4.8)))).hasSize(1);
    }

    @Test
    @DisplayName("Median is the middle value for an odd count and the mean of the middle two for an even count")
    void median_oddAndEven() {
        assertThat(TideGaugeMath.median(List.of(5.0, 1.0, 3.0))).isEqualTo(3.0);
        assertThat(TideGaugeMath.median(List.of(4.0, 1.0, 3.0, 10.0))).isEqualTo(3.5);
        assertThat(TideGaugeMath.median(List.of(-2.0))).isEqualTo(-2.0);
    }
}
