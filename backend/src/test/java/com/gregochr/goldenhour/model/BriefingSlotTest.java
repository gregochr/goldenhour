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
    @DisplayName("Convenience constructor (7-arg)")
    class ConvenienceConstructorTests {

        @Test
        @DisplayName("Claude fields default to null")
        void claudeFieldsAreNull() {
            BriefingSlot slot = new BriefingSlot(
                    "Durham", EVENT_TIME, Verdict.GO, WEATHER,
                    BriefingSlot.TideInfo.NONE, List.of("Clear"), null);

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
                    BriefingSlot.TideInfo.NONE, List.of("Building cloud"), "High cloud");

            assertThat(slot.locationName()).isEqualTo("Bamburgh");
            assertThat(slot.solarEventTime()).isEqualTo(EVENT_TIME);
            assertThat(slot.verdict()).isEqualTo(Verdict.MARGINAL);
            assertThat(slot.standdownReason()).isEqualTo("High cloud");
            assertThat(slot.flags()).containsExactly("Building cloud");
        }
    }

    @Nested
    @DisplayName("withClaudeScores()")
    class WithClaudeScoresTests {

        private final BriefingSlot base = new BriefingSlot(
                "Whitby", EVENT_TIME, Verdict.GO, WEATHER,
                BriefingSlot.TideInfo.NONE, List.of("Tide aligned"), null);

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
            assertThat(enriched.standdownReason()).isNull();
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
}
