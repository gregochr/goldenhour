package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BriefingSlot}.
 */
class BriefingSlotTest {

    private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 4, 22, 6, 0);
    private static final BriefingSlot.WeatherConditions WEATHER =
            new BriefingSlot.WeatherConditions(
                    20, BigDecimal.ZERO, 15000, 70, 10.0, 8.0, 0, BigDecimal.ONE, 30, 40);

    @Nested
    @DisplayName("locationId — the FK join Close to home needs")
    class LocationIdTests {

        @Test
        @DisplayName("the 7-arg form leaves it null, so 59 existing call sites keep working")
        void sevenArgForm_leavesIdNull() {
            BriefingSlot slot = new BriefingSlot(
                    "Durham", EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of("Clear"));

            assertThat(slot.locationId()).isNull();
            assertThat(slot.locationName()).isEqualTo("Durham");
        }

        @Test
        @DisplayName("the 8-arg form carries it")
        void eightArgForm_carriesId() {
            BriefingSlot slot = new BriefingSlot(
                    42L, "Durham", EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of("Clear"));

            assertThat(slot.locationId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("canopySlot carries it too")
        void canopySlot_carriesId() {
            BriefingSlot slot = BriefingSlot.canopySlot(
                    7L, "Houghall Woods", EVENT_TIME, Verdict.GO, WEATHER,
                    List.of("Mist"));

            assertThat(slot.locationId()).isEqualTo(7L);
            assertThat(slot.canopy()).isTrue();
        }

        @Test
        @DisplayName("withClaudeScores preserves it — a wither is where an id quietly goes missing")
        void withClaudeScores_preservesId() {
            BriefingSlot slot = new BriefingSlot(
                    42L, "Durham", EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of("Clear"))
                    .withClaudeScores(4, 70, 65, "Nice", "Headline");

            assertThat(slot.locationId()).isEqualTo(42L);
            assertThat(slot.claudeRating()).isEqualTo(4);
        }

        @Test
        @DisplayName("canopy withClaudeScores preserves both the id and the canopy flag")
        void withClaudeScores_preservesIdOnCanopySlot() {
            BriefingSlot slot = BriefingSlot.canopySlot(
                    7L, "Houghall Woods", EVENT_TIME, Verdict.GO, WEATHER, List.of("Mist"))
                    .withClaudeScores(5, null, null, "Mist through the trunks.");

            assertThat(slot.locationId()).isEqualTo(7L);
            assertThat(slot.canopy()).isTrue();
        }
    }

    @Nested
    @DisplayName("Convenience constructor (7-arg)")
    class ConvenienceConstructorTests {

        @Test
        @DisplayName("Claude fields default to null")
        void claudeFieldsAreNull() {
            BriefingSlot slot = new BriefingSlot(
                    "Durham", EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of("Clear"));

            assertThat(slot.claudeRating()).isNull();
            assertThat(slot.fierySkyPotential()).isNull();
            assertThat(slot.goldenHourPotential()).isNull();
            assertThat(slot.claudeSummary()).isNull();
        }

        @Test
        @DisplayName("Non-Claude fields are preserved")
        void preservesOriginalFields() {
            BriefingSlot slot = new BriefingSlot(
                    "Bamburgh", EVENT_TIME, Verdict.MARGINAL, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of("Building cloud"));

            assertThat(slot.locationName()).isEqualTo("Bamburgh");
            assertThat(slot.solarEventTime()).isEqualTo(EVENT_TIME);
            assertThat(slot.verdict()).isEqualTo(Verdict.MARGINAL);
            assertThat(slot.flags()).containsExactly("Building cloud");
        }
    }

    @Nested
    @DisplayName("withClaudeScores()")
    class WithClaudeScoresTests {

        private final BriefingSlot base = new BriefingSlot(
                "Whitby", EVENT_TIME, Verdict.GO, WEATHER,
                BriefingSlot.TideInfo.NONE, List.of("Tide aligned"));

        @Test
        @DisplayName("Sets all four Claude fields")
        void setsAllFourFields() {
            BriefingSlot enriched = base.withClaudeScores(4, 78, 52, "Dramatic light expected.");

            assertThat(enriched.claudeRating()).isEqualTo(4);
            assertThat(enriched.fierySkyPotential()).isEqualTo(78);
            assertThat(enriched.goldenHourPotential()).isEqualTo(52);
            assertThat(enriched.claudeSummary()).isEqualTo("Dramatic light expected.");
        }

        @Test
        @DisplayName("Parameter ordering: rating is first, not swapped with fierySky")
        void parameterOrdering_ratingNotSwappedWithFierySky() {
            BriefingSlot enriched = base.withClaudeScores(3, 85, 40, "test");

            // Kills mutation: swapping rating and fierySkyPotential params
            assertThat(enriched.claudeRating()).isEqualTo(3);
            assertThat(enriched.fierySkyPotential()).isEqualTo(85);
        }

        @Test
        @DisplayName("Parameter ordering: goldenHour is third, not swapped with fierySky")
        void parameterOrdering_goldenHourNotSwappedWithFierySky() {
            BriefingSlot enriched = base.withClaudeScores(4, 90, 30, "test");

            // Kills mutation: swapping fierySky and goldenHour params
            assertThat(enriched.fierySkyPotential()).isEqualTo(90);
            assertThat(enriched.goldenHourPotential()).isEqualTo(30);
        }

        @Test
        @DisplayName("Preserves all original (non-Claude) fields")
        void preservesOriginalFields() {
            BriefingSlot enriched = base.withClaudeScores(5, 95, 80, "Spectacular.");

            assertThat(enriched.locationName()).isEqualTo("Whitby");
            assertThat(enriched.solarEventTime()).isEqualTo(EVENT_TIME);
            assertThat(enriched.verdict()).isEqualTo(Verdict.GO);
            assertThat(enriched.weather()).isEqualTo(WEATHER);
            assertThat(enriched.tide()).isEqualTo(BriefingSlot.TideInfo.NONE);
            assertThat(enriched.flags()).containsExactly("Tide aligned");
        }

        @Test
        @DisplayName("Returns a new instance, not the same object")
        void returnsNewInstance() {
            BriefingSlot enriched = base.withClaudeScores(4, 70, 60, "Good.");

            assertThat(enriched).isNotSameAs(base);
        }

        @Test
        @DisplayName("Original slot is unchanged after withClaudeScores")
        void originalUnchanged() {
            base.withClaudeScores(4, 70, 60, "Good.");

            assertThat(base.claudeRating()).isNull();
            assertThat(base.fierySkyPotential()).isNull();
        }
    }

    @Nested
    @DisplayName("displayVerdict")
    class DisplayVerdictTests {

        @Test
        @DisplayName("7-arg constructor reads an unrated GO/MARGINAL slot as UNRATED, not as a quality "
                + "verdict (unrated-verdict-plan.md U1)")
        void fallsBackToTriageVerdict() {
            BriefingSlot marginal = new BriefingSlot(
                    "Durham", EVENT_TIME, Verdict.MARGINAL, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of());
            BriefingSlot go = new BriefingSlot(
                    "Durham", EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of());

            assertThat(marginal.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
            assertThat(go.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
        }

        @Test
        @DisplayName("canopySlot with a GO weather verdict and no rating reads UNRATED (U1, review F9c)")
        void canopySlot_goWithNoRatingIsUnrated() {
            BriefingSlot wood = BriefingSlot.canopySlot(
                    "Houghall Woods", EVENT_TIME, Verdict.GO, WEATHER, List.of("Mist"));

            assertThat(wood.canopy()).isTrue();
            assertThat(wood.claudeRating()).isNull();
            assertThat(wood.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
        }

        @Test
        @DisplayName("withClaudeScores with a null rating on a GO slot reads UNRATED, not the triage "
                + "pass as a quality word (U1, review F9c)")
        void withClaudeScores_nullRatingOnGoSlotIsUnrated() {
            BriefingSlot base = new BriefingSlot(
                    "Bamburgh", EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of());

            BriefingSlot enriched = base.withClaudeScores(null, 40, 35, "Unrated summary.");

            assertThat(enriched.claudeRating()).isNull();
            assertThat(enriched.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
        }

        @Test
        @DisplayName("withClaudeScores recomputes displayVerdict from new rating")
        void recomputesOnEnrichment() {
            BriefingSlot base = new BriefingSlot(
                    "Whitby", EVENT_TIME, Verdict.STANDDOWN, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of());
            assertThat(base.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);

            BriefingSlot enriched = base.withClaudeScores(5, 90, 80, "Fire.");

            // Claude rating wins over the triage STANDDOWN
            assertThat(enriched.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        @DisplayName("withClaudeScores on GO slot with rating 2 yields STAND_DOWN")
        void claudeLowerGradesDowngrade() {
            BriefingSlot base = new BriefingSlot(
                    "Bamburgh", EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of());

            BriefingSlot enriched = base.withClaudeScores(2, 30, 25, "Poor.");

            assertThat(enriched.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        }
    }

    @Nested
    @DisplayName("eclipse — the per-location dawn/dusk race sight")
    class EclipseTests {

        private final BriefingSlot.EclipseSight sight = new BriefingSlot.EclipseSight(
                "LUNAR_ECLIPSE", 8, 241, "WSW",
                LocalDateTime.of(2026, 8, 28, 5, 12), LocalDateTime.of(2026, 8, 28, 3, 33),
                LocalDateTime.of(2026, 8, 28, 6, 52), LocalDateTime.of(2026, 8, 28, 6, 18),
                LocalDateTime.of(2026, 8, 27, 20, 30), true, false, "DAWN",
                List.of(new BriefingSlot.LightStop("NAUTICAL_DAWN",
                        LocalDateTime.of(2026, 8, 28, 3, 10))));

        @Test
        @DisplayName("every constructor starts it null — no lunar eclipse until withEclipse attaches one")
        void constructors_startNull() {
            assertThat(new BriefingSlot("Durham", EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of()).eclipse()).isNull();
            assertThat(BriefingSlot.canopySlot("Wood", EVENT_TIME, Verdict.GO, WEATHER,
                    List.of()).eclipse()).isNull();
        }

        @Test
        @DisplayName("withEclipse attaches it, changing nothing else, and null clears it")
        void withEclipse_isolated() {
            BriefingSlot base = new BriefingSlot("Dunstanburgh", EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of("Clear"));

            BriefingSlot carrying = base.withEclipse(sight);

            assertThat(carrying.eclipse()).isEqualTo(sight);
            assertThat(carrying.locationName()).isEqualTo("Dunstanburgh");
            assertThat(carrying.flags()).containsExactly("Clear");
            assertThat(carrying.withEclipse(null).eclipse()).isNull();
        }

        @Test
        @DisplayName("⚠️ withClaudeScores carries it — the same wither-drops-a-field risk")
        void withClaudeScores_preservesEclipse() {
            BriefingSlot carrying = new BriefingSlot("Dunstanburgh", EVENT_TIME, Verdict.GO,
                    WEATHER, BriefingSlot.TideInfo.NONE, List.of())
                    .withEclipse(sight);

            BriefingSlot scored = carrying.withClaudeScores(4, 3, 70, 60, "Fire.", "Headline");

            assertThat(scored.eclipse())
                    .as("the legacy 17-arg constructor must never be the one withClaudeScores calls")
                    .isEqualTo(sight);
            assertThat(scored.claudeRating()).isEqualTo(4);
            assertThat(carrying.withClaudeScores(4, 70, 60, "Fire.").eclipse()).isEqualTo(sight);
        }

        @Test
        @DisplayName("EclipseSight normalises a null stops list to empty, never null")
        void eclipseSight_normalisesNullStops() {
            BriefingSlot.EclipseSight noStops = new BriefingSlot.EclipseSight(
                    "LUNAR_ECLIPSE", 8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 5, 12),
                    LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 52),
                    null, null, false, false, null, null);

            assertThat(noStops.stops()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("couldCarryRating — the one coverage-denominator predicate")
    class CouldCarryRating {

        private final BriefingSlot sky = new BriefingSlot("Bamburgh", EVENT_TIME, Verdict.GO,
                WEATHER, BriefingSlot.TideInfo.NONE, List.of());
        private final BriefingSlot wood = BriefingSlot.canopySlot("Wood", EVENT_TIME, Verdict.GO,
                WEATHER, List.of());

        @Test
        @DisplayName("an unscored open-sky slot could — it is what the batch scores")
        void unscoredSky_could() {
            assertThat(sky.couldCarryRating()).isTrue();
        }

        @Test
        @DisplayName("an unscored wood could not — it never expected a sky rating")
        void unscoredWood_couldNot() {
            assertThat(wood.couldCarryRating()).isFalse();
        }

        @Test
        @DisplayName("a rating present is proof, whatever else the slot is")
        void rated_alwaysCould() {
            assertThat(wood.withClaudeScores(4, 70, 60, "Bluebells.").couldCarryRating()).isTrue();
        }
    }
}
