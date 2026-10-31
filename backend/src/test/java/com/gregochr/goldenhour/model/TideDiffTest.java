package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TideDiff}: pairing by type within three hours and the change thresholds.
 */
class TideDiffTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 9, 6, 0);

    private static TideExtremeEntity extreme(TideExtremeType type, LocalDateTime at, String height) {
        return TideExtremeEntity.builder().type(type).eventTime(at).heightMetres(new BigDecimal(height)).build();
    }

    @Test
    @DisplayName("pairs by type within three hours and reports the largest lag and height delta")
    void pairsByTypeAndMeasuresMaxima() {
        TideDiff diff = TideDiff.compute(
                List.of(extreme(TideExtremeType.HIGH, T0, "5.000"),
                        extreme(TideExtremeType.LOW, T0.plusHours(6), "1.000")),
                List.of(extreme(TideExtremeType.HIGH, T0.plusMinutes(10), "5.020"),
                        extreme(TideExtremeType.LOW, T0.plusHours(6).minusMinutes(30), "1.040")));

        assertThat(diff.pairs()).isEqualTo(2);
        assertThat(diff.maxLagMinutes()).isEqualTo(30.0);
        assertThat(diff.maxHeightMetres()).isEqualTo(0.04);
        assertThat(diff.changed()).isTrue();
    }

    @Test
    @DisplayName("a HIGH is never paired with a LOW, even when they are close")
    void typesAreNotMixed() {
        TideDiff diff = TideDiff.compute(
                List.of(extreme(TideExtremeType.HIGH, T0, "5.000")),
                List.of(extreme(TideExtremeType.LOW, T0.plusMinutes(1), "1.000")));

        assertThat(diff.pairs()).isZero();
        assertThat(diff.changed()).isFalse();
    }

    @Test
    @DisplayName("exactly three hours pairs; one second more does not")
    void pairingWindowBoundary() {
        List<TideExtremeEntity> stored = List.of(extreme(TideExtremeType.HIGH, T0, "5.000"));

        assertThat(TideDiff.compute(stored,
                List.of(extreme(TideExtremeType.HIGH, T0.plusHours(3), "5.000"))).pairs()).isEqualTo(1);
        assertThat(TideDiff.compute(stored,
                List.of(extreme(TideExtremeType.HIGH, T0.plusHours(3).plusSeconds(1), "5.000"))).pairs())
                .isZero();
    }

    @Test
    @DisplayName("a new extreme is used once: two stored HIGHs do not share one new HIGH")
    void eachNewExtremeIsUsedOnce() {
        TideDiff diff = TideDiff.compute(
                List.of(extreme(TideExtremeType.HIGH, T0, "5.000"),
                        extreme(TideExtremeType.HIGH, T0.plusHours(1), "5.000")),
                List.of(extreme(TideExtremeType.HIGH, T0.plusMinutes(30), "5.000")));

        assertThat(diff.pairs()).isEqualTo(1);
    }

    @Test
    @DisplayName("thresholds are strict: 5 minutes and 0.05 m are not changes, a hair over is")
    void thresholds() {
        assertThat(TideDiff.exceeds(5.0, 0.05)).isFalse();
        assertThat(TideDiff.exceeds(5.01, 0.0)).isTrue();
        assertThat(TideDiff.exceeds(0.0, 0.051)).isTrue();
        assertThat(TideDiff.exceeds(null, null)).isFalse();
    }

    @Test
    @DisplayName("examples are the largest-lag changed pairs only, at most two")
    void examplesAreLimitedAndChangedOnly() {
        TideDiff diff = TideDiff.compute(
                List.of(extreme(TideExtremeType.HIGH, T0, "5.000"),
                        extreme(TideExtremeType.HIGH, T0.plusHours(12), "5.000"),
                        extreme(TideExtremeType.HIGH, T0.plusHours(24), "5.000"),
                        extreme(TideExtremeType.HIGH, T0.plusHours(36), "5.000")),
                List.of(extreme(TideExtremeType.HIGH, T0.plusMinutes(20), "5.000"),
                        extreme(TideExtremeType.HIGH, T0.plusHours(12).plusMinutes(50), "5.000"),
                        extreme(TideExtremeType.HIGH, T0.plusHours(24).plusMinutes(35), "5.000"),
                        extreme(TideExtremeType.HIGH, T0.plusHours(36).plusMinutes(2), "5.000")));

        assertThat(diff.examples()).extracting(TideDiff.Pair::afterTime)
                .containsExactly(T0.plusHours(12).plusMinutes(50), T0.plusHours(24).plusMinutes(35));
    }
}
