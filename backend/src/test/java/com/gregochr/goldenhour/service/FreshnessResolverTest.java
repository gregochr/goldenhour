package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.FreshnessProperties;
import com.gregochr.goldenhour.entity.ForecastStability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FreshnessResolver}.
 */
class FreshnessResolverTest {

    private FreshnessResolver resolver(int settled, int transitional, int unsettled, int floor) {
        FreshnessProperties props = new FreshnessProperties();
        props.setSettledHours(settled);
        props.setTransitionalHours(transitional);
        props.setUnsettledHours(unsettled);
        props.setSafetyFloorHours(floor);
        return new FreshnessResolver(props);
    }

    @Nested
    @DisplayName("maxAgeFor with default thresholds")
    class DefaultThresholds {

        private final FreshnessResolver resolver = resolver(36, 12, 4, 2);

        @Test
        @DisplayName("SETTLED returns configured settled hours")
        void settledReturnsConfiguredHours() {
            assertThat(resolver.maxAgeFor(ForecastStability.SETTLED))
                    .isEqualTo(Duration.ofHours(36));
        }

        @Test
        @DisplayName("TRANSITIONAL returns configured transitional hours")
        void transitionalReturnsConfiguredHours() {
            assertThat(resolver.maxAgeFor(ForecastStability.TRANSITIONAL))
                    .isEqualTo(Duration.ofHours(12));
        }

        @Test
        @DisplayName("UNSETTLED returns configured unsettled hours")
        void unsettledReturnsConfiguredHours() {
            assertThat(resolver.maxAgeFor(ForecastStability.UNSETTLED))
                    .isEqualTo(Duration.ofHours(4));
        }
    }

    @Nested
    @DisplayName("Safety floor enforcement")
    class SafetyFloor {

        @Test
        @DisplayName("Floor applied when stability threshold is below floor")
        void floorAppliedWhenBelowFloor() {
            FreshnessResolver r = resolver(36, 12, 1, 2);
            assertThat(r.maxAgeFor(ForecastStability.UNSETTLED))
                    .isEqualTo(Duration.ofHours(2));
        }

        @Test
        @DisplayName("Floor does NOT override when stability threshold exceeds floor")
        void floorNotAppliedWhenAboveFloor() {
            FreshnessResolver r = resolver(36, 12, 4, 2);
            assertThat(r.maxAgeFor(ForecastStability.SETTLED))
                    .isEqualTo(Duration.ofHours(36));
        }

        @Test
        @DisplayName("Floor applied to transitional when transitional is below floor")
        void floorAppliedToTransitional() {
            FreshnessResolver r = resolver(36, 1, 1, 3);
            assertThat(r.maxAgeFor(ForecastStability.TRANSITIONAL))
                    .isEqualTo(Duration.ofHours(3));
        }

        @Test
        @DisplayName("Exact equality with floor returns the floor")
        void exactEqualityWithFloor() {
            FreshnessResolver r = resolver(36, 12, 4, 4);
            assertThat(r.maxAgeFor(ForecastStability.UNSETTLED))
                    .isEqualTo(Duration.ofHours(4));
        }
    }
}
