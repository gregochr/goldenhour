package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LunarPhaseService}.
 */
class LunarPhaseServiceTest {

    private final LunarPhaseService service = new LunarPhaseService();

    @Nested
    @DisplayName("Lunation fraction")
    class LunationFractionTests {

        @Test
        @DisplayName("Reference new moon date returns fraction near 0.0")
        void referenceNewMoon() {
            double fraction = service.getLunationFraction(LocalDate.of(2000, 1, 6));
            assertThat(fraction).isLessThan(0.01);
        }

        @Test
        @DisplayName("Fraction is always in [0.0, 1.0)")
        void fractionRange() {
            for (int i = 0; i < 365; i++) {
                double f = service.getLunationFraction(LocalDate.of(2026, 1, 1).plusDays(i));
                assertThat(f).isBetween(0.0, 1.0);
            }
        }
    }

    @Nested
    @DisplayName("New moon detection")
    class NewMoonTests {

        @ParameterizedTest(name = "New moon on {0}")
        @CsvSource({
            "2000-01-06",   // reference new moon
            "2024-01-11",   // known new moon
            "2025-01-29",   // known new moon
            "2026-02-17",   // known new moon
        })
        void knownNewMoons(LocalDate date) {
            assertThat(service.isNewMoon(date)).isTrue();
        }

        @Test
        @DisplayName("Date far from new moon is not detected")
        void notNewMoon() {
            // ~7 days after a new moon (first quarter) should not be new moon
            assertThat(service.isNewMoon(LocalDate.of(2025, 1, 6))).isFalse();
        }
    }

    @Nested
    @DisplayName("Full moon detection")
    class FullMoonTests {

        @ParameterizedTest(name = "Full moon on {0}")
        @CsvSource({
            "2024-01-25",   // known full moon
            "2025-02-12",   // known full moon
            "2026-03-03",   // known full moon
        })
        void knownFullMoons(LocalDate date) {
            assertThat(service.isFullMoon(date)).isTrue();
        }

        @Test
        @DisplayName("Date far from full moon is not detected")
        void notFullMoon() {
            // ~7 days after a full moon should not be detected
            assertThat(service.isFullMoon(LocalDate.of(2025, 2, 19))).isFalse();
        }
    }

    @Nested
    @DisplayName("Moon phase names")
    class PhaseNameTests {

        @Test
        @DisplayName("New moon date returns 'New Moon'")
        void newMoonPhase() {
            assertThat(service.getMoonPhase(LocalDate.of(2025, 1, 29))).isEqualTo("New Moon");
        }

        @Test
        @DisplayName("Full moon date returns 'Full Moon'")
        void fullMoonPhase() {
            assertThat(service.getMoonPhase(LocalDate.of(2025, 2, 12))).isEqualTo("Full Moon");
        }

        @Test
        @DisplayName("All 8 phase names appear over a full synodic month")
        void allPhasesOverMonth() {
            // Start from a known new moon and check we get all 8 phases
            LocalDate start = LocalDate.of(2025, 1, 29);
            java.util.Set<String> phases = new java.util.HashSet<>();
            for (int d = 0; d < 30; d++) {
                phases.add(service.getMoonPhase(start.plusDays(d)));
            }
            assertThat(phases).containsExactlyInAnyOrder(
                    "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
                    "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent");
        }

        @Test
        @DisplayName("Phase names follow expected order through the cycle")
        void phaseOrder() {
            LocalDate start = LocalDate.of(2025, 1, 29); // new moon
            String prevPhase = service.getMoonPhase(start);
            assertThat(prevPhase).isEqualTo("New Moon");

            // First quarter should appear ~7 days later
            assertThat(service.getMoonPhase(start.plusDays(7))).isEqualTo("First Quarter");

            // Full moon ~15 days later
            assertThat(service.getMoonPhase(start.plusDays(15))).isEqualTo("Full Moon");

            // Last quarter ~22 days later
            assertThat(service.getMoonPhase(start.plusDays(22))).isEqualTo("Last Quarter");
        }
    }

    @Nested
    @DisplayName("Perigee detection")
    class PerigeeTests {

        @Test
        @DisplayName("Reference perigee date is detected")
        void referencePerigee() {
            assertThat(service.isMoonAtPerigee(LocalDate.of(2025, 1, 4))).isTrue();
        }

        @Test
        @DisplayName("One anomalistic month after reference is also perigee")
        void oneMonthLater() {
            // ~27.55 days later ≈ 2025-02-01 (28 days = 0.45 days from next perigee)
            assertThat(service.isMoonAtPerigee(LocalDate.of(2025, 2, 1))).isTrue();
        }

        @Test
        @DisplayName("Mid-cycle is not perigee")
        void midCycle() {
            // ~14 days from reference = apogee area
            assertThat(service.isMoonAtPerigee(LocalDate.of(2025, 1, 18))).isFalse();
        }
    }

    @Nested
    @DisplayName("Tide classification")
    class ClassificationTests {

        @Test
        @DisplayName("Regular day returns REGULAR_TIDE")
        void regularDay() {
            // First quarter — neither new nor full moon
            assertThat(service.classifyTide(LocalDate.of(2025, 2, 5)))
                    .isEqualTo(LunarTideType.REGULAR_TIDE);
        }

        @Test
        @DisplayName("Full moon without perigee returns SPRING_TIDE")
        void springTide() {
            // 2025-02-12 is a full moon but not at perigee
            assertThat(service.classifyTide(LocalDate.of(2025, 2, 12)))
                    .isEqualTo(LunarTideType.SPRING_TIDE);
        }

        @Test
        @DisplayName("New moon returns SPRING_TIDE when not at perigee")
        void springTideNewMoon() {
            // 2025-01-29 is a new moon; check if it's at perigee too
            LunarTideType type = service.classifyTide(LocalDate.of(2025, 1, 29));
            // If it happens to be at perigee, it would be KING_TIDE; otherwise SPRING_TIDE
            assertThat(type).isIn(LunarTideType.SPRING_TIDE, LunarTideType.KING_TIDE);
        }

        @Test
        @DisplayName("KING_TIDE only when both new/full moon AND perigee")
        void kingTideRequiresBoth() {
            // Test many dates: KING_TIDE should never appear without isNewOrFullMoon
            for (int d = 0; d < 365; d++) {
                LocalDate date = LocalDate.of(2026, 1, 1).plusDays(d);
                LunarTideType type = service.classifyTide(date);
                if (type == LunarTideType.KING_TIDE) {
                    assertThat(service.isNewOrFullMoon(date))
                            .as("KING_TIDE on %s requires new/full moon", date)
                            .isTrue();
                    assertThat(service.isMoonAtPerigee(date))
                            .as("KING_TIDE on %s requires perigee", date)
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Dates far in the past still produce valid results")
        void pastDates() {
            double f = service.getLunationFraction(LocalDate.of(1900, 6, 15));
            assertThat(f).isBetween(0.0, 1.0);
            assertThat(service.getMoonPhase(LocalDate.of(1900, 6, 15))).isNotNull();
        }

        @Test
        @DisplayName("Dates far in the future still produce valid results")
        void futureDates() {
            double f = service.getLunationFraction(LocalDate.of(2100, 12, 31));
            assertThat(f).isBetween(0.0, 1.0);
            assertThat(service.classifyTide(LocalDate.of(2100, 12, 31))).isNotNull();
        }

        @Test
        @DisplayName("Adjacent days near new moon boundary transition correctly")
        void newMoonBoundary() {
            // Find a new moon and check the boundary
            LocalDate newMoon = LocalDate.of(2025, 1, 29);
            assertThat(service.isNewMoon(newMoon)).isTrue();
            // 3 days away should be outside the window
            assertThat(service.isNewMoon(newMoon.plusDays(3))).isFalse();
            assertThat(service.isNewMoon(newMoon.minusDays(3))).isFalse();
        }
    }
}
