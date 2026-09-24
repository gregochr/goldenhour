package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.LunarEclipseSight;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;
import com.gregochr.goldenhour.util.LunarEclipseCalculator;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link EclipseSightAssembler}.
 *
 * <p>Uses {@link LunarEclipseCatalog}'s real, catalogue-load-time-validated entries throughout,
 * the same discipline {@code LunarEclipseHotTopicStrategyTest} follows — the contact instants are
 * exactly what production reduces. {@link LunarEclipseCalculator} and {@link SolarService} are
 * mocked so every geometry and light-boundary figure in a test is a deliberately chosen value
 * rather than a hand-computed astronomical one; the one piece of real arithmetic exercised
 * unmocked is {@link LunarEclipseWording#toLondonLocal}'s UTC-to-London conversion, which
 * {@link LunarEclipseWordingTest} pins directly and most tests in this class reuse to compute
 * their own expected values rather than hand-converting BST offsets. The exception is
 * {@code RaceBoundary}'s exact-boundary tests, which need the OPPOSITE direction —a target
 * LONDON boundary, converted back to a UTC value to stub — and {@code toLondonLocal} has no
 * inverse to call for that; those two tests instead subtract a fixed, explicitly-commented one
 * hour (safe only because both the seeded eclipse's date and its boundary sit inside BST with no
 * DST transition between them, which August always is).
 */
@ExtendWith(MockitoExtension.class)
class EclipseSightAssemblerTest {

    /** 2026-08-28 deep partial — SUNRISE, max 04:12:52 UTC (05:12:52 BST); u1 02:33:21 UTC,
     * u4 05:52:09 UTC. The worked example throughout the plan. */
    private static final LocalDate SUNRISE_ECLIPSE_DAY = LocalDate.of(2026, 8, 28);

    /** 2028-12-31 total — SUNSET, max 16:52:01 UTC (GMT, no BST). */
    private static final LocalDate SUNSET_ECLIPSE_DAY = LocalDate.of(2028, 12, 31);

    /** A date with no catalogued lunar eclipse at all. */
    private static final LocalDate NO_ECLIPSE_DAY = LocalDate.of(2026, 9, 1);

    private static final double LAT = 55.6089;
    private static final double LON = -1.7188;

    @Mock
    private LunarEclipseCalculator calculator;

    @Mock
    private SolarService solarService;

    @Mock
    private HotTopicSimulationService simulationService;

    private EclipseSightAssembler assembler;

    @BeforeEach
    void setUp() {
        // "Today", for simulation parity, is a date well clear of every real catalogued entry.
        Clock clock = Clock.fixed(
                LocalDateTime.of(2026, 9, 24, 12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        assembler = new EclipseSightAssembler(calculator, solarService, simulationService, clock);
    }

    private static LocationEntity location(double lat, double lon) {
        return LocationEntity.builder()
                .id(1L).name("Bamburgh").lat(lat).lon(lon)
                .locationType(Set.of()).tideType(Set.of()).solarEventType(Set.of())
                .enabled(true).createdAt(LocalDateTime.now()).build();
    }

    private static LunarEclipse eclipseOn(LocalDate date) {
        return LunarEclipseCatalog.on(date).orElseThrow();
    }

    private static LunarEclipseSight visibleSight(int altAtMax, int azAtMax, String cardinal) {
        return new LunarEclipseSight(altAtMax, azAtMax, cardinal, null, null, false, false,
                LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2000, 1, 1, 1, 0), true);
    }

    /** Stubs every {@code SolarService} boundary method the assembler reads for a SUNRISE slot,
     * at {@link #LAT}/{@link #LON}. */
    private void stubDawnBoundaries(LocalDate date, LocalDateTime nauticalDawnUtc,
            LocalDateTime civilDawnUtc, LocalDateTime sunriseUtc, LocalDateTime goldenMorningEndUtc) {
        stubDawnBoundaries(LAT, LON, date, nauticalDawnUtc, civilDawnUtc, sunriseUtc, goldenMorningEndUtc);
    }

    /** Stubs every {@code SolarService} boundary method the assembler reads for a SUNRISE slot,
     * at the given coordinates — for the simulated path, which always queries Dunstanburgh's own
     * regardless of which location the sight ends up attached to. */
    private void stubDawnBoundaries(double lat, double lon, LocalDate date,
            LocalDateTime nauticalDawnUtc, LocalDateTime civilDawnUtc, LocalDateTime sunriseUtc,
            LocalDateTime goldenMorningEndUtc) {
        when(solarService.nauticalDawnUtc(eq(lat), eq(lon), eq(date))).thenReturn(nauticalDawnUtc);
        when(solarService.civilDawnUtc(eq(lat), eq(lon), eq(date))).thenReturn(civilDawnUtc);
        when(solarService.sunriseUtc(eq(lat), eq(lon), eq(date))).thenReturn(sunriseUtc);
        when(solarService.goldenBlueWindow(eq(lat), eq(lon), eq(date), eq(true)))
                .thenReturn(new SolarService.SolarWindow(
                        civilDawnUtc, sunriseUtc, sunriseUtc, goldenMorningEndUtc));
    }

    /** Stubs every {@code SolarService} boundary method the assembler reads for a SUNSET slot. */
    private void stubDuskBoundaries(LocalDate date, LocalDateTime goldenEveningStartUtc,
            LocalDateTime sunsetUtc, LocalDateTime civilDuskUtc, LocalDateTime nauticalDuskUtc) {
        when(solarService.sunsetUtc(eq(LAT), eq(LON), eq(date))).thenReturn(sunsetUtc);
        when(solarService.civilDuskUtc(eq(LAT), eq(LON), eq(date))).thenReturn(civilDuskUtc);
        when(solarService.nauticalDuskUtc(eq(LAT), eq(LON), eq(date))).thenReturn(nauticalDuskUtc);
        when(solarService.goldenBlueWindow(eq(LAT), eq(LON), eq(date), eq(false)))
                .thenReturn(new SolarService.SolarWindow(
                        sunsetUtc, civilDuskUtc, goldenEveningStartUtc, sunsetUtc));
    }

    @Nested
    @DisplayName("the eclipse's own window — attaches on the matching event type, null on the other")
    class OwnWindowGating {

        @Test
        @DisplayName("a SUNRISE eclipse attaches on the slot's SUNRISE window")
        void sunriseEclipse_attachesOnSunrise() {
            LunarEclipse eclipse = eclipseOn(SUNRISE_ECLIPSE_DAY);
            when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                    .thenReturn(visibleSight(8, 241, "WSW"));
            stubDawnBoundaries(SUNRISE_ECLIPSE_DAY,
                    LocalDateTime.of(2026, 8, 28, 2, 0), LocalDateTime.of(2026, 8, 28, 2, 35),
                    LocalDateTime.of(2026, 8, 28, 5, 6), LocalDateTime.of(2026, 8, 28, 5, 41));

            BriefingSlot.EclipseSight sight = assembler.forSlot(
                    location(LAT, LON), SUNRISE_ECLIPSE_DAY, TargetType.SUNRISE);

            assertThat(sight).isNotNull();
            assertThat(sight.type()).isEqualTo("LUNAR_ECLIPSE");
        }

        @Test
        @DisplayName("that SAME SUNRISE eclipse attaches NOTHING to the SUNSET window of the same day")
        void sunriseEclipse_attachesNothingOnSunset() {
            BriefingSlot.EclipseSight sight = assembler.forSlot(
                    location(LAT, LON), SUNRISE_ECLIPSE_DAY, TargetType.SUNSET);

            assertThat(sight).isNull();
            verifyNoInteractions(calculator);
        }

        @Test
        @DisplayName("a SUNSET eclipse attaches on the slot's SUNSET window, nothing on SUNRISE")
        void sunsetEclipse_attachesOnSunsetOnly() {
            LunarEclipse eclipse = eclipseOn(SUNSET_ECLIPSE_DAY);
            when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                    .thenReturn(visibleSight(20, 200, "SSW"));
            stubDuskBoundaries(SUNSET_ECLIPSE_DAY,
                    LocalDateTime.of(2028, 12, 31, 14, 30), LocalDateTime.of(2028, 12, 31, 16, 5),
                    LocalDateTime.of(2028, 12, 31, 16, 42), LocalDateTime.of(2028, 12, 31, 17, 17));

            assertThat(assembler.forSlot(location(LAT, LON), SUNSET_ECLIPSE_DAY, TargetType.SUNRISE))
                    .as("wrong window for this eclipse").isNull();
            assertThat(assembler.forSlot(location(LAT, LON), SUNSET_ECLIPSE_DAY, TargetType.SUNSET))
                    .isNotNull();
        }

        @Test
        @DisplayName("a date with no catalogued lunar eclipse attaches nothing — forSlot never "
                + "consults simulation at all")
        void noEclipse_attachesNothing() {
            assertThat(assembler.forSlot(location(LAT, LON), NO_ECLIPSE_DAY, TargetType.SUNRISE))
                    .isNull();
            verifyNoInteractions(calculator);
            verifyNoInteractions(simulationService);
        }
    }

    @Nested
    @DisplayName("real eclipse — geometry, unclipped umbra span, light stops and the race test")
    class RealEclipseGeometry {

        @Test
        @DisplayName("moon geometry and moonset/moonrise/setsInShadow/risesInShadow come straight "
                + "from LunarEclipseCalculator.sight, unchanged")
        void carriesTheCalculatorsGeometryUnchanged() {
            LunarEclipse eclipse = eclipseOn(SUNRISE_ECLIPSE_DAY);
            LocalDateTime moonset = LocalDateTime.of(2026, 8, 28, 6, 18);
            LunarEclipseSight rawSight = new LunarEclipseSight(8, 241, "WSW", moonset, null,
                    true, false, LocalDateTime.of(2026, 8, 28, 3, 33),
                    LocalDateTime.of(2026, 8, 28, 6, 18), true);
            when(calculator.sight(eq(eclipse), eq(LAT), eq(LON))).thenReturn(rawSight);
            stubDawnBoundaries(SUNRISE_ECLIPSE_DAY,
                    LocalDateTime.of(2026, 8, 28, 2, 0), LocalDateTime.of(2026, 8, 28, 2, 35),
                    LocalDateTime.of(2026, 8, 28, 5, 6), LocalDateTime.of(2026, 8, 28, 5, 41));

            BriefingSlot.EclipseSight sight = assembler.forSlot(
                    location(LAT, LON), SUNRISE_ECLIPSE_DAY, TargetType.SUNRISE);

            assertThat(sight.moonAltAtMax()).isEqualTo(8);
            assertThat(sight.moonAzAtMax()).isEqualTo(241);
            assertThat(sight.moonAzCardinal()).isEqualTo("WSW");
            assertThat(sight.moonset()).isEqualTo(moonset);
            assertThat(sight.moonrise()).isNull();
            assertThat(sight.setsInShadow()).isTrue();
            assertThat(sight.risesInShadow()).isFalse();
        }

        @Test
        @DisplayName("umbraStart/umbraEnd are the eclipse's own u1/u4 — NOT the calculator's "
                + "location-clipped visibleUmbraStart/End")
        void umbraSpanIsUnclipped() {
            LunarEclipse eclipse = eclipseOn(SUNRISE_ECLIPSE_DAY);
            // A location-clipped visible span narrower than [u1, u4] — the calculator's own
            // visibleUmbraStart/End, which EclipseSight must NOT use for umbraStart/umbraEnd.
            LunarEclipseSight clippedSight = new LunarEclipseSight(8, 241, "WSW",
                    LocalDateTime.of(2026, 8, 28, 4, 0), LocalDateTime.of(2026, 8, 27, 20, 0),
                    true, false,
                    LocalDateTime.of(2026, 8, 28, 3, 50) /* clipped, later than real u1 */,
                    LocalDateTime.of(2026, 8, 28, 4, 0) /* clipped, earlier than real u4 */,
                    true);
            when(calculator.sight(eq(eclipse), eq(LAT), eq(LON))).thenReturn(clippedSight);
            stubDawnBoundaries(SUNRISE_ECLIPSE_DAY,
                    LocalDateTime.of(2026, 8, 28, 2, 0), LocalDateTime.of(2026, 8, 28, 2, 35),
                    LocalDateTime.of(2026, 8, 28, 5, 6), LocalDateTime.of(2026, 8, 28, 5, 41));

            BriefingSlot.EclipseSight sight = assembler.forSlot(
                    location(LAT, LON), SUNRISE_ECLIPSE_DAY, TargetType.SUNRISE);

            assertThat(sight.umbraStart())
                    .as("must be the eclipse's own u1, not the clipped visible start")
                    .isEqualTo(LunarEclipseWording.toLondonLocal(eclipse.u1()));
            assertThat(sight.umbraEnd())
                    .as("must be the eclipse's own u4, not the clipped visible end")
                    .isEqualTo(LunarEclipseWording.toLondonLocal(eclipse.u4()));
        }

        @Test
        @DisplayName("maximum is the eclipse's own max, London local")
        void maximumIsLondonLocal() {
            LunarEclipse eclipse = eclipseOn(SUNRISE_ECLIPSE_DAY);
            when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                    .thenReturn(visibleSight(8, 241, "WSW"));
            stubDawnBoundaries(SUNRISE_ECLIPSE_DAY,
                    LocalDateTime.of(2026, 8, 28, 2, 0), LocalDateTime.of(2026, 8, 28, 2, 35),
                    LocalDateTime.of(2026, 8, 28, 5, 6), LocalDateTime.of(2026, 8, 28, 5, 41));

            BriefingSlot.EclipseSight sight = assembler.forSlot(
                    location(LAT, LON), SUNRISE_ECLIPSE_DAY, TargetType.SUNRISE);

            assertThat(sight.maximum()).isEqualTo(LunarEclipseWording.toLondonLocal(eclipse.max()));
        }

        @Test
        @DisplayName("SUNRISE stops carry exactly the four dawn keys, in order, London local")
        void sunriseStopsCarryTheFourDawnKeys() {
            LunarEclipse eclipse = eclipseOn(SUNRISE_ECLIPSE_DAY);
            when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                    .thenReturn(visibleSight(8, 241, "WSW"));
            LocalDateTime nauticalDawn = LocalDateTime.of(2026, 8, 28, 2, 0);
            LocalDateTime civilDawn = LocalDateTime.of(2026, 8, 28, 2, 35);
            LocalDateTime sunrise = LocalDateTime.of(2026, 8, 28, 5, 6);
            LocalDateTime goldenMorningEnd = LocalDateTime.of(2026, 8, 28, 5, 41);
            stubDawnBoundaries(SUNRISE_ECLIPSE_DAY, nauticalDawn, civilDawn, sunrise, goldenMorningEnd);

            List<BriefingSlot.LightStop> stops = assembler.forSlot(
                    location(LAT, LON), SUNRISE_ECLIPSE_DAY, TargetType.SUNRISE).stops();

            assertThat(stops).extracting(BriefingSlot.LightStop::key).containsExactly(
                    "NAUTICAL_DAWN", "CIVIL_DAWN", "SUNRISE", "GOLDEN_MORNING_END");
            assertThat(stops).extracting(BriefingSlot.LightStop::time).containsExactly(
                    LunarEclipseWording.toLondonLocal(nauticalDawn),
                    LunarEclipseWording.toLondonLocal(civilDawn),
                    LunarEclipseWording.toLondonLocal(sunrise),
                    LunarEclipseWording.toLondonLocal(goldenMorningEnd));
        }

        @Test
        @DisplayName("SUNSET stops carry exactly the four dusk keys, in order, London local")
        void sunsetStopsCarryTheFourDuskKeys() {
            LunarEclipse eclipse = eclipseOn(SUNSET_ECLIPSE_DAY);
            when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                    .thenReturn(visibleSight(20, 200, "SSW"));
            LocalDateTime goldenEveningStart = LocalDateTime.of(2028, 12, 31, 14, 30);
            LocalDateTime sunset = LocalDateTime.of(2028, 12, 31, 16, 5);
            LocalDateTime civilDusk = LocalDateTime.of(2028, 12, 31, 16, 42);
            LocalDateTime nauticalDusk = LocalDateTime.of(2028, 12, 31, 17, 17);
            stubDuskBoundaries(SUNSET_ECLIPSE_DAY, goldenEveningStart, sunset, civilDusk, nauticalDusk);

            List<BriefingSlot.LightStop> stops = assembler.forSlot(
                    location(LAT, LON), SUNSET_ECLIPSE_DAY, TargetType.SUNSET).stops();

            assertThat(stops).extracting(BriefingSlot.LightStop::key).containsExactly(
                    "GOLDEN_EVENING_START", "SUNSET", "CIVIL_DUSK", "NAUTICAL_DUSK");
            assertThat(stops).extracting(BriefingSlot.LightStop::time).containsExactly(
                    LunarEclipseWording.toLondonLocal(goldenEveningStart),
                    LunarEclipseWording.toLondonLocal(sunset),
                    LunarEclipseWording.toLondonLocal(civilDusk),
                    LunarEclipseWording.toLondonLocal(nauticalDusk));
        }

        @Nested
        @DisplayName("race — DAWN/DUSK/null, at the exact 60-minute boundary")
        class RaceBoundary {

            @Test
            @DisplayName("umbraEnd exactly 60 minutes before nautical dawn is NOT racing — the test "
                    + "is strictly-after, not at-or-after")
            void exactlySixtyMinutesBeforeDawnDoesNotRace() {
                // Real u4 05:52:09 UTC -> 06:52:09 BST. Nautical dawn stubbed to exactly one hour
                // later so umbraEnd == nauticalDawn - 60min, the ">" boundary's edge (not-after
                // fails the ">" test, so this must read null) — see the next test for the other
                // side of the same boundary.
                LunarEclipse eclipse = eclipseOn(SUNRISE_ECLIPSE_DAY);
                when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                        .thenReturn(visibleSight(8, 241, "WSW"));
                LocalDateTime umbraEndLondon = LunarEclipseWording.toLondonLocal(eclipse.u4());
                LocalDateTime nauticalDawnLondon = umbraEndLondon.plusMinutes(60);
                LocalDateTime nauticalDawnUtc = nauticalDawnLondon.minusHours(1); // BST -> UTC
                stubDawnBoundaries(SUNRISE_ECLIPSE_DAY, nauticalDawnUtc,
                        nauticalDawnUtc.plusMinutes(35), nauticalDawnUtc.plusHours(3),
                        nauticalDawnUtc.plusHours(3).plusMinutes(20));

                BriefingSlot.EclipseSight sight = assembler.forSlot(
                        location(LAT, LON), SUNRISE_ECLIPSE_DAY, TargetType.SUNRISE);

                assertThat(sight.race())
                        .as("umbraEnd == nauticalDawn - 60min is NOT strictly after it")
                        .isNull();
            }

            @Test
            @DisplayName("umbraEnd one minute later than the boundary races (DAWN)")
            void oneMinuteInsideTheBoundaryRaces() {
                LunarEclipse eclipse = eclipseOn(SUNRISE_ECLIPSE_DAY);
                when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                        .thenReturn(visibleSight(8, 241, "WSW"));
                LocalDateTime umbraEndLondon = LunarEclipseWording.toLondonLocal(eclipse.u4());
                LocalDateTime nauticalDawnLondon = umbraEndLondon.plusMinutes(59);
                LocalDateTime nauticalDawnUtc = nauticalDawnLondon.minusHours(1);
                stubDawnBoundaries(SUNRISE_ECLIPSE_DAY, nauticalDawnUtc,
                        nauticalDawnUtc.plusMinutes(35), nauticalDawnUtc.plusHours(3),
                        nauticalDawnUtc.plusHours(3).plusMinutes(20));

                BriefingSlot.EclipseSight sight = assembler.forSlot(
                        location(LAT, LON), SUNRISE_ECLIPSE_DAY, TargetType.SUNRISE);

                assertThat(sight.race()).isEqualTo("DAWN");
            }

            @Test
            @DisplayName("a high, leisurely eclipse (dawn far away) races nothing — null")
            void farFromDawnRacesNothing() {
                LunarEclipse eclipse = eclipseOn(SUNRISE_ECLIPSE_DAY);
                when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                        .thenReturn(visibleSight(45, 241, "WSW"));
                // Nautical dawn hours after u4 — no race.
                stubDawnBoundaries(SUNRISE_ECLIPSE_DAY,
                        LocalDateTime.of(2026, 8, 28, 8, 0), LocalDateTime.of(2026, 8, 28, 8, 35),
                        LocalDateTime.of(2026, 8, 28, 9, 6), LocalDateTime.of(2026, 8, 28, 9, 41));

                BriefingSlot.EclipseSight sight = assembler.forSlot(
                        location(LAT, LON), SUNRISE_ECLIPSE_DAY, TargetType.SUNRISE);

                assertThat(sight.race()).isNull();
            }

            @Test
            @DisplayName("a SUNSET eclipse whose umbraStart falls before nautical dusk + 60min races DUSK")
            void duskEclipseRaces() {
                LunarEclipse eclipse = eclipseOn(SUNSET_ECLIPSE_DAY);
                when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                        .thenReturn(visibleSight(20, 200, "SSW"));
                LocalDateTime umbraStartLondon = LunarEclipseWording.toLondonLocal(eclipse.u1());
                // Nautical dusk 30 minutes before umbraStart -> umbraStart < nauticalDusk + 60min.
                LocalDateTime nauticalDuskUtc = umbraStartLondon.minusMinutes(30); // GMT, no offset
                stubDuskBoundaries(SUNSET_ECLIPSE_DAY, nauticalDuskUtc.minusHours(2),
                        nauticalDuskUtc.minusMinutes(45), nauticalDuskUtc.minusMinutes(10),
                        nauticalDuskUtc);

                BriefingSlot.EclipseSight sight = assembler.forSlot(
                        location(LAT, LON), SUNSET_ECLIPSE_DAY, TargetType.SUNSET);

                assertThat(sight.race()).isEqualTo("DUSK");
            }

            @Test
            @DisplayName("DUSK's own boundary, mirroring DAWN's: umbraStart exactly 60 minutes "
                    + "after nautical dusk is NOT racing — strictly-before, not at-or-before")
            void exactlySixtyMinutesAfterDuskDoesNotRace() {
                LunarEclipse eclipse = eclipseOn(SUNSET_ECLIPSE_DAY);
                when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                        .thenReturn(visibleSight(20, 200, "SSW"));
                LocalDateTime umbraStartLondon = LunarEclipseWording.toLondonLocal(eclipse.u1());
                // GMT on 2028-12-31 (no BST) -- London local equals the UTC value directly.
                LocalDateTime nauticalDuskUtc = umbraStartLondon.minusMinutes(60);
                stubDuskBoundaries(SUNSET_ECLIPSE_DAY, nauticalDuskUtc.minusHours(2),
                        nauticalDuskUtc.minusMinutes(45), nauticalDuskUtc.minusMinutes(10),
                        nauticalDuskUtc);

                BriefingSlot.EclipseSight sight = assembler.forSlot(
                        location(LAT, LON), SUNSET_ECLIPSE_DAY, TargetType.SUNSET);

                assertThat(sight.race())
                        .as("umbraStart == nauticalDusk + 60min is NOT strictly before it")
                        .isNull();
            }

            @Test
            @DisplayName("DUSK one minute inside the boundary races")
            void oneMinuteInsideTheDuskBoundaryRaces() {
                LunarEclipse eclipse = eclipseOn(SUNSET_ECLIPSE_DAY);
                when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                        .thenReturn(visibleSight(20, 200, "SSW"));
                LocalDateTime umbraStartLondon = LunarEclipseWording.toLondonLocal(eclipse.u1());
                LocalDateTime nauticalDuskUtc = umbraStartLondon.minusMinutes(59);
                stubDuskBoundaries(SUNSET_ECLIPSE_DAY, nauticalDuskUtc.minusHours(2),
                        nauticalDuskUtc.minusMinutes(45), nauticalDuskUtc.minusMinutes(10),
                        nauticalDuskUtc);

                BriefingSlot.EclipseSight sight = assembler.forSlot(
                        location(LAT, LON), SUNSET_ECLIPSE_DAY, TargetType.SUNSET);

                assertThat(sight.race()).isEqualTo("DUSK");
            }

            @Test
            @DisplayName("a SUNSET eclipse far from nautical dusk races nothing — DUSK's own null case")
            void farFromDuskRacesNothing() {
                LunarEclipse eclipse = eclipseOn(SUNSET_ECLIPSE_DAY);
                when(calculator.sight(eq(eclipse), eq(LAT), eq(LON)))
                        .thenReturn(visibleSight(45, 200, "SSW"));
                LocalDateTime umbraStartLondon = LunarEclipseWording.toLondonLocal(eclipse.u1());
                // Nautical dusk five hours before umbraStart -- far outside the 60-minute window.
                LocalDateTime nauticalDuskUtc = umbraStartLondon.minusHours(5);
                stubDuskBoundaries(SUNSET_ECLIPSE_DAY, nauticalDuskUtc.minusHours(2),
                        nauticalDuskUtc.minusMinutes(45), nauticalDuskUtc.minusMinutes(10),
                        nauticalDuskUtc);

                BriefingSlot.EclipseSight sight = assembler.forSlot(
                        location(LAT, LON), SUNSET_ECLIPSE_DAY, TargetType.SUNSET);

                assertThat(sight.race()).isNull();
            }
        }
    }

    @Nested
    @DisplayName("forSlot NEVER simulates — the build-time seam attaches real sights only (#914)")
    class ForSlotNeverSimulates {

        private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

        @Test
        @DisplayName("forSlot returns null on a no-eclipse date, and never even asks "
                + "HotTopicSimulationService a question — whether or not simulation is active is "
                + "irrelevant, because forSlot no longer reads that collaborator at all")
        void forSlot_ignoresSimulationEntirely() {
            assertThat(assembler.forSlot(location(LAT, LON), TODAY, TargetType.SUNRISE)).isNull();
            verifyNoInteractions(simulationService);
            verifyNoInteractions(calculator);
        }
    }

    @Nested
    @DisplayName("simulatedSightFor — the SERVE-time overlay, gated on HotTopicSimulationService "
            + "(BriefingService.getCachedBriefing overlays this on every request; it is never "
            + "persisted into daily_briefing_cache — Codex review of #914)")
    class SimulatedSightForTests {

        private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
        private static final double DUNSTANBURGH_LAT = 55.49;
        private static final double DUNSTANBURGH_LON = -1.59;

        private void stubActive() {
            when(simulationService.isEnabled()).thenReturn(true);
            when(simulationService.getActiveTypes()).thenReturn(Set.of("LUNAR_ECLIPSE"));
        }

        @Test
        @DisplayName("re-dates the real 2026-08-28 Dunstanburgh reduction onto today's SUNRISE "
                + "window — geometry AND light stops both Dunstanburgh's own, re-dated, since a "
                + "serve-time overlay has no per-location coordinates to hand")
        void reDatesTheTemplateOntoToday() {
            stubActive();
            LunarEclipse template = eclipseOn(SUNRISE_ECLIPSE_DAY);
            LunarEclipseSight templateSight = new LunarEclipseSight(8, 241, "WSW",
                    LocalDateTime.of(2026, 8, 28, 6, 18), null, true, false,
                    LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 52), true);
            when(calculator.sight(eq(template), eq(DUNSTANBURGH_LAT), eq(DUNSTANBURGH_LON)))
                    .thenReturn(templateSight);
            // The simulated path queries Dunstanburgh's own light boundaries, never a per-slot
            // location's — see EclipseSightAssembler.buildSimulated's own javadoc.
            stubDawnBoundaries(DUNSTANBURGH_LAT, DUNSTANBURGH_LON, TODAY,
                    LocalDateTime.of(2026, 9, 24, 4, 0), LocalDateTime.of(2026, 9, 24, 4, 35),
                    LocalDateTime.of(2026, 9, 24, 6, 6), LocalDateTime.of(2026, 9, 24, 6, 41));

            BriefingSlot.EclipseSight sight = assembler.simulatedSightFor(TODAY, TargetType.SUNRISE);

            assertThat(sight).isNotNull();
            assertThat(sight.type()).isEqualTo("LUNAR_ECLIPSE");
            assertThat(sight.moonAltAtMax()).isEqualTo(8);
            assertThat(sight.moonAzCardinal()).isEqualTo("WSW");
            // Same time-of-day as the template, re-dated onto "today".
            assertThat(sight.maximum()).isEqualTo(TODAY.atTime(5, 12, 52));
            assertThat(sight.moonset()).isEqualTo(TODAY.atTime(6, 18));
            assertThat(sight.moonrise()).isNull();
            // The light stops queried Dunstanburgh's own coordinates, never a per-slot location's.
            verify(solarService).nauticalDawnUtc(eq(DUNSTANBURGH_LAT), eq(DUNSTANBURGH_LON), eq(TODAY));
        }

        @Test
        @DisplayName("never fires when simulation is disabled")
        void doesNothingWhenDisabled() {
            when(simulationService.isEnabled()).thenReturn(false);

            assertThat(assembler.simulatedSightFor(TODAY, TargetType.SUNRISE)).isNull();
        }

        @Test
        @DisplayName("never fires when LUNAR_ECLIPSE is not among the active simulated types")
        void doesNothingWhenTypeNotActive() {
            when(simulationService.isEnabled()).thenReturn(true);
            when(simulationService.getActiveTypes()).thenReturn(Set.of("ECLIPSE"));

            assertThat(assembler.simulatedSightFor(TODAY, TargetType.SUNRISE)).isNull();
        }

        @Test
        @DisplayName("never fires on the SUNSET window — the simulated template is SUNRISE only")
        void doesNothingOnSunset() {
            stubActive();

            assertThat(assembler.simulatedSightFor(TODAY, TargetType.SUNSET)).isNull();
        }

        @Test
        @DisplayName("never fires on a date other than today")
        void doesNothingOnAnyOtherDate() {
            stubActive();

            assertThat(assembler.simulatedSightFor(TODAY.plusDays(1), TargetType.SUNRISE)).isNull();
        }

        @Test
        @DisplayName("never fires on a date that already carries a REAL catalogued eclipse, even on "
                + "the SUNRISE window that eclipse's own SUNSET label leaves free — simulation must "
                + "never manufacture a second answer for a date the catalogue already answers")
        void doesNothingOnADateARealEclipseAlreadyOwns() {
            stubActive();

            assertThat(assembler.simulatedSightFor(SUNSET_ECLIPSE_DAY, TargetType.SUNRISE)).isNull();
        }

        @Test
        @DisplayName("isSimulationActiveForLunarEclipse is the cheap top-level gate BriefingService "
                + "checks before walking the whole response tree")
        void isSimulationActiveForLunarEclipse_reflectsBothFlags() {
            assertThat(assembler.isSimulationActiveForLunarEclipse())
                    .as("unstubbed: Mockito answers false/empty").isFalse();

            when(simulationService.isEnabled()).thenReturn(true);
            when(simulationService.getActiveTypes()).thenReturn(Set.of("ECLIPSE"));
            assertThat(assembler.isSimulationActiveForLunarEclipse())
                    .as("enabled, but LUNAR_ECLIPSE itself not active").isFalse();

            when(simulationService.getActiveTypes()).thenReturn(Set.of("LUNAR_ECLIPSE"));
            assertThat(assembler.isSimulationActiveForLunarEclipse()).isTrue();
        }
    }
}
