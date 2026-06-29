package com.gregochr.goldenhour.eval;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AtmosphericData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies every registered sky-rating eval fixture deserialises <em>faithfully</em> — the frozen
 * JSON round-trips into a well-formed {@link AtmosphericData} with no silently-defaulted fields.
 *
 * <p>This runs in the default {@code verify} build (no {@code @Tag}, no Claude). It does <b>not</b>
 * require every fixture to be fully augmented: the fixtures are real days ported from
 * {@code PromptRegressionTest}, and augmentation is the day's reality — some carry directional cloud
 * and a cloud-approach trend, others (e.g. observer-only Angel of the North) carry only what was
 * persisted for that run. The per-fixture spot-checks assert distinctive scalar/sub-record values,
 * which catches a mistyped JSON field name (it would otherwise deserialise to a silent default).
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

    @Test
    void onlyTheKnownBorderlineWashoutIsMonitored() {
        // 'Monitored' (gated=false) relaxes the build gate, so it must not spread beyond the one
        // fixture empirically shown to be a session coin-flip. Everything else stays gated so the
        // manual run still catches real regressions.
        List<String> monitored = SkyRatingEvalFixtures.ALL.stream()
                .filter(f -> !f.gated())
                .map(SkyRatingEvalFixture::name)
                .toList();

        assertEquals(List.of("copt-hill-5mar-washout"), monitored,
                "only the known coin-flip washout may be monitored — do not let it become a "
                        + "dumping ground for inconvenient fixtures");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyFixtureDeserialisesWithCoreFields(SkyRatingEvalFixture fixture) {
        AtmosphericData data = SkyRatingEvalFixtures.load(fixture);

        // Always-block — a fixture cannot run without these; nulls here mean broken JSON.
        assertNotNull(data.locationName(), "locationName");
        assertNotNull(data.solarEventTime(), "solarEventTime (LocalDateTime must round-trip)");
        assertNotNull(data.targetType(), "targetType");
        assertNotNull(data.cloud(), "cloud");
        assertNotNull(data.weather(), "weather");
        assertNotNull(data.weather().windSpeedMs(), "weather.windSpeedMs (BigDecimal must parse)");
        assertNotNull(data.aerosol(), "aerosol");
        assertNotNull(data.aerosol().pm25(), "aerosol.pm25 (BigDecimal must parse)");
        assertNotNull(data.comfort(), "comfort");
        assertTrue(fixture.band().min() >= 1 && fixture.band().max() <= 5, "band within 1..5");
    }

    @Test
    void washoutHasBlockedSolarHorizonAndNoCloudApproach() {
        AtmosphericData data = load("copt-hill-5mar-washout");

        assertEquals(1, data.cloud().lowCloudPercent(), "near-clear observer point");
        assertEquals(67, data.directionalCloud().solarLowCloudPercent(), "blocked solar horizon");
        assertEquals(100, data.directionalCloud().solarHighCloudPercent());
        assertNull(data.cloudApproach(), "cloud-approach was not captured for this run");
    }

    @Test
    void moderateCarriesTideAndDirectionalAtSunrise() {
        AtmosphericData data = load("st-marys-10mar-moderate");

        assertEquals(TargetType.SUNRISE, data.targetType());
        assertEquals(0, data.cloud().lowCloudPercent(), "clear low cloud lets light through");
        assertEquals(100, data.directionalCloud().solarMidCloudPercent(), "mid/high canvas at horizon");
        assertNotNull(data.tide(), "coastal — routes to CoastalPromptBuilder");
        assertEquals(TideState.HIGH, data.tide().tideState());
        assertEquals(0, new BigDecimal("1.11").compareTo(data.tide().nextHighTideHeightMetres()),
                "tide height BigDecimal must parse");
    }

    @Test
    void overcastBuildsTowardTheEventWithAnUnderCalledUpwindBank() {
        AtmosphericData data = load("copt-hill-15mar-overcast");

        assertEquals(100, data.cloud().lowCloudPercent(), "total low overcast");
        assertEquals(39, data.directionalCloud().solarLowCloudPercent());
        assertTrue(data.cloudApproach().solarTrend().isBuilding(), "low cloud builds toward the event");
        assertEquals(84, data.cloudApproach().upwindSample().currentLowCloudPercent(),
                "cloud is high upwind RIGHT NOW");
        assertEquals(0, data.cloudApproach().upwindSample().eventLowCloudPercent(),
                "model optimistically predicts a clear horizon");
    }

    @Test
    void falsePositiveCarriesTheUpwindApproachSignal() {
        AtmosphericData data = load("copt-hill-11mar-false-positive");

        assertEquals(7, data.directionalCloud().solarLowCloudPercent(), "clear event-time snapshot");
        assertNotNull(data.cloudApproach().upwindSample(), "the upwind bank is the whole point");
        assertEquals(70, data.cloudApproach().upwindSample().currentLowCloudPercent(),
                "cloud is high upwind RIGHT NOW");
        assertEquals(15, data.cloudApproach().upwindSample().eventLowCloudPercent(),
                "model optimistically predicts clearing");
    }

    @Test
    void horizonStripHasAClearingFarField() {
        AtmosphericData data = load("copt-hill-16mar-horizon-strip");

        assertEquals(64, data.directionalCloud().solarLowCloudPercent(), "high low-cloud at the horizon");
        assertEquals(12, data.directionalCloud().farSolarLowCloudPercent(),
                "far-field clears — thin strip, not an extensive blanket");
    }

    @Test
    void clearSkyCapHasNoCanvasInAnyLayerButCarriesTide() {
        AtmosphericData data = load("st-marys-7apr-clear-sky-cap");

        assertEquals(0, data.cloud().lowCloudPercent());
        assertEquals(0, data.cloud().midCloudPercent());
        assertEquals(0, data.cloud().highCloudPercent());
        assertNull(data.directionalCloud(), "clear dawn — no directional persisted");
        assertNotNull(data.tide(), "coastal");
        assertEquals(2.4, data.weather().dewPointCelsius().doubleValue(), 1e-9,
                "dewPoint Double must parse");
    }

    @Test
    void handAuthoredMiddlingCarriesFullAugmentation() {
        AtmosphericData data = load("middling-hazy-mixed");

        assertEquals(35, data.cloud().lowCloudPercent());
        assertNotNull(data.directionalCloud(), "the curated middling fixture is fully augmented");
        assertEquals(30, data.directionalCloud().farSolarLowCloudPercent());
        assertNotNull(data.cloudApproach().solarTrend(), "carries the trend block");
        assertEquals(4, data.cloudApproach().solarTrend().slots().size());
        assertNotNull(data.pressureTrend(), "and a pressure trend");
    }

    private static AtmosphericData load(String name) {
        return SkyRatingEvalFixtures.load(byName(name));
    }

    private static SkyRatingEvalFixture byName(String name) {
        return SkyRatingEvalFixtures.ALL.stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such fixture: " + name));
    }
}
