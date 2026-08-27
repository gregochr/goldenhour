package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CloudVerificationPair}.
 *
 * <p>Focuses on the partial-data paths. Verification rows are deliberately nullable — an archive
 * fetch that found nothing still writes a row so it is not retried forever — so every derived
 * value has to cope with half a pair, and must report "unknown" rather than a plausible-looking
 * zero that would silently skew a bucket average.
 */
class CloudVerificationPairTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 10);

    @Test
    @DisplayName("vetoFired needs both triggers, not either")
    void vetoFired_requiresBothTriggers() {
        assertThat(pair(true, 70, 240, 250).vetoFired()).isTrue();
        // Building, but the upwind reading is below the 60% trigger.
        assertThat(pair(true, 59, 240, 250).vetoFired()).isFalse();
        // High upwind reading, but no building trend.
        assertThat(pair(false, 70, 240, 250).vetoFired()).isFalse();
        // Trend unknown, or upwind never sampled — cannot claim the veto fired.
        assertThat(pair(null, 70, 240, 250).vetoFired()).isFalse();
        assertThat(pair(true, null, 240, 250).vetoFired()).isFalse();
    }

    @Test
    @DisplayName("upwindCapped is false when the distance was never recorded")
    void upwindCapped_handlesMissingDistance() {
        assertThat(withDistance(200).upwindCapped()).isTrue();
        assertThat(withDistance(201).upwindCapped()).isTrue();
        assertThat(withDistance(199).upwindCapped()).isFalse();
        assertThat(withDistance(null).upwindCapped()).isFalse();
    }

    @Test
    @DisplayName("windSunAngle takes the short way round the compass")
    void windSunAngle_normalisesAcrossNorth() {
        // 350 vs 10 is 20 degrees apart, not 340.
        assertThat(pair(true, 70, 350, 10).windSunAngle()).isEqualTo(20);
        assertThat(pair(true, 70, 240, 250).windSunAngle()).isEqualTo(10);
        // Directly opposed.
        assertThat(pair(true, 70, 70, 250).windSunAngle()).isEqualTo(180);
    }

    @Test
    @DisplayName("windSunAngle is null when either bearing is unknown")
    void windSunAngle_missingBearing_returnsNull() {
        assertThat(pair(true, 70, null, 250).windSunAngle()).isNull();
        assertThat(pair(true, 70, 240, null).windSunAngle()).isNull();
    }

    @Test
    @DisplayName("gapError is null rather than zero when the archive had no reading")
    void gapError_missingReading_returnsNull() {
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, 55, 40, true, 70, 120, 240, 250,
                null, null, null, null, null, null).gapError()).isEqualTo(-50);
        // Observed missing — a zero here would look like a perfect forecast.
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, null, 60, 40, 55, 40, true, 70, 120, 240, 250,
                null, null, null, null, null, null).gapError()).isNull();
        // Forecast missing (triaged before directional data was assembled).
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                null, 80, 60, 40, 55, 40, true, 70, 120, 240, 250,
                null, null, null, null, null, null).gapError()).isNull();
    }

    @Test
    @DisplayName("canvasError compares the stronger layer, and is null if either side is partial")
    void canvasError_usesStrongerLayer() {
        // Forecast max(60,40)=60 vs observed max(55,40)=55.
        assertThat(withCanvas(55, 40).canvasError()).isEqualTo(5);
        // High layer dominates on the observed side: max(10,90)=90.
        assertThat(withCanvas(10, 90).canvasError()).isEqualTo(-30);
        // A canvas is only known when BOTH layers are — one layer alone could understate it.
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, null, 55, 40, true, 70, 120, 240, 250,
                null, null, null, null, null, null).canvasError()).isNull();
        assertThat(withCanvas(null, 40).canvasError()).isNull();
    }

    @Test
    @DisplayName("coneSpread reports the wall-with-gap structure a 3-point mean cannot carry")
    void coneSpread_measuresConeStructure() {
        // One bearing clear, another blocked — a mean of 60 would look like a uniform deck.
        assertThat(measurement(null, 0, 90, null).coneSpread()).isEqualTo(90);
        assertThat(measurement(null, 55, 65, null).coneSpread()).isEqualTo(10);
        // Rows verified before the measurement pass carry no extremes — unknown, not zero.
        assertThat(measurement(null, null, 90, null).coneSpread()).isNull();
        assertThat(measurement(null, 0, null, null).coneSpread()).isNull();
    }

    @Test
    @DisplayName("farDrop is positive when the corridor clears with distance")
    void farDrop_signedNearMinusFar() {
        // Observed gap low is 80 in the helper: a near strip over a clear far corridor.
        assertThat(measurement(null, null, null, 20).farDrop()).isEqualTo(60);
        // The reverse: near clear-ish relative to a blanketed far corridor.
        assertThat(measurement(null, null, null, 95).farDrop()).isEqualTo(-15);
        assertThat(measurement(null, null, null, null).farDrop()).isNull();
        // Near reading missing — no comparison to make.
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, null, 60, 40, 55, 40, true, 70, 120, 240, 250,
                null, null, null, 20, null, null).farDrop()).isNull();
    }

    @Test
    @DisplayName("farError verifies the forecast's own 226 km claim, null when either side is missing")
    void farError_signedForecastMinusObserved() {
        assertThat(measurement(50, null, null, 20).farError()).isEqualTo(30);
        assertThat(measurement(10, null, null, 40).farError()).isEqualTo(-30);
        assertThat(measurement(null, null, null, 20).farError()).isNull();
        assertThat(measurement(50, null, null, null).farError()).isNull();
    }

    @Test
    @DisplayName("highCanvasDominant is strict, so an empty sky does not count as a high canvas")
    void highCanvasDominant_strictComparison() {
        assertThat(withCanvas(10, 90).highCanvasDominant()).isTrue();
        assertThat(withCanvas(55, 40).highCanvasDominant()).isFalse();
        // Equal layers — including 0/0, the empty sky — are not high-dominant.
        assertThat(withCanvas(0, 0).highCanvasDominant()).isFalse();
        assertThat(withCanvas(null, 40).highCanvasDominant()).isNull();
        assertThat(withCanvas(55, null).highCanvasDominant()).isNull();
    }

    @Test
    @DisplayName("midCanvasDominant is strict, so an empty sky does not count as a mid canvas")
    void midCanvasDominant_strictComparison() {
        // Mid dominance is the cut the 226 km point measures directly rather than proxies.
        assertThat(withCanvas(55, 40).midCanvasDominant()).isTrue();
        assertThat(withCanvas(10, 90).midCanvasDominant()).isFalse();
        // Equal layers — including 0/0, the empty sky — are neither mid- nor high-dominant.
        assertThat(withCanvas(0, 0).midCanvasDominant()).isFalse();
        assertThat(withCanvas(0, 0).highCanvasDominant()).isFalse();
        // Half a canvas cannot settle dominance either way — unknown, not "not mid-dominant".
        assertThat(withCanvas(null, 40).midCanvasDominant()).isNull();
        assertThat(withCanvas(55, null).midCanvasDominant()).isNull();
    }

    private CloudVerificationPair pair(Boolean building, Integer upwindCurrent,
            Integer windDirection, Integer azimuthDeg) {
        return new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, 55, 40, building, upwindCurrent, 120, windDirection, azimuthDeg,
                null, null, null, null, null, null);
    }

    private CloudVerificationPair withDistance(Integer distanceKm) {
        return new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, 55, 40, true, 70, distanceKm, 240, 250,
                null, null, null, null, null, null);
    }

    private CloudVerificationPair withCanvas(Integer observedMid, Integer observedHigh) {
        return new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, observedMid, observedHigh, true, 70, 120, 240, 250,
                null, null, null, null, null, null);
    }

    private CloudVerificationPair measurement(Integer forecastFarLow, Integer observedGapLowMin,
            Integer observedGapLowMax, Integer observedFarLow) {
        return new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, 55, 40, true, 70, 120, 240, 250,
                forecastFarLow, observedGapLowMin, observedGapLowMax, observedFarLow, null, null);
    }
}
