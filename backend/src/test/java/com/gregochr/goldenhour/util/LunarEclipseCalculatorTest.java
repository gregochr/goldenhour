package com.gregochr.goldenhour.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.model.LunarEclipseSight;
import com.gregochr.goldenhour.service.LunarEclipseCatalog;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.Kind;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPosition;
import com.gregochr.solarutils.LunarPhase;
import com.gregochr.solarutils.MoonriseMoonset;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link LunarEclipseCalculator} — the per-location geometry (altitude/bearing at maximum,
 * moonrise/moonset interaction, the 3°/30-minute eligibility rule).
 *
 * <p>Two kinds of test live here, deliberately separated. The {@link RealEphemeris} group uses the
 * genuine {@code solar-utils} calculators against the real seeded catalogue and checks the results
 * against figures published or derivable independently of this codebase — the same discipline
 * {@code EclipseCalculatorTest} applies to the solar reduction. The {@link EligibilityBoundary}
 * group replaces the calculators with mocks that report an exact, controlled altitude sequence, so
 * the 3°/30-minute rule can be pinned at its exact boundary the way no real eclipse conveniently
 * sits at.
 */
class LunarEclipseCalculatorTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * Absolute gap in whole minutes between two {@link LocalDateTime}s — used to compare a
     * library-computed instant against an expected figure with a minute-scale tolerance rather than
     * exact-second equality, since the exact instant is the real {@code solar-utils} calculators'
     * own output, not a value this test derives independently.
     */
    private static long minutesBetween(LocalDateTime a, LocalDateTime b) {
        return Math.abs(Duration.between(a, b).toMinutes());
    }

    @Nested
    @DisplayName("Real ephemeris, checked against independently derivable figures")
    class RealEphemeris {

        private final LunarEclipseCalculator calculator =
                new LunarEclipseCalculator(new LunarCalculator(), new MoonriseMoonsetCalculator());

        private LunarEclipse on(LocalDate date) {
            return LunarEclipseCatalog.on(date).orElseThrow(() -> new AssertionError("not catalogued: " + date));
        }

        @Test
        @DisplayName("2026-08-28 at Dunstanburgh matches the design's own worked figures")
        void dunstanburghWorkedExample() {
            // Design bundle: "moon 7 deg up WSW at max, sets 06:16 still in shadow" (local/BST).
            // Published to +-2 deg / +-4 deg tolerance in the design; this build's own reduction
            // lands at 7.65 deg / 241 deg (WSW), well inside both bands.
            LunarEclipseSight sight = calculator.sight(on(LocalDate.of(2026, 8, 28)), 55.49, -1.59);

            assertThat(sight.moonAltAtMax()).isCloseTo(7, within(2));
            assertThat(sight.moonAzAtMax()).isCloseTo(244, within(4));
            assertThat(sight.moonAzCardinal()).isEqualTo("WSW");
            assertThat(sight.setsInShadow()).isTrue();
            assertThat(sight.moonset()).isNotNull();
            // Moonset there: this build's MoonriseMoonsetCalculator answer is ~06:18:46 BST — 3
            // minutes from the design's stated "06:16", both consistent with a moon setting while
            // the umbral phase (which runs to 06:52:09 BST = u4) is still under way. Asserted with a
            // minute-scale tolerance rather than exact-second equality — this is the LIBRARY's own
            // output, not a hand-derived figure, so pinning it to the second would make the test
            // brittle to a harmless sub-second change in solar-utils' internal bisection.
            assertThat(minutesBetween(sight.moonset(), LocalDateTime.of(2026, 8, 28, 6, 18, 46)))
                    .isLessThanOrEqualTo(1);
            assertThat(sight.visible()).isTrue();
        }

        @Test
        @DisplayName("the Dunstanburgh moonset agrees with an independent altitude-sign-change scan")
        void moonsetAgreesWithIndependentScan() {
            // Cross-checks MoonriseMoonsetCalculator against a second, independent method on the
            // same underlying LunarCalculator: a plain minute-by-minute scan for the altitude
            // crossing zero, rather than trusting the library's own internal bisection. This is
            // exactly the "MoonriseMoonsetCalculator's moonset lies within 10 min of the altitude
            // sign change" check the plan asks for.
            LunarCalculator lunarCalculator = new LunarCalculator();
            double lat = 55.49;
            double lon = -1.59;

            ZonedDateTime scanStart = ZonedDateTime.of(2026, 8, 28, 5, 0, 0, 0, LONDON);
            ZonedDateTime crossing = null;
            double previousAltitude = lunarCalculator.calculate(scanStart, lat, lon).altitude();
            for (int minute = 1; minute <= 120 && crossing == null; minute++) {
                ZonedDateTime t = scanStart.plusMinutes(minute);
                double altitude = lunarCalculator.calculate(t, lat, lon).altitude();
                if (previousAltitude > 0 && altitude <= 0) {
                    crossing = t;
                }
                previousAltitude = altitude;
            }

            assertThat(crossing).as("moon should set within the scanned window").isNotNull();

            LunarEclipseSight sight = calculator.sight(on(LocalDate.of(2026, 8, 28)), lat, lon);
            long gapMinutes = Math.abs(Duration.between(
                    crossing.toLocalDateTime(), sight.moonset()).toMinutes());
            assertThat(gapMinutes).as("moonset vs independent altitude-crossing scan").isLessThanOrEqualTo(10);
        }

        @Test
        @DisplayName("2028-12-31 rises in shadow at the UK reference point")
        void risesInShadowWorkedExample() {
            // U1 15:07:17 UTC, U4 18:36:53 UTC (both GMT locally, no BST on 31 Dec); moonrise at the
            // UK-centre reference point falls at 15:36:14 UTC/GMT, inside that window.
            LunarEclipseSight sight = calculator.sight(on(LocalDate.of(2028, 12, 31)), 54.5, -2.5);

            assertThat(sight.risesInShadow()).isTrue();
            assertThat(minutesBetween(sight.moonrise(), LocalDateTime.of(2028, 12, 31, 15, 36, 14)))
                    .isLessThanOrEqualTo(1);
            assertThat(sight.visibleUmbraStart()).isEqualTo(sight.moonrise());
            assertThat(sight.visible()).isTrue();
        }

        @Test
        @DisplayName("2025-03-14 sets in shadow at the UK reference point — a morning eclipse")
        void setsInShadowMorningExample() {
            LunarEclipseSight sight = calculator.sight(on(LocalDate.of(2025, 3, 14)), 54.5, -2.5);

            assertThat(sight.setsInShadow()).isTrue();
            assertThat(sight.visibleUmbraEnd()).isEqualTo(sight.moonset());
            assertThat(sight.visible()).isTrue();
        }

        @Test
        @DisplayName("2025-09-07 rises in shadow at the UK reference point — an evening eclipse")
        void risesInShadowEveningExample() {
            LunarEclipseSight sight = calculator.sight(on(LocalDate.of(2025, 9, 7)), 54.5, -2.5);

            assertThat(sight.risesInShadow()).isTrue();
            assertThat(sight.visible()).isTrue();
        }

        @Test
        @DisplayName("2029-12-20's umbral span crosses London midnight and still resolves correctly")
        void midnightCrossingSpanResolves() {
            // U1 is on the London civil date of 20 Dec, U4 on 21 Dec (00:29 GMT) -- this exercises
            // the two-civil-date lookup for real, even though at the UK-centre point the Moon
            // neither rises nor sets near the span (it is 43-58 deg up throughout), so neither flag
            // fires. The point of this test is that sight() returns a sane, non-throwing answer
            // rather than silently querying only one of the two dates.
            LunarEclipse eclipse = on(LocalDate.of(2029, 12, 20));
            assertThat(eclipse.u1().toLocalDate()).isEqualTo(LocalDate.of(2029, 12, 20));
            assertThat(eclipse.u4().toLocalDate()).isEqualTo(LocalDate.of(2029, 12, 21));

            LunarEclipseSight sight = calculator.sight(eclipse, 54.5, -2.5);

            assertThat(sight.visible()).isTrue();
            assertThat(sight.setsInShadow()).isFalse();
            assertThat(sight.risesInShadow()).isFalse();
            assertThat(sight.visibleUmbraStart()).isEqualTo(eclipse.u1());
            assertThat(sight.visibleUmbraEnd()).isEqualTo(eclipse.u4());
        }
    }

    @Nested
    @DisplayName("The 3-degree/30-minute eligibility rule, at its exact boundary")
    @ExtendWith(MockitoExtension.class)
    class EligibilityBoundary {

        private static final double LAT = 54.5;
        private static final double LON = -2.5;

        @Mock
        private LunarCalculator lunarCalculator;

        @Mock
        private MoonriseMoonsetCalculator moonriseMoonsetCalculator;

        private LunarEclipseCalculator calculator() {
            return new LunarEclipseCalculator(lunarCalculator, moonriseMoonsetCalculator);
        }

        /** Builds a synthetic partial eclipse with an arbitrary but internally consistent span. */
        private LunarEclipse syntheticEclipse(LocalDateTime u1, LocalDateTime max, LocalDateTime u4) {
            return new LunarEclipse(u1.toLocalDate(), Kind.PARTIAL, 0.5,
                    u1.minusMinutes(30), u1, null, max, null, u4, u4.plusMinutes(30), null, null);
        }

        /**
         * Every fixture in this nested class shares the same {@code u1} date (2030-01-01), so the
         * London civil date {@link LunarEclipseCalculator} will query is knowable — stub it exactly
         * rather than with {@code any()}.
         */
        private void noMoonEvents() {
            when(moonriseMoonsetCalculator.calculate(eq(LocalDate.of(2030, 1, 1)), eq(LAT), eq(LON), eq(LONDON)))
                    .thenReturn(new MoonriseMoonset(Optional.empty(), Optional.empty()));
        }

        private LunarPosition positionAt(double altitude) {
            return new LunarPosition(altitude, 200.0, 0.99, LunarPhase.FULL_MOON, 384000.0);
        }

        @Test
        @DisplayName("2.9 degrees for 40 minutes, never reaching 3 degrees, is invisible")
        void belowThresholdForFortyMinutesIsInvisible() {
            LocalDateTime u1 = LocalDateTime.of(2030, 1, 1, 1, 0);
            LocalDateTime u4 = u1.plusMinutes(40);
            LocalDateTime max = u1.plusMinutes(20);
            noMoonEvents();
            when(lunarCalculator.calculate(any(), eq(LAT), eq(LON))).thenReturn(positionAt(2.9));

            LunarEclipseSight sight = calculator().sight(syntheticEclipse(u1, max, u4), LAT, LON);

            assertThat(sight.visible()).isFalse();
        }

        @Test
        @DisplayName("3.1 degrees for a 29-minute streak (not at maximum) is invisible")
        void streakOfTwentyNineMinutesIsInvisible() {
            visibilityStreakTest(29, false);
        }

        @Test
        @DisplayName("3.1 degrees for a 30-minute streak (not at maximum) is visible")
        void streakOfThirtyMinutesIsVisible() {
            visibilityStreakTest(30, true);
        }

        /**
         * Builds a span where altitude is 3.1 degrees for exactly {@code streakMinutes} minutes
         * starting at u1, then drops to 1.0 degree for the rest of the span, including at maximum —
         * which is placed well clear of the streak so the "altitude at maximum" fast path cannot
         * fire and mask the streak-duration boundary being tested.
         */
        private void visibilityStreakTest(int streakMinutes, boolean expectedVisible) {
            LocalDateTime u1 = LocalDateTime.of(2030, 1, 1, 1, 0);
            LocalDateTime max = u1.plusMinutes(50);
            LocalDateTime u4 = u1.plusMinutes(60);
            noMoonEvents();
            when(lunarCalculator.calculate(any(), eq(LAT), eq(LON))).thenAnswer(invocation -> {
                ZonedDateTime t = invocation.getArgument(0);
                long minute = Duration.between(u1.atZone(ZoneOffset.UTC), t).toMinutes();
                double altitude = minute >= 0 && minute <= streakMinutes ? 3.1 : 1.0;
                return positionAt(altitude);
            });

            LunarEclipseSight sight = calculator().sight(syntheticEclipse(u1, max, u4), LAT, LON);

            assertThat(sight.visible()).isEqualTo(expectedVisible);
        }

        @Test
        @DisplayName("altitude at maximum alone, at or above 3 degrees, is sufficient")
        void altitudeAtMaximumAloneIsSufficient() {
            LocalDateTime u1 = LocalDateTime.of(2030, 1, 1, 1, 0);
            LocalDateTime max = u1.plusMinutes(5);
            LocalDateTime u4 = u1.plusMinutes(10);
            noMoonEvents();
            // Every sample below threshold except the single maximum instant itself.
            when(lunarCalculator.calculate(any(), eq(LAT), eq(LON))).thenAnswer(invocation -> {
                ZonedDateTime t = invocation.getArgument(0);
                boolean isMax = t.toLocalDateTime().equals(max);
                return positionAt(isMax ? 3.0 : 1.0);
            });

            LunarEclipseSight sight = calculator().sight(syntheticEclipse(u1, max, u4), LAT, LON);

            assertThat(sight.visible()).isTrue();
        }

        @Test
        @DisplayName("altitude just below 3 degrees at maximum, with no streak, is invisible")
        void justBelowThresholdAtMaximumIsInvisible() {
            LocalDateTime u1 = LocalDateTime.of(2030, 1, 1, 1, 0);
            LocalDateTime max = u1.plusMinutes(5);
            LocalDateTime u4 = u1.plusMinutes(10);
            noMoonEvents();
            when(lunarCalculator.calculate(any(), eq(LAT), eq(LON))).thenReturn(positionAt(2.99));

            LunarEclipseSight sight = calculator().sight(syntheticEclipse(u1, max, u4), LAT, LON);

            assertThat(sight.visible()).isFalse();
        }

        @Test
        @DisplayName("an azimuth just under 360 rounds to 0 (north), never to 360")
        void azimuthJustUnderFullCircleRoundsToZero() {
            // A raw azimuth of 359.6 degrees is already in [0, 360) before rounding, so normalising
            // first and rounding second would produce 360 -- a value PromptUtils.toCardinal() happens
            // to still read as north via its own modulo, but which sight.moonAzAtMax() itself would
            // wrongly report as 360 rather than 0. Rounding first and normalising the integer result
            // is what this pins.
            LocalDateTime u1 = LocalDateTime.of(2030, 1, 1, 1, 0);
            LocalDateTime max = u1.plusMinutes(5);
            LocalDateTime u4 = u1.plusMinutes(10);
            noMoonEvents();
            when(lunarCalculator.calculate(any(), eq(LAT), eq(LON)))
                    .thenReturn(new LunarPosition(1.0, 359.6, 0.99, LunarPhase.FULL_MOON, 384000.0));

            LunarEclipseSight sight = calculator().sight(syntheticEclipse(u1, max, u4), LAT, LON);

            assertThat(sight.moonAzAtMax()).isZero();
            assertThat(sight.moonAzCardinal()).isEqualTo("N");
        }
    }

    @Nested
    @DisplayName("A span crossing local midnight resolves moonset against the right civil date")
    @ExtendWith(MockitoExtension.class)
    class MidnightCrossing {

        private static final double LAT = 51.0;
        private static final double LON = 0.0;

        @Mock
        private LunarCalculator lunarCalculator;

        @Mock
        private MoonriseMoonsetCalculator moonriseMoonsetCalculator;

        @Test
        @DisplayName("a moonset on the SECOND civil date, inside the span, is found and reported")
        void moonsetOnTheLaterDateIsFound() {
            // Span: 2030-06-30 23:10 to 2030-07-01 01:40 London local (crosses midnight). A moonset
            // at 01:20 on 1 July, inside the span, must be found even though u1's own civil date is
            // 30 June -- checking only that date would miss it entirely.
            LocalDateTime u1Utc = LocalDateTime.of(2030, 6, 30, 22, 10); // 23:10 BST
            LocalDateTime maxUtc = LocalDateTime.of(2030, 6, 30, 23, 30); // 00:30 BST
            LocalDateTime u4Utc = LocalDateTime.of(2030, 7, 1, 0, 40); // 01:40 BST
            LunarEclipse eclipse = new LunarEclipse(u1Utc.toLocalDate(), Kind.PARTIAL, 0.5,
                    u1Utc.minusMinutes(30), u1Utc, null, maxUtc, null, u4Utc, u4Utc.plusMinutes(30), null, null);

            when(lunarCalculator.calculate(any(), eq(LAT), eq(LON)))
                    .thenReturn(new LunarPosition(1.0, 200.0, 0.99, LunarPhase.FULL_MOON, 384000.0));

            ZonedDateTime moonsetOnTheFirstDate = ZonedDateTime.of(
                    LocalDateTime.of(2030, 6, 30, 10, 0), LONDON); // outside the span, irrelevant
            ZonedDateTime moonsetOnTheSecondDate = ZonedDateTime.of(
                    LocalDateTime.of(2030, 7, 1, 1, 20), LONDON); // inside the span
            when(moonriseMoonsetCalculator.calculate(LocalDate.of(2030, 6, 30), LAT, LON, LONDON))
                    .thenReturn(new MoonriseMoonset(Optional.empty(), Optional.of(moonsetOnTheFirstDate)));
            when(moonriseMoonsetCalculator.calculate(LocalDate.of(2030, 7, 1), LAT, LON, LONDON))
                    .thenReturn(new MoonriseMoonset(Optional.empty(), Optional.of(moonsetOnTheSecondDate)));

            LunarEclipseSight sight =
                    new LunarEclipseCalculator(lunarCalculator, moonriseMoonsetCalculator).sight(eclipse, LAT, LON);

            assertThat(sight.setsInShadow()).isTrue();
            assertThat(sight.moonset()).isEqualTo(LocalDateTime.of(2030, 7, 1, 1, 20));
        }

        @Test
        @DisplayName("checking only the first civil date would have missed it")
        void onlyCheckingTheFirstDateWouldMiss() {
            // Same shape as above, but this time the ONLY relevant candidate lives on the second
            // date and the first date has nothing at all -- the case that most directly shows the
            // second lookup is load-bearing rather than defensive.
            LocalDateTime u1Utc = LocalDateTime.of(2030, 6, 30, 22, 10);
            LocalDateTime maxUtc = LocalDateTime.of(2030, 6, 30, 23, 30);
            LocalDateTime u4Utc = LocalDateTime.of(2030, 7, 1, 0, 40);
            LunarEclipse eclipse = new LunarEclipse(u1Utc.toLocalDate(), Kind.PARTIAL, 0.5,
                    u1Utc.minusMinutes(30), u1Utc, null, maxUtc, null, u4Utc, u4Utc.plusMinutes(30), null, null);

            when(lunarCalculator.calculate(any(), eq(LAT), eq(LON)))
                    .thenReturn(new LunarPosition(1.0, 200.0, 0.99, LunarPhase.FULL_MOON, 384000.0));

            when(moonriseMoonsetCalculator.calculate(LocalDate.of(2030, 6, 30), LAT, LON, LONDON))
                    .thenReturn(new MoonriseMoonset(Optional.empty(), Optional.empty()));
            ZonedDateTime moonsetOnTheSecondDate =
                    ZonedDateTime.of(LocalDateTime.of(2030, 7, 1, 0, 55), LONDON);
            when(moonriseMoonsetCalculator.calculate(LocalDate.of(2030, 7, 1), LAT, LON, LONDON))
                    .thenReturn(new MoonriseMoonset(Optional.empty(), Optional.of(moonsetOnTheSecondDate)));

            LunarEclipseSight sight =
                    new LunarEclipseCalculator(lunarCalculator, moonriseMoonsetCalculator).sight(eclipse, LAT, LON);

            assertThat(sight.setsInShadow()).isTrue();
            assertThat(sight.moonset()).isEqualTo(LocalDateTime.of(2030, 7, 1, 0, 55));
        }
    }
}
