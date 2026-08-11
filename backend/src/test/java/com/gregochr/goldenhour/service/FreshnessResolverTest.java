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
        return resolver(settled, transitional, unsettled, floor, 2, 8);
    }

    private FreshnessResolver resolver(int settled, int transitional, int unsettled, int floor,
            int t0, int t1) {
        FreshnessProperties props = new FreshnessProperties();
        props.setSettledHours(settled);
        props.setTransitionalHours(transitional);
        props.setUnsettledHours(unsettled);
        props.setSafetyFloorHours(floor);
        props.getHorizon().setT0Hours(t0);
        props.getHorizon().setT1Hours(t1);
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

    @Nested
    @DisplayName("Horizon caps")
    class HorizonCaps {

        private final FreshnessResolver resolver = resolver(36, 12, 4, 2);

        @Test
        @DisplayName("T+0 SETTLED takes the 2h cap, not the 36h stability threshold")
        void todaySettledTakesCap() {
            assertThat(resolver.maxAgeFor(0, ForecastStability.SETTLED))
                    .isEqualTo(Duration.ofHours(2));
        }

        @Test
        @DisplayName("T+1 SETTLED takes the 8h cap, not the 36h stability threshold")
        void tomorrowSettledTakesCap() {
            assertThat(resolver.maxAgeFor(1, ForecastStability.SETTLED))
                    .isEqualTo(Duration.ofHours(8));
        }

        @Test
        @DisplayName("T+1 UNSETTLED keeps stability's 4h — tighter than the 8h cap")
        void tomorrowUnsettledKeepsTighterStability() {
            assertThat(resolver.maxAgeFor(1, ForecastStability.UNSETTLED))
                    .isEqualTo(Duration.ofHours(4));
        }

        @Test
        @DisplayName("T+2 is uncapped — stability alone governs")
        void twoDaysAheadIsUncapped() {
            assertThat(resolver.maxAgeFor(2, ForecastStability.SETTLED))
                    .isEqualTo(Duration.ofHours(36));
        }

        @Test
        @DisplayName("T+7 is uncapped — stability alone governs")
        void sevenDaysAheadIsUncapped() {
            assertThat(resolver.maxAgeFor(7, ForecastStability.TRANSITIONAL))
                    .isEqualTo(Duration.ofHours(12));
        }

        @Test
        @DisplayName("A negative horizon is treated as T+0 rather than falling through uncapped")
        void negativeHorizonTakesTheTodayCap() {
            // Past dates are filtered long before the gate, so this is defensive — but the
            // fall-through it prevents would be to the 36h threshold, i.e. the exact bug.
            assertThat(resolver.maxAgeFor(-1, ForecastStability.SETTLED))
                    .isEqualTo(Duration.ofHours(2));
        }
    }

    @Nested
    @DisplayName("Cap and floor are independent terms")
    class CapAndFloorAreIndependent {

        @Test
        @DisplayName("Floor above the cap wins: floor=4, T+0 cap=2 gives 4h")
        void floorAboveCapWins() {
            FreshnessResolver r = resolver(36, 12, 4, 4, 2, 8);
            assertThat(r.maxAgeFor(0, ForecastStability.SETTLED))
                    .isEqualTo(Duration.ofHours(4));
        }

        @Test
        @DisplayName("Floor below the cap does not bind: floor=1, T+0 cap=2 gives 2h")
        void floorBelowCapDoesNotBind() {
            FreshnessResolver r = resolver(36, 12, 4, 1, 2, 8);
            assertThat(r.maxAgeFor(0, ForecastStability.SETTLED))
                    .isEqualTo(Duration.ofHours(2));
        }

        @Test
        @DisplayName("Raising the T+0 cap to the stability threshold restores pre-cap behaviour "
                + "— the documented rollback")
        void rollbackByRaisingCap() {
            FreshnessResolver r = resolver(36, 12, 4, 2, 36, 36);
            assertThat(r.maxAgeFor(0, ForecastStability.SETTLED))
                    .isEqualTo(Duration.ofHours(36));
        }
    }

    @Nested
    @DisplayName("Monotonicity")
    class Monotonicity {

        /**
         * The safety property the rollout rests on: adding a horizon can only ever tighten.
         *
         * <p>This does NOT pin the safety floor — {@code max(min(base,cap),floor)} stays ≤
         * {@code max(base,floor)} even with the floor clamp deleted. The floor is pinned by
         * {@link SafetyFloor} and {@link CapAndFloorAreIndependent}.
         */
        @Test
        @DisplayName("No (horizon, stability) pair is ever staler than stability alone")
        void horizonNeverLoosensTheThreshold() {
            FreshnessResolver r = resolver(36, 12, 4, 2);
            for (int daysAhead = 0; daysAhead <= 7; daysAhead++) {
                for (ForecastStability stability : ForecastStability.values()) {
                    assertThat(r.maxAgeFor(daysAhead, stability))
                            .as("T+%d %s must not exceed the stability-only threshold",
                                    daysAhead, stability)
                            .isLessThanOrEqualTo(r.maxAgeFor(stability));
                }
            }
        }
    }
}
