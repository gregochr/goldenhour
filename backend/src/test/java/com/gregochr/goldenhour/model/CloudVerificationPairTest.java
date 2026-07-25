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
                30, 80, 60, 40, 55, 40, true, 70, 120, 240, 250).gapError()).isEqualTo(-50);
        // Observed missing — a zero here would look like a perfect forecast.
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, null, 60, 40, 55, 40, true, 70, 120, 240, 250).gapError()).isNull();
        // Forecast missing (triaged before directional data was assembled).
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                null, 80, 60, 40, 55, 40, true, 70, 120, 240, 250).gapError()).isNull();
    }

    @Test
    @DisplayName("canvasError compares the stronger layer, and is null if either side is partial")
    void canvasError_usesStrongerLayer() {
        // Forecast max(60,40)=60 vs observed max(55,40)=55.
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, 55, 40, true, 70, 120, 240, 250).canvasError()).isEqualTo(5);
        // High layer dominates on the observed side: max(10,90)=90.
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, 10, 90, true, 70, 120, 240, 250).canvasError()).isEqualTo(-30);
        // A canvas is only known when BOTH layers are — one layer alone could understate it.
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, null, 55, 40, true, 70, 120, 240, 250).canvasError()).isNull();
        assertThat(new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, null, 40, true, 70, 120, 240, 250).canvasError()).isNull();
    }

    private CloudVerificationPair pair(Boolean building, Integer upwindCurrent,
            Integer windDirection, Integer azimuthDeg) {
        return new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, 55, 40, building, upwindCurrent, 120, windDirection, azimuthDeg);
    }

    private CloudVerificationPair withDistance(Integer distanceKm) {
        return new CloudVerificationPair("Durham", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, 55, 40, true, 70, distanceKm, 240, 250);
    }
}
