package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.BrathayValleyMistFixture;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.SeasonalWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * Isolation guard (inversion-v2-plan section 3, rule 1 and 4; section 4 Phase 0): the valley mist
 * score and band must not move when only inputs that mist must not read are varied.
 *
 * <p>Runs the REAL production chain on the real Brathay fixture, in the order {@code ForecastService}
 * runs it: {@code augmentWithInversionScore} then {@code augmentWithValleyMist} on its output. The
 * only things that change between variants are the pressure-level temperatures, the boundary layer
 * height, the viewpoint elevation and {@code overlooksWater}.
 *
 * <p><b>Later phases extend {@link #inversionOnlyVariants()}</b> with their new inversion-only inputs
 * (975/950 hPa temperatures, geopotential heights, pressure-level winds): add a field to
 * {@link Variant}, apply it in {@link #chain}, and add rows. Every row is asserted against the
 * baseline mist score and band.
 */
class ValleyMistIsolationTest {

    private static final LocalDateTime[] SUNRISES = {
        BrathayValleyMistFixture.SUNRISE_READS_0700, BrathayValleyMistFixture.SUNRISE_READS_0600};

    private ForecastDataAugmentor augmentor;

    @BeforeEach
    void setUp() {
        // Neither augment method used here touches a collaborator, so every one is a mock.
        augmentor = new ForecastDataAugmentor(mock(OpenMeteoService.class), mock(SolarService.class),
                mock(TideService.class), mock(TideFactDeriver.class),
                mock(WeatherAugmentedTideService.class), mock(SurgeCalibrationLogger.class),
                mock(BluebellConditionService.class),
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"));
    }

    /**
     * One combination of the inputs mist must not read. A null {@code temperature925} /
     * {@code temperature850} means "the profile level is absent".
     */
    record Variant(String name, Double temperature925, Double temperature850, int boundaryLayerMetres,
            Integer elevationMetres, boolean overlooksWater) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** Parsed fixture temperatures at 07:00 are 925 hPa 1.8 C and surface 5.8 C; 850 hPa is absent. */
    static List<Variant> inversionOnlyVariants() {
        return List.of(
                // 925 / 850 hPa temperatures at an eligible 335 m water-overlook site.
                new Variant("strong reversal (925 +12 C)", 12.0, null, 100, 335, true),
                new Variant("strong reversal via 850 only", null, 14.0, 100, 335, true),
                new Variant("no reversal (as parsed, 925 colder)", 1.8, null, 100, 335, true),
                new Variant("no profile at all (null 925 and 850)", null, null, 100, 335, true),
                new Variant("reversal at both levels", 9.0, 10.0, 100, 335, true),
                // Boundary layer height, which gates inversion clearance.
                new Variant("boundary layer 5 m", 12.0, null, 5, 335, true),
                new Variant("boundary layer 500 m (viewpoint inside it)", 12.0, null, 500, 335, true),
                new Variant("boundary layer 1500 m", 12.0, null, 1500, 335, true),
                // Elevation: null, below the 200 m gate, and the plan's 335 / 597 m sites.
                new Variant("elevation null", 12.0, null, 100, null, true),
                new Variant("elevation 150 (below the inversion gate)", 12.0, null, 100, 150, true),
                new Variant("elevation 335", 12.0, null, 100, 335, true),
                new Variant("elevation 597", 12.0, null, 100, 597, true),
                // overlooksWater, which gates inversion eligibility.
                new Variant("overlooks water", 12.0, null, 100, 335, true),
                new Variant("does not overlook water", 12.0, null, 100, 335, false),
                new Variant("everything inversion-friendly", 15.0, 18.0, 5, 597, true),
                new Variant("everything inversion-hostile", -5.0, -9.0, 2000, 150, false));
    }

    static Stream<Arguments> variantsAtBothSlots() {
        return Stream.of(SUNRISES).flatMap(sunrise -> inversionOnlyVariants().stream()
                .map(variant -> Arguments.of(sunrise, variant)));
    }

    /** The real chain: inversion first, then mist on its output, as ForecastService does. */
    private AtmosphericData chain(LocalDateTime sunrise, Variant variant) {
        AtmosphericData parsed = BrathayValleyMistFixture.sunriseAt(sunrise);
        AtmosphericData varied = BrathayValleyMistFixture.withUpperAir(parsed,
                variant.temperature925(), variant.temperature850(), variant.boundaryLayerMetres());
        AtmosphericData withInversion = augmentor.augmentWithInversionScore(
                varied, variant.elevationMetres(), variant.overlooksWater());
        return augmentor.augmentWithValleyMist(withInversion, true);
    }

    /** The baseline: the data exactly as the parser produced it, no inversion eligibility. */
    private AtmosphericData baseline(LocalDateTime sunrise) {
        AtmosphericData parsed = BrathayValleyMistFixture.sunriseAt(sunrise);
        AtmosphericData withInversion = augmentor.augmentWithInversionScore(parsed, null, false);
        return augmentor.augmentWithValleyMist(withInversion, true);
    }

    @Test
    @DisplayName("the baselines are the pinned Brathay readings: 7.03 LIKELY at 07:00, 5.03 POSSIBLE at 06:00")
    void baselines_arePinned() {
        AtmosphericData at0700 = baseline(BrathayValleyMistFixture.SUNRISE_READS_0700);
        AtmosphericData at0600 = baseline(BrathayValleyMistFixture.SUNRISE_READS_0600);

        assertThat(at0700.mistScore()).isCloseTo(7.03, within(0.01));
        assertThat(at0700.mistPotential()).isEqualTo(MistPotential.LIKELY);
        assertThat(at0600.mistScore()).isCloseTo(5.03, within(0.01));
        assertThat(at0600.mistPotential()).isEqualTo(MistPotential.POSSIBLE);
        assertThat(at0700.inversionScore()).isNull();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("variantsAtBothSlots")
    @DisplayName("mist score and band are identical whatever the inversion-only inputs")
    void mist_isIdenticalAcrossInversionOnlyInputs(LocalDateTime sunrise, Variant variant) {
        AtmosphericData expected = baseline(sunrise);

        AtmosphericData actual = chain(sunrise, variant);

        // Exact equality, not a tolerance: nothing mist reads has changed.
        assertThat(actual.mistScore()).isEqualTo(expected.mistScore());
        assertThat(actual.mistPotential()).isEqualTo(expected.mistPotential());
    }

    @Test
    @DisplayName("the variants really do move the inversion score, so the isolation above is not vacuous")
    void variants_doReachTheInversionScore() {
        LocalDateTime sunrise = BrathayValleyMistFixture.SUNRISE_READS_0700;
        Variant strong = inversionOnlyVariants().get(0);
        Variant none = inversionOnlyVariants().get(2);
        Variant ineligible = inversionOnlyVariants().get(9);

        AtmosphericData strongResult = chain(sunrise, strong);
        AtmosphericData noneResult = chain(sunrise, none);

        assertThat(strongResult.inversionScore()).isNotNull();
        assertThat(noneResult.inversionScore()).isNotNull();
        assertThat(strongResult.inversionScore()).isGreaterThan(noneResult.inversionScore());
        assertThat(chain(sunrise, ineligible).inversionScore()).isNull();
    }
}
