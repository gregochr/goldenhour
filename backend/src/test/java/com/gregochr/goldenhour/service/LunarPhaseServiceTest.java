package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.solarutils.LunarCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LunarPhaseService}.
 *
 * <h2>These test the sky, not the model's memory of itself</h2>
 *
 * <p>The suite this replaces could not fail. Its two load-bearing assertions were
 * {@code getLunationFraction(REFERENCE_NEW_MOON) < 0.01} and
 * {@code isMoonAtPerigee(REFERENCE_PERIGEE)}, both of which are true <em>by construction</em> for
 * any epoch whatsoever — including the two wrong ones the service actually had. The perigee epoch
 * was 3.45 days off a real perigee and the test asserted it was one, so the suite was not merely
 * silent about the defect: it pinned it.
 *
 * <p>Every date below is therefore an <b>externally checkable astronomical event</b>, taken from an
 * independent Meeus implementation and corroborated by solar-utils agreeing with it to under an
 * hour. A test here fails when the code disagrees with the sky, which is the only disagreement that
 * matters.
 *
 * <p>The real {@link LunarCalculator} is used rather than a mock. It is a pure function of time with
 * no I/O, and mocking it would put the ephemeris back inside the test — which is how the previous
 * suite came to be checking arithmetic against itself.
 */
class LunarPhaseServiceTest {

    private final LunarPhaseService service = new LunarPhaseService(new LunarCalculator());

    // Verified events (UTC). Meeus, cross-checked against solar-utils to within ~0.3 h.
    private static final LocalDate NEW_MOON_2025_01 = LocalDate.of(2025, 1, 29);   // 12:38
    private static final LocalDate FULL_MOON_2025_02 = LocalDate.of(2025, 2, 12);  // 13:55
    private static final LocalDate NEW_MOON_2026_01 = LocalDate.of(2026, 1, 18);   // 19:54
    private static final LocalDate FULL_MOON_2026_01 = LocalDate.of(2026, 1, 3);   // 10:05
    private static final LocalDate PERIGEE_2025_01 = LocalDate.of(2025, 1, 7);     // 23:19

    @Nested
    @DisplayName("perigee — the epoch that was wrong, and the test that said it was right")
    class PerigeeTests {

        @Test
        @DisplayName("2025-01-04 is NOT at perigee — the old REFERENCE_PERIGEE was 3.45 days out")
        void theOldReferenceEpochIsNotAPerigee() {
            // The previous suite asserted the opposite, and passed, because the service measured
            // perigee by counting mean anomalistic months from this very date. The real perigee was
            // 2025-01-07 23:19 UTC. This assertion is the whole defect in one line: if someone
            // reintroduces epoch arithmetic anchored here, this fails.
            assertThat(service.isMoonAtPerigee(LocalDate.of(2025, 1, 4))).isFalse();
            assertThat(service.daysFromNearestPerigee(LocalDate.of(2025, 1, 4)))
                    .as("2025-01-04 is over three days from the true perigee")
                    .isGreaterThan(3.0);
        }

        @Test
        @DisplayName("the true perigee date is at perigee")
        void theTruePerigeeIsFound() {
            assertThat(service.daysFromNearestPerigee(PERIGEE_2025_01)).isLessThan(0.6);
            assertThat(service.isMoonAtPerigee(PERIGEE_2025_01)).isTrue();
        }

        @Test
        @DisplayName("perigee distance is never negative and never more than half the longest "
                + "anomalistic month")
        void perigeeDistanceIsBounded() {
            // The anomalistic month runs 24.6-28.6 days — it is NOT the constant 27.55 the old
            // implementation assumed, which is half of why it drifted. Half the longest is 14.3.
            for (int offset = 0; offset < 60; offset++) {
                LocalDate date = LocalDate.of(2026, 3, 1).plusDays(offset);
                assertThat(service.daysFromNearestPerigee(date))
                        .as("perigee distance on %s", date)
                        .isBetween(0.0, 14.4);
            }
        }

        @Test
        @DisplayName("isMoonAtPerigee agrees with daysFromNearestPerigee at the window")
        void windowAndDistanceAgree() {
            for (int offset = 0; offset < 40; offset++) {
                LocalDate date = LocalDate.of(2026, 3, 1).plusDays(offset);
                boolean within = service.daysFromNearestPerigee(date)
                        <= LunarPhaseService.PERIGEE_WINDOW_DAYS;
                assertThat(service.isMoonAtPerigee(date)).isEqualTo(within);
            }
        }
    }

    @Nested
    @DisplayName("syzygy detection")
    class SyzygyTests {

        @Test
        @DisplayName("true new moons are detected")
        void newMoonsAreDetected() {
            assertThat(service.isNewMoon(NEW_MOON_2025_01)).isTrue();
            assertThat(service.isNewMoon(NEW_MOON_2026_01)).isTrue();
        }

        @Test
        @DisplayName("true full moons are detected, and are not mistaken for new moons")
        void fullMoonsAreDetected() {
            assertThat(service.isFullMoon(FULL_MOON_2025_02)).isTrue();
            assertThat(service.isFullMoon(FULL_MOON_2026_01)).isTrue();
            assertThat(service.isNewMoon(FULL_MOON_2025_02)).isFalse();
        }

        @Test
        @DisplayName("three days from a new moon is outside the window")
        void awayFromSyzygyNothingFires() {
            assertThat(service.isNewMoon(NEW_MOON_2025_01.plusDays(3))).isFalse();
            assertThat(service.isNewMoon(NEW_MOON_2025_01.minusDays(3))).isFalse();
        }

        @Test
        @DisplayName("the nearest new moon is never more than half a synodic month away")
        void newMoonDistanceIsBounded() {
            // 14.8 days. The first cut sized the search window on the 14.765-day SYZYGY interval
            // instead, which is the gap between a new moon and the next FULL moon — so it threw on
            // the first date in mid-lunation. This is that bug's regression test.
            for (int offset = 0; offset < 40; offset++) {
                LocalDate date = LocalDate.of(2026, 6, 1).plusDays(offset);
                assertThat(service.daysFromNearestNewMoon(date))
                        .as("new moon distance on %s", date).isBetween(0.0, 14.9);
                assertThat(service.daysFromNearestFullMoon(date))
                        .as("full moon distance on %s", date).isBetween(0.0, 14.9);
            }
        }
    }

    @Nested
    @DisplayName("illumination")
    class IlluminationTests {

        @Test
        @DisplayName("a new moon is dark and a full moon is lit")
        void syzygyIlluminationIsRight() {
            assertThat(service.getIlluminationFraction(NEW_MOON_2025_01)).isLessThan(0.02);
            assertThat(service.getIlluminationFraction(FULL_MOON_2025_02)).isGreaterThan(0.98);
        }

        @Test
        @DisplayName("illumination stays within [0,1] across a whole lunation")
        void illuminationIsAFraction() {
            for (int offset = 0; offset < 60; offset++) {
                assertThat(service.getIlluminationFraction(LocalDate.of(2026, 1, 1).plusDays(offset)))
                        .isBetween(0.0, 1.0);
            }
        }

        @Test
        @DisplayName("illumination rises monotonically from new moon to full moon")
        void illuminationRisesToFull() {
            // The old implementation derived this from a mean-phase cosine, so it had the right
            // shape and the wrong timing — out by up to 7.1 percentage points, and on the wrong
            // side of the meteor strategies' 50% gate on 26 days in three years. Monotonicity from
            // new to full is a property the true curve has and a mistimed one still has, so this
            // is deliberately paired with the exact-date assertions above rather than replacing them.
            double previous = -1;
            for (int day = 0; day <= 13; day++) {
                double illumination = service.getIlluminationFraction(NEW_MOON_2026_01.plusDays(day));
                assertThat(illumination).as("day %d after new moon", day).isGreaterThan(previous);
                previous = illumination;
            }
        }
    }

    @Nested
    @DisplayName("phase names")
    class PhaseNameTests {

        @Test
        @DisplayName("syzygy dates are named New Moon and Full Moon")
        void syzygyPhasesAreNamed() {
            assertThat(service.getMoonPhase(NEW_MOON_2025_01)).isEqualTo("New Moon");
            assertThat(service.getMoonPhase(FULL_MOON_2025_02)).isEqualTo("Full Moon");
        }

        @Test
        @DisplayName("all eight phase names appear across one synodic month")
        void allEightPhasesAppear() {
            Set<String> phases = new HashSet<>();
            for (int day = 0; day < 30; day++) {
                phases.add(service.getMoonPhase(NEW_MOON_2026_01.plusDays(day)));
            }
            assertThat(phases).containsExactlyInAnyOrder(
                    "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
                    "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent");
        }

        @Test
        @DisplayName("the quarters fall where they should through the cycle")
        void phasesFollowTheCycle() {
            assertThat(service.getMoonPhase(NEW_MOON_2026_01.plusDays(7))).isEqualTo("First Quarter");
            assertThat(service.getMoonPhase(NEW_MOON_2026_01.plusDays(15))).isEqualTo("Full Moon");
            assertThat(service.getMoonPhase(NEW_MOON_2026_01.plusDays(22))).isEqualTo("Last Quarter");
        }
    }

    @Nested
    @DisplayName("tide classification — the lunar axis")
    class ClassificationTests {

        @Test
        @DisplayName("a date far from syzygy is a regular tide")
        void regularDay() {
            assertThat(service.classifyTide(NEW_MOON_2026_01.plusDays(7)))
                    .isEqualTo(LunarTideType.REGULAR_TIDE);
        }

        @Test
        @DisplayName("a syzygy far from perigee is a spring tide, not a king tide")
        void springNotKing() {
            // 2025-02-12 is a full moon; the nearest perigee is well outside the window.
            assertThat(service.classifyTide(FULL_MOON_2025_02))
                    .isEqualTo(LunarTideType.SPRING_TIDE);
        }

        @Test
        @DisplayName("a perigean spring is a king tide, on every day of its run")
        void perigeanSpringIsKing() {
            // New moon 2026-06-15 02:56, perigee 0.15 days away — about as unambiguous as a
            // perigean spring gets. The old implementation called this SPRING: its perigee estimate
            // for mid-2026 was days out, and it found only one king tide in the whole of the
            // twelve months from August 2026, on a date that was not the closest coincidence.
            assertThat(service.classifyTide(LocalDate.of(2026, 6, 15)))
                    .isEqualTo(LunarTideType.KING_TIDE);
            // The other day of the run carries the same label — that is the per-event rule. The
            // run is these two days: midday on the 16th is 1.38 days past the syzygy, outside the
            // 1.0-day window, so it is a regular tide rather than the run's third day.
            assertThat(service.classifyTide(LocalDate.of(2026, 6, 14)))
                    .isEqualTo(LunarTideType.KING_TIDE);
            assertThat(service.classifyTide(LocalDate.of(2026, 6, 16)))
                    .isEqualTo(LunarTideType.REGULAR_TIDE);
        }

        @Test
        @DisplayName("a syzygy just outside the perigee window is spring, not king")
        void justOutsideThePerigeeWindowIsSpring() {
            // Full moon 2026-01-03 10:05, perigee 2026-01-01 21:45 — 1.51 days apart, a hair over
            // PERIGEE_WINDOW_DAYS. Pinned deliberately: it is the nearest miss in the period, so it
            // is where a sloppy comparison or a per-date perigee test would show up first. Measured
            // per DATE rather than per event, 2026-01-02 does qualify, and the run would then be
            // part king and part spring.
            assertThat(service.classifyTide(LocalDate.of(2026, 1, 2)))
                    .isEqualTo(LunarTideType.SPRING_TIDE);
        }

        @Test
        @DisplayName("a run is labelled consistently — no run is part spring and part king")
        void runsAreNotSplitAcrossLabels() {
            // King-ness belongs to the EVENT, so it is measured from the syzygy instant rather than
            // from each date. Measured per-date instead, the days on the perigee side of a syzygy
            // can qualify while the far side does not, which labels one tidal event two ways across
            // its own days — the exact self-contradiction the tide surfaces keep having removed.
            LunarTideType runLabel = null;
            for (int offset = 0; offset < 1096; offset++) {
                LocalDate date = LocalDate.of(2026, 1, 1).plusDays(offset);
                LunarTideType type = service.classifyTide(date);
                if (type == LunarTideType.REGULAR_TIDE) {
                    runLabel = null;
                    continue;
                }
                if (runLabel == null) {
                    runLabel = type;
                }
                assertThat(type).as("run containing %s changes label mid-run", date)
                        .isEqualTo(runLabel);
            }
        }

        @Test
        @DisplayName("king tides fire 5-10 times a year, which is what the pill copy claims")
        void kingTideFrequencyMatchesTheCopy() {
            // KingTideHotTopicStrategy tells the reader king tides happen "5-10 times per year".
            // With the old 0.5-day window against a perigee estimate that could be 4.65 days out,
            // the real figure was one run a year. This pins the claim to the behaviour, so widening
            // or narrowing PERIGEE_WINDOW_DAYS cannot silently make the copy a lie.
            int runs = 0;
            LunarTideType previous = LunarTideType.REGULAR_TIDE;
            for (int offset = 0; offset < 1096; offset++) {
                LunarTideType type = service.classifyTide(LocalDate.of(2026, 1, 1).plusDays(offset));
                if (type == LunarTideType.KING_TIDE && previous != LunarTideType.KING_TIDE) {
                    runs++;
                }
                previous = type;
            }
            assertThat(runs / 3.0).isBetween(5.0, 10.0);
        }

        @Test
        @DisplayName("every spring-or-king run sits on a syzygy, and there is one per syzygy")
        void oneRunPerSyzygy() {
            // ~24.7 syzygies a year, so ~25 runs. Materially fewer means the window is missing
            // events; materially more means it is firing away from syzygy altogether.
            int runs = 0;
            boolean inRun = false;
            for (int offset = 0; offset < 1096; offset++) {
                LocalDate date = LocalDate.of(2026, 1, 1).plusDays(offset);
                boolean qualifying = service.classifyTide(date) != LunarTideType.REGULAR_TIDE;
                if (qualifying && !inRun) {
                    runs++;
                    assertThat(service.isNewOrFullMoon(date))
                            .as("run starting %s is not on a syzygy", date).isTrue();
                }
                inRun = qualifying;
            }
            assertThat(runs / 3.0).isBetween(23.0, 27.0);
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("dates far outside the ephemeris era still answer")
        void distantDatesStillWork() {
            assertThat(service.getMoonPhase(LocalDate.of(1900, 6, 15))).isNotNull();
            assertThat(service.classifyTide(LocalDate.of(2100, 12, 31))).isNotNull();
            assertThat(service.getIlluminationFraction(LocalDate.of(1900, 6, 15)))
                    .isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("repeated calls are consistent, so memoisation cannot skew a result")
        void memoisationIsTransparent() {
            LocalDate date = LocalDate.of(2026, 5, 20);
            double first = service.daysFromNearestPerigee(date);
            for (int i = 0; i < 5; i++) {
                assertThat(service.daysFromNearestPerigee(date)).isEqualTo(first);
            }
        }
    }
}
