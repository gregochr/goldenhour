package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPhase;
import com.gregochr.solarutils.LunarPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuroraScorer} scoring logic and text generation.
 */
class AuroraScorerTest {

    private LunarCalculator mockLunarCalc;
    private AuroraScorer scorer;

    @BeforeEach
    void setUp() {
        mockLunarCalc = mock(LunarCalculator.class);
        scorer = new AuroraScorer(mockLunarCalc);
    }

    // -------------------------------------------------------------------------
    // Score modifier unit tests
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "cloudModifier({0}%) = {1}")
    @CsvSource({
        "0,   1.0",
        "10,  1.0",
        "19,  1.0",
        "20,  0.5",
        "30,  0.5",
        "39,  0.5",
        "40,  0.0",
        "59,  0.0",
        "60, -1.0",
        "79, -1.0",
        "80, -1.5",
        "100,-1.5"
    })
    @DisplayName("cloudModifier returns expected modifier for cloud % boundaries")
    void cloudModifier_boundaries(int cloud, double expected) {
        assertThat(scorer.cloudModifier(cloud)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "moonModifier(penalty={0}) = {1}")
    @CsvSource({
        "0.0,   0.5",
        "0.14,  0.25",
        "0.15,  0.0",
        "0.34,  0.0",
        "0.35, -0.5",
        "0.64, -0.5",
        "0.65, -1.0",
        "1.0,  -1.0"
    })
    @DisplayName("moonModifier maps auroraPenalty correctly")
    void moonModifier_penaltyBoundaries(double penalty, double expected) {
        // aboveHorizon=false only when penalty is 0.0 (moon below horizon → auroraPenalty()=0)
        LunarPosition moon = mockMoonWithPenalty(penalty, penalty != 0.0);
        assertThat(scorer.moonModifier(moon)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "bortleModifier(class={0}) = {1}")
    @CsvSource({
        "1,  0.5",
        "2,  0.5",
        "3,  0.0",
        "4,  0.0",
        "5, -0.5",
        "9, -0.5"
    })
    @DisplayName("bortleModifier returns correct modifier for each class")
    void bortleModifier_allClasses(int bortleClass, double expected) {
        assertThat(scorer.bortleModifier(bortleClass)).isEqualTo(expected);
    }

    @Test
    @DisplayName("bortleModifier(null) returns 0.0 — no data available")
    void bortleModifier_null_returnsZero() {
        assertThat(scorer.bortleModifier(null)).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // Composite scoring scenarios
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5-star scenario: RED + clear skies + moon below horizon + Bortle 2")
    void score_fiveStar_idealConditions() {
        stubMoon(0.0, false, 0.0, LunarPhase.NEW_MOON); // below horizon
        var location = locationWithBortle(2);

        var scores = scorer.score(AlertLevel.RED, List.of(location), Map.of("Test", 10));

        assertThat(scores).hasSize(1);
        AuroraForecastScore score = scores.get(0);
        assertThat(score.stars()).isEqualTo(5);
        assertThat(score.summary()).contains("★★★★★");
        assertThat(score.detail()).isNotBlank();
    }

    @Test
    @DisplayName("1-star scenario: AMBER + overcast + full moon + Bortle 5")
    void score_oneStar_worstConditions() {
        // Full moon in northern sky, high altitude = max penalty
        stubMoon(1.0, true, 0.98, LunarPhase.FULL_MOON);
        var location = locationWithBortle(5);

        var scores = scorer.score(AlertLevel.AMBER, List.of(location), Map.of("Test", 95));

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).stars()).isEqualTo(1);
    }

    @Test
    @DisplayName("3-star scenario: AMBER + mostly clear + moderate moon + Bortle 4")
    void score_threeStar_moderateConditions() {
        stubMoon(0.25, true, 0.40, LunarPhase.WAXING_GIBBOUS); // moderate penalty
        var location = locationWithBortle(4);

        var scores = scorer.score(AlertLevel.AMBER, List.of(location), Map.of("Test", 25));

        assertThat(scores).hasSize(1);
        int stars = scores.get(0).stars();
        assertThat(stars).isBetween(2, 4);
    }

    // -------------------------------------------------------------------------
    // Score clamping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Score is always clamped to [1, 5] regardless of inputs")
    void score_alwaysClampedToRange() {
        // Best case: RED + clear + no moon + Bortle 1 = 4 + 1 + 0.5 + 0.5 = 6 → clamped to 5
        stubMoon(0.0, false, 0.0, LunarPhase.NEW_MOON);
        var location = locationWithBortle(1);
        var scores = scorer.score(AlertLevel.RED, List.of(location), Map.of("Test", 0));
        assertThat(scores.get(0).stars()).isLessThanOrEqualTo(5);

        // Worst case: AMBER + overcast + severe moon + Bortle 9 = 3 -1.5 -1.0 -0.5 = 0 → clamped to 1
        stubMoon(0.9, true, 0.98, LunarPhase.FULL_MOON);
        var bad = locationWithBortle(9);
        var badScores = scorer.score(AlertLevel.AMBER, List.of(bad), Map.of("Test", 100));
        assertThat(badScores.get(0).stars()).isGreaterThanOrEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Text generation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("summary is non-blank and contains star characters")
    void score_summaryContainsStars() {
        stubMoon(0.0, false, 0.0, LunarPhase.NEW_MOON);
        var location = locationWithBortle(3);
        var scores = scorer.score(AlertLevel.AMBER, List.of(location), Map.of("Test", 20));

        assertThat(scores.get(0).summary()).isNotBlank();
        assertThat(scores.get(0).summary()).containsAnyOf("★", "☆");
    }

    @Test
    @DisplayName("detail contains all four factor sections")
    void score_detailContainsAllFactors() {
        stubMoon(0.2, true, 0.5, LunarPhase.WAXING_GIBBOUS);
        var location = locationWithBortle(3);
        var scores = scorer.score(AlertLevel.RED, List.of(location), Map.of("Test", 40));

        String detail = scores.get(0).detail();
        assertThat(detail).contains("Geomagnetic activity");
        assertThat(detail).contains("Cloud cover");
        assertThat(detail).contains("Moonlight");
        assertThat(detail).contains("Dark skies");
    }

    @Test
    @DisplayName("detail mentions Bortle class number when data is available")
    void score_detailMentionsBortleClass() {
        stubMoon(0.0, false, 0.0, LunarPhase.NEW_MOON);
        var location = locationWithBortle(3);
        var scores = scorer.score(AlertLevel.AMBER, List.of(location), Map.of("Test", 10));

        assertThat(scores.get(0).detail()).contains("Bortle 3");
    }

    @Test
    @DisplayName("detail mentions 'No dark sky data' when bortle_class is null")
    void score_detail_noBortleData() {
        stubMoon(0.0, false, 0.0, LunarPhase.NEW_MOON);
        var location = locationWithBortle(null);
        var scores = scorer.score(AlertLevel.AMBER, List.of(location), Map.of("Test", 10));

        assertThat(scores.get(0).detail()).contains("No dark sky data");
    }

    @Test
    @DisplayName("Moon detail changes based on above/below horizon")
    void score_moonDetail_belowHorizon() {
        stubMoon(0.0, false, 0.05, LunarPhase.WANING_CRESCENT);
        var location = locationWithBortle(3);
        var scores = scorer.score(AlertLevel.AMBER, List.of(location), Map.of("Test", 20));

        assertThat(scores.get(0).detail()).contains("below the horizon");
    }

    @Test
    @DisplayName("Multiple locations are all scored")
    void score_multipleLocations_allScored() {
        stubMoon(0.0, false, 0.0, LunarPhase.NEW_MOON);
        var loc1 = locationWithBortleAndName(2, "Dark Peak");
        var loc2 = locationWithBortleAndName(4, "Bamburgh");
        var loc3 = locationWithBortleAndName(5, "Urban Site");

        var scores = scorer.score(AlertLevel.AMBER,
                List.of(loc1, loc2, loc3),
                Map.of("Dark Peak", 15, "Bamburgh", 45, "Urban Site", 85));

        assertThat(scores).hasSize(3);
        assertThat(scores).allMatch(s -> s.stars() >= 1 && s.stars() <= 5);
        assertThat(scores).allMatch(s -> !s.summary().isBlank());
        assertThat(scores).allMatch(s -> !s.detail().isBlank());
    }

    @Test
    @DisplayName("cloudPercent is stored on the score")
    void score_cloudPercentStored() {
        stubMoon(0.0, false, 0.0, LunarPhase.NEW_MOON);
        var location = locationWithBortle(3);
        var scores = scorer.score(AlertLevel.AMBER, List.of(location), Map.of("Test", 37));

        assertThat(scores.get(0).cloudPercent()).isEqualTo(37);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubMoon(double penalty, boolean aboveHorizon,
            double illumination, LunarPhase phase) {
        LunarPosition moon = new LunarPosition(
                aboveHorizon ? 20.0 : -5.0, // altitude
                350.0,                       // northern sky azimuth
                illumination,
                phase,
                384_400.0);
        when(mockLunarCalc.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenReturn(moon);
    }

    private LunarPosition mockMoonWithPenalty(double penalty, boolean aboveHorizon) {
        // Build a LunarPosition whose auroraPenalty() matches the expected value
        if (!aboveHorizon) {
            return new LunarPosition(-10.0, 0.0, 0.0, LunarPhase.NEW_MOON, 384_400.0);
        }
        // Use altitude=90, illumination=penalty, northern sky (azimuth 0) for predictable penalty
        // auroraPenalty = illumination * sin(90°) * 1.0 (northern sky) = illumination
        return new LunarPosition(90.0, 0.0, penalty, LunarPhase.FULL_MOON, 384_400.0);
    }

    private LocationEntity locationWithBortle(Integer bortleClass) {
        return locationWithBortleAndName(bortleClass, "Test");
    }

    private LocationEntity locationWithBortleAndName(Integer bortleClass, String name) {
        return LocationEntity.builder()
                .id(1L)
                .name(name)
                .lat(54.78)
                .lon(-1.58)
                .bortleClass(bortleClass)
                .build();
    }
}
