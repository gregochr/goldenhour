package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PressureTrend}.
 */
class PressureTrendTest {

    @Nested
    @DisplayName("labelFromTendency boundary values")
    class LabelFromTendencyBoundaryTests {

        @Test
        @DisplayName("exactly -3.0 (RAPID_FALL_THRESHOLD) → FALLING_RAPIDLY")
        void exactlyRapidFallThreshold() {
            assertThat(PressureTrend.labelFromTendency(-3.0))
                    .isEqualTo("FALLING_RAPIDLY");
        }

        @Test
        @DisplayName("exactly -1.5 (MODERATE_FALL_THRESHOLD) → FALLING")
        void exactlyModerateFallThreshold() {
            assertThat(PressureTrend.labelFromTendency(-1.5))
                    .isEqualTo("FALLING");
        }

        @Test
        @DisplayName("exactly +1.0 (RISE_THRESHOLD) → RISING")
        void exactlyRiseThreshold() {
            assertThat(PressureTrend.labelFromTendency(1.0))
                    .isEqualTo("RISING");
        }

        @Test
        @DisplayName("-1.4999 (just above MODERATE_FALL) → STEADY")
        void justAboveModerateFall() {
            assertThat(PressureTrend.labelFromTendency(-1.4999))
                    .isEqualTo("STEADY");
        }

        @Test
        @DisplayName("+0.999 (just below RISE) → STEADY")
        void justBelowRise() {
            assertThat(PressureTrend.labelFromTendency(0.999))
                    .isEqualTo("STEADY");
        }

        @Test
        @DisplayName("-2.999 (between MODERATE_FALL and RAPID_FALL) → FALLING")
        void betweenModerateAndRapidFall() {
            assertThat(PressureTrend.labelFromTendency(-2.999))
                    .isEqualTo("FALLING");
        }

        @Test
        @DisplayName("zero → STEADY")
        void zero() {
            assertThat(PressureTrend.labelFromTendency(0.0))
                    .isEqualTo("STEADY");
        }
    }

    @Nested
    @DisplayName("Compact constructor immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("mutating input list does not affect record contents")
        void defensiveCopy() {
            List<Double> input = new ArrayList<>(List.of(1010.0, 1010.5, 1011.0));
            PressureTrend pt = new PressureTrend(input, 1.0, "RISING");

            input.add(9999.0);

            assertThat(pt.pressureHpa()).hasSize(3);
            assertThat(pt.pressureHpa()).doesNotContain(9999.0);
        }

        @Test
        @DisplayName("returned list is unmodifiable")
        void unmodifiable() {
            PressureTrend pt = new PressureTrend(
                    List.of(1010.0, 1010.5), 0.5, "STEADY");

            assertThatThrownBy(() -> pt.pressureHpa().add(9999.0))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
