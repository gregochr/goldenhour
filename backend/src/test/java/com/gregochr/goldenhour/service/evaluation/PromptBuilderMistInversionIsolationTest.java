package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.BrathayValleyMistFixture;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.service.BluebellConditionService;
import com.gregochr.goldenhour.service.ForecastDataAugmentor;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.SurgeCalibrationLogger;
import com.gregochr.goldenhour.service.TideFactDeriver;
import com.gregochr.goldenhour.service.TideService;
import com.gregochr.goldenhour.service.WeatherAugmentedTideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.MonthDay;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Prompt isolation guard (inversion-v2-plan section 4 Phase 0): for a site that is both
 * {@code mist_prone} and inversion-eligible, the {@code VALLEY MIST CONDITIONS:} block of the user
 * message is identical whatever the inversion inputs are, whether or not the inversion block sits
 * beside it.
 *
 * <p>This pins what the prompt builder prints. It cannot test what Claude does with the
 * neighbouring inversion block when it echoes {@code mist_potential}; the plan's production query
 * (which sites are both mist-prone and inversion-eligible) covers that exposure.
 */
class PromptBuilderMistInversionIsolationTest {

    private static final String MIST_HEADER = "VALLEY MIST CONDITIONS:";

    private static final String INVERSION_HEADER = "CLOUD INVERSION FORECAST:";

    /** A 335 m water-overlook viewpoint: eligible for inversion (200 m and over, overlooks water). */
    private static final int ELIGIBLE_ELEVATION_M = 335;

    private final PromptBuilder promptBuilder = new PromptBuilder();

    private ForecastDataAugmentor augmentor;

    @BeforeEach
    void setUp() {
        augmentor = new ForecastDataAugmentor(mock(OpenMeteoService.class), mock(SolarService.class),
                mock(TideService.class), mock(TideFactDeriver.class),
                mock(WeatherAugmentedTideService.class), mock(SurgeCalibrationLogger.class),
                mock(BluebellConditionService.class),
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"));
    }

    /** The user message for the eligible, mist-prone fixture site with the given inversion inputs. */
    private String message(Double temperature925, Double temperature850, int boundaryLayerMetres) {
        AtmosphericData parsed = BrathayValleyMistFixture.sunriseAt(
                BrathayValleyMistFixture.SUNRISE_READS_0700);
        AtmosphericData varied = BrathayValleyMistFixture.withUpperAir(
                parsed, temperature925, temperature850, boundaryLayerMetres);
        AtmosphericData withInversion = augmentor.augmentWithInversionScore(
                varied, ELIGIBLE_ELEVATION_M, true);
        AtmosphericData withMist = augmentor.augmentWithValleyMist(withInversion, true);
        return promptBuilder.buildUserMessage(withMist);
    }

    /** The mist block: from its header to the end of the echo instruction that closes it. */
    private static String mistBlock(String message) {
        int start = message.indexOf(MIST_HEADER);
        int end = message.indexOf(PromptBuilder.VALLEY_MIST_ECHO_INSTRUCTION)
                + PromptBuilder.VALLEY_MIST_ECHO_INSTRUCTION.length();
        assertThat(start).as("mist block present").isNotNegative();
        assertThat(end).as("mist block closed by the echo instruction").isGreaterThan(start);
        return message.substring(start, end);
    }

    static Stream<Arguments> inversionInputs() {
        return Stream.of(
                // Reversal positive, score at the prompt's inclusion threshold (7): inversion block IN.
                Arguments.of("strong positive reversal, inversion block included", 12.0, null, 100, true),
                Arguments.of("positive reversal via 850 hPa only, inversion block included",
                        null, 14.0, 100, true),
                // Reversal positive but the viewpoint is inside the stable layer: score capped, block OUT.
                Arguments.of("positive reversal but vetoed by clearance, block absent", 12.0, null, 500, false),
                // Reversal negative (as parsed: 925 hPa colder than the surface): capped at 6, block OUT.
                Arguments.of("negative reversal, block absent", 1.8, null, 100, false),
                Arguments.of("strongly negative reversal, block absent", -8.0, null, 100, false),
                // No profile at all: capped at 8 (not vetoed), so the block is IN but has no reversal line.
                Arguments.of("no pressure-level data, inversion block included", null, null, 100, true));
    }

    @Test
    @DisplayName("the mist block exists and states the real Brathay reading, with no inversion wording in it")
    void mistBlock_statesTheBrathayReading() {
        String block = mistBlock(message(12.0, null, 100));

        assertThat(block).startsWith(MIST_HEADER)
                .contains("Score: 7.0/10 (LIKELY)")
                .contains("Tightest temp-dew gap overnight: 2.0°C at event")
                .doesNotContainIgnoringCase("inversion")
                .doesNotContain("reversal");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inversionInputs")
    @DisplayName("the VALLEY MIST CONDITIONS block is identical whatever the inversion inputs")
    void mistBlock_isIdenticalWhateverTheInversionInputs(String name, Double temperature925,
            Double temperature850, int boundaryLayerMetres, boolean inversionBlockExpected) {
        String baseline = mistBlock(message(12.0, null, 100));

        String message = message(temperature925, temperature850, boundaryLayerMetres);

        // Guard the guard: the case really is the one it claims to be.
        assertThat(message.contains(INVERSION_HEADER)).isEqualTo(inversionBlockExpected);
        assertThat(mistBlock(message)).isEqualTo(baseline);
    }
}
