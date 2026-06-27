package com.gregochr.goldenhour.eval;

import com.gregochr.goldenhour.model.AtmosphericData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies every registered sky-rating eval fixture is well-formed and <em>faithful</em> — i.e.
 * the frozen JSON deserialises into a fully-augmented {@link AtmosphericData} carrying the blocks
 * the prompt reasons over for colour.
 *
 * <p>This runs in the default {@code verify} build (no {@code @Tag}, no Claude). It is the guard
 * against the partial-fixture trap Step 0 flagged: a fixture missing {@code directionalCloud} or
 * {@code cloudApproach.solarTrend} would silently under-exercise the prompt, so those are asserted
 * non-null for every fixture. The per-fixture value spot-checks additionally catch a mistyped JSON
 * field name, which would otherwise deserialise to a silent default rather than the intended value.
 */
class SkyRatingEvalFixturesTest {

    private static List<SkyRatingEvalFixture> fixtures() {
        return SkyRatingEvalFixtures.ALL;
    }

    @Test
    void fixtureSetCoversBothHalvesOfTheRange() {
        boolean hasStrong = SkyRatingEvalFixtures.ALL.stream().anyMatch(f -> f.band().min() >= 4);
        boolean hasFlat = SkyRatingEvalFixtures.ALL.stream().anyMatch(f -> f.band().max() <= 2);
        boolean hasMiddling = SkyRatingEvalFixtures.ALL.stream()
                .anyMatch(f -> f.band().contains(3) && f.band().max() < 5 && f.band().min() > 1);

        assertTrue(hasStrong, "need a strong fixture (no under-rating) — band min >= 4");
        assertTrue(hasFlat, "need a flat fixture (not always-high) — band max <= 2");
        assertTrue(hasMiddling, "need a middling fixture (graded, not bimodal)");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fixtureLoadsAndIsFullyAugmented(SkyRatingEvalFixture fixture) {
        AtmosphericData data = SkyRatingEvalFixtures.load(fixture);

        // Always-block — a fixture cannot run without these.
        assertNotNull(data.locationName(), "locationName");
        assertNotNull(data.solarEventTime(), "solarEventTime (LocalDateTime must round-trip)");
        assertNotNull(data.targetType(), "targetType");
        assertNotNull(data.cloud(), "cloud");
        assertNotNull(data.weather(), "weather");
        assertNotNull(data.weather().windSpeedMs(), "weather.windSpeedMs (BigDecimal must parse)");
        assertNotNull(data.aerosol(), "aerosol");
        assertNotNull(data.comfort(), "comfort");

        // Faithful-fixture invariant: the colour-load-bearing augmented blocks must be present.
        assertNotNull(data.directionalCloud(),
                "directionalCloud must be present — it decides strong vs blocked sky");
        assertNotNull(data.cloudApproach(), "cloudApproach must be present");
        assertNotNull(data.cloudApproach().solarTrend(),
                "cloudApproach.solarTrend must be present — the [BUILDING]/[CLEARING] block");
        assertNotNull(data.cloudApproach().solarTrend().slots(), "solarTrend.slots");
        assertEquals(4, data.cloudApproach().solarTrend().slots().size(),
                "solarTrend should carry the T-3h..T slot series");
    }

    @Test
    void strongFixtureCarriesAClearingCanvasTrend() {
        AtmosphericData data = SkyRatingEvalFixtures.load(byName("strong-clearing-canvas"));

        assertEquals(5, data.cloud().lowCloudPercent());
        assertEquals(35, data.cloud().midCloudPercent());
        assertEquals(70, data.cloud().highCloudPercent());
        assertEquals(new BigDecimal("38000"), BigDecimal.valueOf(data.weather().visibilityMetres()));
        assertEquals(0, new BigDecimal("2.80").compareTo(data.weather().windSpeedMs()),
                "windSpeedMs scalar must parse from JSON");
        assertEquals(8, data.directionalCloud().solarLowCloudPercent(),
                "clear-ish solar blocker lets light through");
        assertEquals(75, data.directionalCloud().solarHighCloudPercent(), "high canvas at horizon");

        // The canvas (mid/high) must have deserialised on the slots for the clearing check to hold.
        assertTrue(data.cloudApproach().solarTrend().isClearing(),
                "blocker clears into the event while the canvas survives");
        assertFalse(data.cloudApproach().solarTrend().isBuilding(),
                "a clearing trend is not a building one");
    }

    @Test
    void flatFixtureCarriesABuildingBlanketTrend() {
        AtmosphericData data = SkyRatingEvalFixtures.load(byName("flat-grey-overcast"));

        assertEquals(100, data.cloud().lowCloudPercent(), "total low overcast");
        assertEquals(96, data.directionalCloud().solarLowCloudPercent(), "blocked solar horizon");
        assertEquals(95, data.directionalCloud().farSolarLowCloudPercent(),
                "far-field stays high — extensive blanket, not a thin strip");
        assertTrue(data.cloudApproach().solarTrend().isBuilding(),
                "low cloud builds toward the event");
    }

    @Test
    void portedFalsePositiveCarriesTheUpwindApproachSignal() {
        AtmosphericData data = SkyRatingEvalFixtures.load(byName("copt-hill-11mar-false-positive"));

        // Verbatim port of PromptRegressionTest#coptHill_11Mar2026... — spot-check the values.
        assertEquals(7, data.directionalCloud().solarLowCloudPercent(), "clear event-time snapshot");
        assertNotNull(data.cloudApproach().upwindSample(), "the upwind bank is the whole point");
        assertEquals(70, data.cloudApproach().upwindSample().currentLowCloudPercent(),
                "cloud is high upwind RIGHT NOW");
        assertEquals(15, data.cloudApproach().upwindSample().eventLowCloudPercent(),
                "model optimistically predicts clearing");
    }

    @Test
    void clearSkyCapFixtureHasNoCanvasInAnyDirection() {
        AtmosphericData data = SkyRatingEvalFixtures.load(byName("clear-sky-no-canvas-cap3"));

        assertEquals(0, data.cloud().lowCloudPercent());
        assertEquals(0, data.cloud().midCloudPercent());
        assertEquals(0, data.cloud().highCloudPercent());
        assertEquals(0, data.directionalCloud().solarHighCloudPercent(), "no canvas at the horizon");
        assertFalse(data.cloudApproach().solarTrend().isClearing(),
                "clearing to bald blue is not a dramatic clearance — no canvas survives");
        assertFalse(data.cloudApproach().solarTrend().isBuilding());
    }

    private static SkyRatingEvalFixture byName(String name) {
        return SkyRatingEvalFixtures.ALL.stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such fixture: " + name));
    }
}
