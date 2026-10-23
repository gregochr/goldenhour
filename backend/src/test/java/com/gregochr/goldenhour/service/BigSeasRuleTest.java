package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link BigSeasRule} against the default thresholds (3.0 m, 60 deg, 8 s). */
class BigSeasRuleTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 12, 10, 8, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 12, 10, 11, 0);
    private static final BigSeasProperties PROPS = new BigSeasProperties();

    private static boolean qualifies(Double height, Integer dir, Double period, Integer faces) {
        return BigSeasRule.qualifies(height, dir, period, faces, START, END, PROPS);
    }

    @ParameterizedTest(name = "height {0} m -> {1}")
    @CsvSource({"2.99,false", "3.0,true", "3.01,true"})
    @DisplayName("wave height: 3.0 m qualifies, just under does not")
    void heightBoundary(double height, boolean expected) {
        assertThat(qualifies(height, 45, 10.0, 45)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "period {0} s -> {1}")
    @CsvSource({"7.99,false", "8.0,true", "8.01,true"})
    @DisplayName("wave period: 8.0 s qualifies, just under does not")
    void periodBoundary(double period, boolean expected) {
        assertThat(qualifies(4.0, 45, period, 45)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "dir {0} vs faces 45 -> {1}")
    @CsvSource({"105,true", "106,false", "345,true", "344,false", "45,true"})
    @DisplayName("onshore angle: 60 degrees either side qualifies, 61 does not")
    void angleBoundary(int dir, boolean expected) {
        assertThat(qualifies(4.0, dir, 10.0, 45)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the angle wraps through north: 350 against a structure facing 10 is 20 degrees, not 340")
    void angleWraps() {
        assertThat(BigSeasRule.angleOffDeg(350, 10)).isEqualTo(20);
        assertThat(BigSeasRule.angleOffDeg(10, 350)).isEqualTo(20);
        assertThat(qualifies(4.0, 350, 10.0, 10)).isTrue();
    }

    @Test
    @DisplayName("a wave direction of 360 is north: 0 degrees off a structure facing 0, 180 off one facing 180")
    void directionOf360IsNorth() {
        assertThat(BigSeasRule.angleOffDeg(360, 0)).isZero();
        assertThat(BigSeasRule.angleOffDeg(360, 180)).isEqualTo(180);
        assertThat(qualifies(4.0, 360, 10.0, 0)).isTrue();
        assertThat(qualifies(4.0, 360, 10.0, 180)).isFalse();
    }

    @Test
    @DisplayName("any null input, or an empty or inverted window, does not qualify")
    void nullsAndEmptyWindows() {
        assertThat(qualifies(null, 45, 10.0, 45)).isFalse();
        assertThat(qualifies(4.0, null, 10.0, 45)).isFalse();
        assertThat(qualifies(4.0, 45, null, 45)).isFalse();
        assertThat(qualifies(4.0, 45, 10.0, null)).isFalse();
        assertThat(BigSeasRule.qualifies(4.0, 45, 10.0, 45, null, END, PROPS)).isFalse();
        assertThat(BigSeasRule.qualifies(4.0, 45, 10.0, 45, START, null, PROPS)).isFalse();
        assertThat(BigSeasRule.qualifies(4.0, 45, 10.0, 45, START, START, PROPS)).isFalse();
        assertThat(BigSeasRule.qualifies(4.0, 45, 10.0, 45, END, START, PROPS)).isFalse();
        assertThat(qualifies(4.0, 45, 10.0, 45)).isTrue();
    }

    @Test
    @DisplayName("the thresholds come from the properties, so retuning needs no refetch")
    void thresholdsAreConfigurable() {
        BigSeasProperties strict = new BigSeasProperties();
        strict.setMinWaveHeightM(5.0);
        strict.setMaxOnshoreAngleDeg(20);

        assertThat(BigSeasRule.qualifies(4.0, 45, 10.0, 45, START, END, strict)).isFalse();
        assertThat(BigSeasRule.qualifies(5.0, 66, 10.0, 45, START, END, strict)).isFalse();
        assertThat(BigSeasRule.qualifies(5.0, 66, 10.0, 45, START, END, PROPS)).isTrue();
    }
}
