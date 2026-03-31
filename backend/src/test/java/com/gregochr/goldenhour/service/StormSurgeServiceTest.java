package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.CoastalParameters;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link StormSurgeService}.
 */
class StormSurgeServiceTest {

    private StormSurgeService service;

    private static final CoastalParameters CRASTER =
            new CoastalParameters(80, 250_000, 30, true);
    private static final CoastalParameters BAMBURGH =
            new CoastalParameters(75, 300_000, 30, true);

    @BeforeEach
    void setUp() {
        service = new StormSurgeService();
    }

    // ── Pressure Effect Tests ────────────────────────────────────────

    @Nested
    @DisplayName("Inverse barometer effect (0.01 m/hPa)")
    class PressureEffectTests {

        @Test
        @DisplayName("Standard pressure produces zero rise")
        void standardPressure_zeroRise() {
            double rise = service.calculatePressureRise(1013.25);
            assertThat(rise).isCloseTo(0.0, within(0.001));
        }

        @Test
        @DisplayName("Deep low pressure (970 hPa) produces ~0.43m rise")
        void deepLow_significantRise() {
            double rise = service.calculatePressureRise(970.0);
            assertThat(rise).isCloseTo(0.4325, within(0.001));
        }

        @Test
        @DisplayName("Moderate low pressure (990 hPa) produces ~0.23m rise")
        void moderateLow_moderateRise() {
            double rise = service.calculatePressureRise(990.0);
            assertThat(rise).isCloseTo(0.2325, within(0.001));
        }

        @Test
        @DisplayName("High pressure (1030 hPa) produces negative rise (suppression)")
        void highPressure_suppression() {
            double rise = service.calculatePressureRise(1030.0);
            assertThat(rise).isNegative();
            assertThat(rise).isCloseTo(-0.1675, within(0.001));
        }

        @Test
        @DisplayName("Storm Xaver-like pressure (~960 hPa) produces ~0.53m rise")
        void xaverLikePressure() {
            double rise = service.calculatePressureRise(960.0);
            assertThat(rise).isCloseTo(0.5325, within(0.001));
        }

        @Test
        @DisplayName("Pressure rise is linear with pressure difference")
        void linearRelationship() {
            double rise990 = service.calculatePressureRise(990.0);
            double rise980 = service.calculatePressureRise(980.0);
            assertThat(rise980 - rise990).isCloseTo(0.10, within(0.001));
        }

        @Test
        @DisplayName("Extreme low pressure (940 hPa) still gives correct result")
        void extremeLowPressure() {
            double rise = service.calculatePressureRise(940.0);
            assertThat(rise).isCloseTo(0.7325, within(0.001));
        }
    }

    // ── Onshore Component Tests ──────────────────────────────────────

    @Nested
    @DisplayName("Onshore wind component projection")
    class OnshoreComponentTests {

        @Test
        @DisplayName("Wind directly onshore (FROM shore-normal direction) = 1.0")
        void directlyOnshore() {
            double component = service.calculateOnshoreComponent(80, 80);
            assertThat(component).isCloseTo(1.0, within(0.01));
        }

        @Test
        @DisplayName("Wind parallel to coast = 0.0")
        void parallelToCoast() {
            double component = service.calculateOnshoreComponent(170, 80);
            assertThat(component).isCloseTo(0.0, within(0.01));
        }

        @Test
        @DisplayName("Wind offshore (opposite to shore-normal) clamped to 0.0")
        void offshore() {
            double component = service.calculateOnshoreComponent(260, 80);
            assertThat(component).isCloseTo(0.0, within(0.01));
        }

        @Test
        @DisplayName("Wind 45° to shore-normal = cos(45°) ≈ 0.71")
        void angledOnshore() {
            double component = service.calculateOnshoreComponent(125, 80);
            assertThat(component).isCloseTo(0.707, within(0.02));
        }

        @ParameterizedTest
        @DisplayName("North wind on east-facing Northumberland coast")
        @CsvSource({
            "0,   80,  0.17",
            "45,  80,  0.82",
            "90,  80,  0.98",
            "180, 80,  0.0",
            "270, 80,  0.0",
            "360, 80,  0.17"
        })
        void northumberlandScenarios(double windFrom, double shoreNormal, double expected) {
            double component = service.calculateOnshoreComponent(windFrom, shoreNormal);
            assertThat(component).isCloseTo(expected, within(0.05));
        }

        @Test
        @DisplayName("Wrap-around: wind 350° on shore-normal 10° = near-direct onshore")
        void wrapAround() {
            double component = service.calculateOnshoreComponent(350, 10);
            assertThat(component).isCloseTo(0.94, within(0.05));
        }

        @Test
        @DisplayName("Wind exactly 180° from shore-normal is fully offshore = 0.0")
        void exactlyOpposite() {
            double component = service.calculateOnshoreComponent(270, 90);
            assertThat(component).isCloseTo(0.0, within(0.01));
        }

        @Test
        @DisplayName("North-facing coast with north wind = fully onshore")
        void northFacingNorthWind() {
            double component = service.calculateOnshoreComponent(0, 0);
            assertThat(component).isCloseTo(1.0, within(0.01));
        }

        @Test
        @DisplayName("South-facing coast with south wind = fully onshore")
        void southFacingSouthWind() {
            double component = service.calculateOnshoreComponent(180, 180);
            assertThat(component).isCloseTo(1.0, within(0.01));
        }
    }

    // ── Wind Setup Tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("Wind setup calculation")
    class WindSetupTests {

        @Test
        @DisplayName("Zero wind produces zero setup")
        void zeroWind() {
            double setup = service.calculateWindSetup(0, 1.0, CRASTER);
            assertThat(setup).isZero();
        }

        @Test
        @DisplayName("Offshore wind (onshore=0) produces zero setup")
        void offshoreWind() {
            double setup = service.calculateWindSetup(15, 0.0, CRASTER);
            assertThat(setup).isZero();
        }

        @Test
        @DisplayName("Moderate onshore wind produces meaningful setup")
        void moderateOnshoreWind() {
            double setup = service.calculateWindSetup(15, 1.0, CRASTER);
            assertThat(setup).isGreaterThan(0.05);
            assertThat(setup).isLessThan(1.0);
        }

        @Test
        @DisplayName("Wind setup scales with wind speed squared")
        void quadraticScaling() {
            double setup10 = service.calculateWindSetup(10, 1.0, CRASTER);
            double setup20 = service.calculateWindSetup(20, 1.0, CRASTER);
            assertThat(setup20 / setup10).isCloseTo(4.0, within(0.01));
        }

        @Test
        @DisplayName("Shallower water produces more setup")
        void shallowerWaterMoreSetup() {
            var shallow = new CoastalParameters(80, 250_000, 15, true);
            var deep = new CoastalParameters(80, 250_000, 60, true);

            double setupShallow = service.calculateWindSetup(15, 1.0, shallow);
            double setupDeep = service.calculateWindSetup(15, 1.0, deep);

            assertThat(setupShallow / setupDeep).isCloseTo(4.0, within(0.01));
        }

        @Test
        @DisplayName("Longer fetch produces more setup")
        void longerFetchMoreSetup() {
            var shortFetch = new CoastalParameters(80, 100_000, 30, true);
            var longFetch = new CoastalParameters(80, 400_000, 30, true);

            double setupShort = service.calculateWindSetup(15, 1.0, shortFetch);
            double setupLong = service.calculateWindSetup(15, 1.0, longFetch);

            assertThat(setupLong / setupShort).isCloseTo(4.0, within(0.01));
        }

        @Test
        @DisplayName("Non-tidal location returns zero from calculate()")
        void nonTidalLocation() {
            StormSurgeBreakdown result = service.calculate(
                    970, 25, 80, CoastalParameters.NON_TIDAL, "REGULAR_TIDE");
            assertThat(result.totalSurgeMetres()).isZero();
        }

        @Test
        @DisplayName("Negative wind speed produces zero setup")
        void negativeWindSpeed() {
            double setup = service.calculateWindSetup(-5, 1.0, CRASTER);
            assertThat(setup).isZero();
        }

        @Test
        @DisplayName("Partial onshore component reduces setup proportionally")
        void partialOnshoreComponent() {
            double fullOnshore = service.calculateWindSetup(15, 1.0, CRASTER);
            double halfOnshore = service.calculateWindSetup(15, 0.5, CRASTER);
            assertThat(halfOnshore / fullOnshore).isCloseTo(0.5, within(0.01));
        }

        @Test
        @DisplayName("Very light wind produces negligible setup")
        void veryLightWind() {
            double setup = service.calculateWindSetup(2, 1.0, CRASTER);
            assertThat(setup).isLessThan(0.01);
        }

        @Test
        @DisplayName("Storm-force wind produces significant setup")
        void stormForceWind() {
            double setup = service.calculateWindSetup(30, 1.0, CRASTER);
            assertThat(setup).isGreaterThan(0.5);
        }
    }

    // ── Risk Classification Tests ────────────────────────────────────

    @Nested
    @DisplayName("Risk classification")
    class RiskClassificationTests {

        @Test
        @DisplayName("Tiny surge = NONE regardless of tide type")
        void tinySurge_none() {
            assertThat(service.classifyRisk(0.05, "KING_TIDE")).isEqualTo(TideRiskLevel.NONE);
            assertThat(service.classifyRisk(0.05, "REGULAR_TIDE")).isEqualTo(TideRiskLevel.NONE);
        }

        @Test
        @DisplayName("Small surge + regular tide = LOW")
        void smallSurge_regularTide_low() {
            assertThat(service.classifyRisk(0.20, "REGULAR_TIDE")).isEqualTo(TideRiskLevel.LOW);
        }

        @Test
        @DisplayName("Small surge + spring tide = MODERATE")
        void smallSurge_springTide_moderate() {
            assertThat(service.classifyRisk(0.20, "SPRING_TIDE")).isEqualTo(TideRiskLevel.MODERATE);
        }

        @Test
        @DisplayName("Medium surge + regular tide = MODERATE")
        void mediumSurge_regularTide_moderate() {
            assertThat(service.classifyRisk(0.45, "REGULAR_TIDE")).isEqualTo(TideRiskLevel.MODERATE);
        }

        @Test
        @DisplayName("Medium surge + king tide = HIGH")
        void mediumSurge_kingTide_high() {
            assertThat(service.classifyRisk(0.45, "KING_TIDE")).isEqualTo(TideRiskLevel.HIGH);
        }

        @Test
        @DisplayName("Large surge = HIGH regardless of tide type")
        void largeSurge_alwaysHigh() {
            assertThat(service.classifyRisk(0.70, "REGULAR_TIDE")).isEqualTo(TideRiskLevel.HIGH);
            assertThat(service.classifyRisk(0.70, "KING_TIDE")).isEqualTo(TideRiskLevel.HIGH);
        }

        @Test
        @DisplayName("Zero surge = NONE")
        void zeroSurge_none() {
            assertThat(service.classifyRisk(0.0, "KING_TIDE")).isEqualTo(TideRiskLevel.NONE);
        }

        @ParameterizedTest
        @DisplayName("Boundary values at 0.10m threshold")
        @CsvSource({
            "0.099, REGULAR_TIDE, NONE",
            "0.100, REGULAR_TIDE, LOW",
            "0.100, SPRING_TIDE,  MODERATE"
        })
        void boundaryAt010(double surge, String tideType, TideRiskLevel expected) {
            assertThat(service.classifyRisk(surge, tideType)).isEqualTo(expected);
        }

        @ParameterizedTest
        @DisplayName("Boundary values at 0.30m threshold")
        @CsvSource({
            "0.299, REGULAR_TIDE, LOW",
            "0.300, REGULAR_TIDE, MODERATE",
            "0.300, KING_TIDE,    HIGH"
        })
        void boundaryAt030(double surge, String tideType, TideRiskLevel expected) {
            assertThat(service.classifyRisk(surge, tideType)).isEqualTo(expected);
        }

        @ParameterizedTest
        @DisplayName("Boundary values at 0.60m threshold")
        @CsvSource({
            "0.599, REGULAR_TIDE, MODERATE",
            "0.600, REGULAR_TIDE, HIGH",
            "0.600, SPRING_TIDE,  HIGH"
        })
        void boundaryAt060(double surge, String tideType, TideRiskLevel expected) {
            assertThat(service.classifyRisk(surge, tideType)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Null lunar tide type treated as regular")
        void nullTideType() {
            assertThat(service.classifyRisk(0.20, null)).isEqualTo(TideRiskLevel.LOW);
            assertThat(service.classifyRisk(0.45, null)).isEqualTo(TideRiskLevel.MODERATE);
        }

        @Test
        @DisplayName("Unknown lunar tide type treated as regular")
        void unknownTideType() {
            assertThat(service.classifyRisk(0.20, "NEAP_TIDE")).isEqualTo(TideRiskLevel.LOW);
        }
    }

    // ── Integration / Scenario Tests ─────────────────────────────────

    @Nested
    @DisplayName("Real-world scenarios")
    class ScenarioTests {

        @Test
        @DisplayName("Calm day at Craster — minimal surge")
        void calmDay() {
            StormSurgeBreakdown result = service.calculate(
                    1018, 5, 180, CRASTER, "REGULAR_TIDE");

            assertThat(result.totalSurgeMetres()).isLessThan(0.05);
            assertThat(result.riskLevel()).isEqualTo(TideRiskLevel.NONE);
            assertThat(result.pressureRiseMetres()).isNegative();
            assertThat(result.windRiseMetres()).isZero();
        }

        @Test
        @DisplayName("Active low pressure with NE wind at Bamburgh")
        void activeLowWithOnshoreWind() {
            StormSurgeBreakdown result = service.calculate(
                    985, 18, 45, BAMBURGH, "SPRING_TIDE");

            assertThat(result.pressureRiseMetres()).isCloseTo(0.283, within(0.01));
            assertThat(result.windRiseMetres()).isGreaterThan(0.0);
            assertThat(result.totalSurgeMetres()).isGreaterThan(0.28);
            assertThat(result.onshoreComponentFraction()).isGreaterThan(0.7);
            assertThat(result.riskLevel()).isIn(TideRiskLevel.MODERATE, TideRiskLevel.HIGH);
            assertThat(result.surgeExplanation()).contains("Low pressure");
        }

        @Test
        @DisplayName("Storm Xaver-like conditions at Craster (NE wind)")
        void xaverLikeConditions() {
            // Xaver: deep low, strong NE wind (onshore for ENE-facing coast)
            StormSurgeBreakdown result = service.calculate(
                    960, 25, 60, CRASTER, "SPRING_TIDE");

            assertThat(result.pressureRiseMetres()).isCloseTo(0.5325, within(0.01));
            assertThat(result.windRiseMetres()).isGreaterThan(0.0);
            assertThat(result.totalSurgeMetres()).isGreaterThan(0.5);
            assertThat(result.riskLevel()).isEqualTo(TideRiskLevel.HIGH);
        }

        @Test
        @DisplayName("High pressure with offshore wind — water level suppressed")
        void highPressureOffshore() {
            StormSurgeBreakdown result = service.calculate(
                    1035, 12, 270, BAMBURGH, "REGULAR_TIDE");

            assertThat(result.pressureRiseMetres()).isNegative();
            assertThat(result.windRiseMetres()).isZero();
            assertThat(result.totalSurgeMetres()).isZero();
            assertThat(result.riskLevel()).isEqualTo(TideRiskLevel.NONE);
        }

        @Test
        @DisplayName("Explanation text is human-readable")
        void explanationReadable() {
            StormSurgeBreakdown result = service.calculate(
                    990, 15, 75, CRASTER, "REGULAR_TIDE");

            assertThat(result.surgeExplanation())
                    .doesNotContain("null")
                    .doesNotContain("NaN");
            if (result.isSignificant()) {
                assertThat(result.surgeExplanation()).contains("hPa");
            }
        }

        @Test
        @DisplayName("Non-tidal location gets zero breakdown")
        void nonTidalBreakdown() {
            StormSurgeBreakdown result = service.calculate(
                    960, 25, 80, CoastalParameters.NON_TIDAL, "KING_TIDE");

            assertThat(result).isEqualTo(StormSurgeBreakdown.none());
        }

        @Test
        @DisplayName("Moderate pressure drop alone produces meaningful surge")
        void pressureAlone() {
            // 15 hPa drop, no wind
            StormSurgeBreakdown result = service.calculate(
                    998, 0, 0, CRASTER, "REGULAR_TIDE");

            assertThat(result.pressureRiseMetres()).isCloseTo(0.153, within(0.01));
            assertThat(result.windRiseMetres()).isZero();
            assertThat(result.totalSurgeMetres()).isCloseTo(0.153, within(0.01));
            assertThat(result.riskLevel()).isEqualTo(TideRiskLevel.LOW);
        }

        @Test
        @DisplayName("Strong wind alone with standard pressure")
        void windAlone() {
            StormSurgeBreakdown result = service.calculate(
                    1013.25, 20, 80, CRASTER, "REGULAR_TIDE");

            assertThat(result.pressureRiseMetres()).isCloseTo(0.0, within(0.001));
            assertThat(result.windRiseMetres()).isGreaterThan(0.0);
            assertThat(result.totalSurgeMetres()).isEqualTo(result.windRiseMetres());
        }
    }

    // ── Explanation Content Tests ────────────────────────────────────

    @Nested
    @DisplayName("Explanation text content")
    class ExplanationTests {

        @Test
        @DisplayName("No significant surge gives standard message")
        void noSignificantSurge() {
            StormSurgeBreakdown result = service.calculate(
                    1015, 3, 180, CRASTER, "REGULAR_TIDE");
            assertThat(result.surgeExplanation()).isEqualTo("No significant surge expected");
        }

        @Test
        @DisplayName("HIGH risk includes caution warning")
        void highRiskCaution() {
            StormSurgeBreakdown result = service.calculate(
                    960, 25, 80, CRASTER, "SPRING_TIDE");
            assertThat(result.surgeExplanation()).contains("caution");
        }

        @Test
        @DisplayName("MODERATE risk mentions coastal photography")
        void moderateRiskPhotography() {
            StormSurgeBreakdown result = service.calculate(
                    995, 12, 80, CRASTER, "REGULAR_TIDE");
            if (result.riskLevel() == TideRiskLevel.MODERATE) {
                assertThat(result.surgeExplanation()).contains("coastal photography");
            }
        }

        @Test
        @DisplayName("Wind component described with onshore strength label")
        void windOnshoreLabel() {
            StormSurgeBreakdown result = service.calculate(
                    990, 18, 80, CRASTER, "REGULAR_TIDE");
            if (result.windRiseMetres() >= 0.02) {
                assertThat(result.surgeExplanation()).containsAnyOf(
                        "strong onshore", "moderate onshore", "weak onshore");
            }
        }

        @Test
        @DisplayName("Explanation includes wind in knots")
        void windInKnots() {
            StormSurgeBreakdown result = service.calculate(
                    990, 15, 80, CRASTER, "REGULAR_TIDE");
            if (result.windRiseMetres() >= 0.02) {
                assertThat(result.surgeExplanation()).contains("kn");
            }
        }

        @Test
        @DisplayName("High pressure suppression included in explanation")
        void highPressureSuppression() {
            // High pressure + significant wind surge to make total significant
            StormSurgeBreakdown result = service.calculate(
                    1030, 25, 80, CRASTER, "REGULAR_TIDE");
            if (result.isSignificant()) {
                assertThat(result.surgeExplanation()).contains("High pressure");
                assertThat(result.surgeExplanation()).contains("suppressing water");
            }
        }

        @Test
        @DisplayName("Weak onshore wind label when onshore component is low")
        void weakOnshoreWindLabel() {
            // Wind partly offshore — low onshore component (0.0-0.3)
            // Shore normal is 80°, wind from 170° → angle ~90° → cos≈0 → barely onshore
            // Use 120° → angle 40° → cos≈0.77 → moderate
            // Use 150° → angle 70° → cos≈0.34 → moderate (just above 0.3)
            // Use 160° → angle 80° → cos≈0.17 → weak (below 0.3)
            StormSurgeBreakdown result = service.calculate(
                    980, 20, 160, CRASTER, "REGULAR_TIDE");
            if (result.windRiseMetres() >= 0.02) {
                assertThat(result.surgeExplanation()).contains("weak onshore");
            }
        }

        @Test
        @DisplayName("Explanation always ends with total surge")
        void alwaysHasTotal() {
            StormSurgeBreakdown result = service.calculate(
                    990, 15, 80, CRASTER, "REGULAR_TIDE");
            if (result.isSignificant()) {
                assertThat(result.surgeExplanation()).contains("Total surge estimate");
            }
        }
    }

    // ── Rounding Tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("Output rounding")
    class RoundingTests {

        @Test
        @DisplayName("Output values are rounded to 3 decimal places")
        void roundedToThreeDecimalPlaces() {
            StormSurgeBreakdown result = service.calculate(
                    990, 15, 75, CRASTER, "REGULAR_TIDE");

            assertThat(result.pressureRiseMetres() * 1000 % 1).isCloseTo(0.0, within(0.001));
            assertThat(result.windRiseMetres() * 1000 % 1).isCloseTo(0.0, within(0.001));
            assertThat(result.totalSurgeMetres() * 1000 % 1).isCloseTo(0.0, within(0.001));
            assertThat(result.onshoreComponentFraction() * 1000 % 1).isCloseTo(0.0, within(0.001));
        }
    }

    // ── Edge Case Tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @ParameterizedTest
        @DisplayName("Various pressure values produce correct direction")
        @ValueSource(doubles = {900, 950, 1013.25, 1050})
        void pressureDirectionConsistency(double pressure) {
            double rise = service.calculatePressureRise(pressure);
            if (pressure < StormSurgeService.STANDARD_PRESSURE_HPA) {
                assertThat(rise).isPositive();
            } else if (pressure > StormSurgeService.STANDARD_PRESSURE_HPA) {
                assertThat(rise).isNegative();
            } else {
                assertThat(rise).isCloseTo(0.0, within(0.001));
            }
        }

        @Test
        @DisplayName("Calculate preserves input values in result")
        void preservesInputValues() {
            StormSurgeBreakdown result = service.calculate(
                    985.0, 18.0, 45.0, BAMBURGH, "SPRING_TIDE");

            assertThat(result.pressureHpa()).isEqualTo(985.0);
            assertThat(result.windSpeedMs()).isEqualTo(18.0);
            assertThat(result.windDirectionDegrees()).isEqualTo(45.0);
        }

        @Test
        @DisplayName("Total surge is never negative")
        void totalSurgeNeverNegative() {
            StormSurgeBreakdown result = service.calculate(
                    1040, 5, 270, CRASTER, "REGULAR_TIDE");
            assertThat(result.totalSurgeMetres()).isGreaterThanOrEqualTo(0.0);
        }

        @Test
        @DisplayName("Onshore component never exceeds 1.0")
        void onshoreNeverExceedsOne() {
            for (int windDir = 0; windDir < 360; windDir += 10) {
                double component = service.calculateOnshoreComponent(windDir, 90);
                assertThat(component).isBetween(0.0, 1.0);
            }
        }
    }
}
