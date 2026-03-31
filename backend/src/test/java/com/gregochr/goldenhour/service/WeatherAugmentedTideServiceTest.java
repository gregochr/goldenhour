package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.CoastalParameters;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.service.WeatherAugmentedTideService.AugmentedTideResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link WeatherAugmentedTideService}.
 */
class WeatherAugmentedTideServiceTest {

    private WeatherAugmentedTideService service;

    private static final CoastalParameters CRASTER =
            new CoastalParameters(80, 250_000, 30, true);

    @BeforeEach
    void setUp() {
        service = new WeatherAugmentedTideService(new StormSurgeService());
    }

    @Nested
    @DisplayName("augment()")
    class AugmentTests {

        @Test
        @DisplayName("Adjusted range includes surge on top of astronomical range")
        void adjustedRangeAddsSurge() {
            AugmentedTideResult result = service.augment(
                    985, 18, 80, CRASTER, "REGULAR_TIDE", 4.5, 5.0);

            assertThat(result.adjustedRangeMetres()).isGreaterThan(4.5);
            assertThat(result.adjustedRangeMetres())
                    .isCloseTo(4.5 + result.surgeBreakdown().totalSurgeMetres(), within(0.001));
        }

        @Test
        @DisplayName("Adjusted high tide includes surge on top of predicted")
        void adjustedHighTideAddsSurge() {
            AugmentedTideResult result = service.augment(
                    985, 18, 80, CRASTER, "REGULAR_TIDE", 4.5, 5.0);

            assertThat(result.adjustedHighTideMetres()).isGreaterThan(5.0);
            assertThat(result.adjustedHighTideMetres())
                    .isCloseTo(5.0 + result.surgeBreakdown().totalSurgeMetres(), within(0.001));
        }

        @Test
        @DisplayName("Non-tidal location produces zero adjustment")
        void nonTidal() {
            AugmentedTideResult result = service.augment(
                    960, 25, 80, CoastalParameters.NON_TIDAL, "REGULAR_TIDE", 4.0, 5.0);

            assertThat(result.adjustedRangeMetres()).isEqualTo(4.0);
            assertThat(result.adjustedHighTideMetres()).isEqualTo(5.0);
            assertThat(result.hasMeaningfulSurge()).isFalse();
        }

        @Test
        @DisplayName("Calm conditions produce no meaningful surge")
        void calmConditions() {
            AugmentedTideResult result = service.augment(
                    1018, 3, 180, CRASTER, "REGULAR_TIDE", 4.0, 5.0);

            assertThat(result.hasMeaningfulSurge()).isFalse();
            assertThat(result.adjustedRangeMetres()).isCloseTo(4.0, within(0.05));
        }

        @Test
        @DisplayName("Storm conditions produce meaningful surge")
        void stormConditions() {
            AugmentedTideResult result = service.augment(
                    970, 22, 80, CRASTER, "SPRING_TIDE", 5.0, 5.5);

            assertThat(result.hasMeaningfulSurge()).isTrue();
            assertThat(result.surgeBreakdown().totalSurgeMetres()).isGreaterThan(0.3);
        }
    }

    @Nested
    @DisplayName("AugmentedTideResult")
    class ResultTests {

        @Test
        @DisplayName("hasMeaningfulSurge returns true above threshold")
        void meaningfulSurge() {
            var surge = new StormSurgeBreakdown(0.2, 0.1, 0.3, 990, 15, 80, 0.9,
                    TideRiskLevel.MODERATE, "test");
            var result = new AugmentedTideResult(surge, 5.3, 5.8);

            assertThat(result.hasMeaningfulSurge()).isTrue();
        }

        @Test
        @DisplayName("hasMeaningfulSurge returns false below threshold")
        void noMeaningfulSurge() {
            var surge = new StormSurgeBreakdown(0.01, 0.01, 0.02, 1013, 3, 180, 0.0,
                    TideRiskLevel.NONE, "test");
            var result = new AugmentedTideResult(surge, 4.02, 5.02);

            assertThat(result.hasMeaningfulSurge()).isFalse();
        }

        @Test
        @DisplayName("isRareOpportunity requires spring/king + meaningful surge >= 0.15")
        void rareOpportunity() {
            var surge = new StormSurgeBreakdown(0.15, 0.10, 0.25, 990, 15, 80, 0.9,
                    TideRiskLevel.MODERATE, "test");
            var result = new AugmentedTideResult(surge, 5.25, 5.75);

            assertThat(result.isRareOpportunity("KING_TIDE")).isTrue();
            assertThat(result.isRareOpportunity("SPRING_TIDE")).isTrue();
            assertThat(result.isRareOpportunity("REGULAR_TIDE")).isFalse();
        }

        @Test
        @DisplayName("isRareOpportunity requires surge >= 0.15m")
        void rareOpportunityNeedsSufficientSurge() {
            var surge = new StormSurgeBreakdown(0.05, 0.05, 0.10, 1005, 10, 80, 0.5,
                    TideRiskLevel.LOW, "test");
            var result = new AugmentedTideResult(surge, 4.1, 5.1);

            assertThat(result.isRareOpportunity("KING_TIDE")).isFalse();
        }

        @Test
        @DisplayName("isRareOpportunity false when not significant")
        void rareOpportunityRequiresSignificance() {
            var surge = StormSurgeBreakdown.none();
            var result = new AugmentedTideResult(surge, 4.0, 5.0);

            assertThat(result.isRareOpportunity("KING_TIDE")).isFalse();
        }
    }
}
