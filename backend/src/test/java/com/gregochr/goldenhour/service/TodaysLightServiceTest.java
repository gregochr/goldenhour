package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.TodaysLightResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The masthead's light rule.
 *
 * <p>The claims worth pinning are the ones a green render would not catch: the row's clock times
 * are on the <em>UK civil</em> clock rather than the UTC one {@link SolarService} returns, "today"
 * is the UK civil date rather than the UTC one, the gradient's positions are real enough that the
 * lit part genuinely narrows in winter, and the stop list is ascending — a descending one draws a
 * plausible picture from wrong numbers, because the browser silently repairs it.
 *
 * <p>{@link SolarService} is used for real, not mocked. It is a deterministic wrapper over
 * solar-utils, and the alternative — stubbing every solar time — would let the arithmetic under
 * test be verified against numbers this test invented.
 */
@ExtendWith(MockitoExtension.class)
class TodaysLightServiceTest {

    /** Alnwick, Northumberland — the handoff's own worked example. */
    private static final double LAT = 55.4130;
    private static final double LON = -1.7060;
    private static final String POSTCODE = "NE66 1NG";

    private static final LocalDate MIDSUMMER = LocalDate.of(2026, 6, 21);
    private static final LocalDate MIDWINTER = LocalDate.of(2026, 12, 21);

    private final SolarService solarService = new SolarService();

    @Mock
    private UserSettingsService settingsService;

    @Mock
    private Authentication auth;

    /** A service whose "now" is fixed, so no test's expectations depend on the day it runs. */
    private TodaysLightService serviceAt(String instant) {
        return new TodaysLightService(solarService, settingsService,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static UserSettingsService.HomeLocation home(Double lat, Double lon, String postcode) {
        return new UserSettingsService.HomeLocation(7L, lat, lon, 30, postcode);
    }

    /** The UK civil clock string for a UTC instant — the same conversion the service renders. */
    private static String clockAt(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(java.time.ZoneId.of("Europe/London"))
                .toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static double positionOf(TodaysLightResponse light, String key) {
        return light.stops().stream()
                .filter(s -> s.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stop named " + key))
                .position();
    }

    @Nested
    @DisplayName("Resolving the caller's home")
    class Resolution {

        @Test
        @DisplayName("No saved postcode is the empty state, not an error")
        void noHomeReturnsNull() {
            when(settingsService.getHomeLocation(auth)).thenReturn(home(null, null, null));

            assertThat(serviceAt("2026-06-21T09:00:00Z").getTodaysLight(auth)).isNull();
        }

        @Test
        @DisplayName("A postcode with no coordinates is also the empty state")
        void postcodeWithoutCoordinatesReturnsNull() {
            when(settingsService.getHomeLocation(auth)).thenReturn(home(null, null, POSTCODE));

            assertThat(serviceAt("2026-06-21T09:00:00Z").getTodaysLight(auth)).isNull();
        }

        @Test
        @DisplayName("The row is labelled with the stored postcode — never an unlabelled gradient")
        void labelNamesTheStoredPostcode() {
            when(settingsService.getHomeLocation(auth)).thenReturn(home(LAT, LON, POSTCODE));

            TodaysLightResponse light = serviceAt("2026-06-21T09:00:00Z").getTodaysLight(auth);

            assertThat(light.label()).isEqualTo("Home · NE66 1NG");
            assertThat(light.shortLabel()).isEqualTo("NE66 1NG");
        }

        @Test
        @DisplayName("\"Today\" is the UK civil date, so the late-BST hour draws tomorrow's light")
        void todayIsTheUkCivilDate() {
            when(settingsService.getHomeLocation(auth)).thenReturn(home(LAT, LON, POSTCODE));

            // 23:30 UTC on 20 June is 00:30 BST on the 21st. A UTC "today" would draw the 20th.
            TodaysLightResponse served = serviceAt("2026-06-20T23:30:00Z").getTodaysLight(auth);

            assertThat(served).isEqualTo(
                    serviceAt("2026-06-21T09:00:00Z").buildFor(LAT, LON, POSTCODE, MIDSUMMER));
        }
    }

    @Nested
    @DisplayName("The time row")
    class TimeRow {

        @Test
        @DisplayName("Clock times are UK civil, not the UTC SolarService returns")
        void timesAreOnTheUkCivilClock() {
            TodaysLightResponse light = serviceAt("2026-06-21T09:00:00Z")
                    .buildFor(LAT, LON, POSTCODE, MIDSUMMER);

            LocalDateTime sunriseUtc = solarService.sunriseUtc(LAT, LON, MIDSUMMER);
            // BST is UTC+1, so the rendered hour must be one ahead of the UTC one. Asserting the
            // difference rather than a literal keeps this a timezone test, not an ephemeris test.
            assertThat(light.sunrise())
                    .isEqualTo(String.format("%02d:%02d",
                            sunriseUtc.plusHours(1).getHour(), sunriseUtc.getMinute()));
        }

        @Test
        @DisplayName("In GMT the civil clock and the UTC one agree")
        void winterTimesMatchUtc() {
            TodaysLightResponse light = serviceAt("2026-12-21T09:00:00Z")
                    .buildFor(LAT, LON, POSTCODE, MIDWINTER);

            LocalDateTime sunsetUtc = solarService.sunsetUtc(LAT, LON, MIDWINTER);
            assertThat(light.sunset())
                    .isEqualTo(String.format("%02d:%02d", sunsetUtc.getHour(), sunsetUtc.getMinute()));
        }

        @Test
        @DisplayName("The four labelled times run in order across the day")
        void theFourTimesAreInOrder() {
            TodaysLightResponse light = serviceAt("2026-06-21T09:00:00Z")
                    .buildFor(LAT, LON, POSTCODE, MIDSUMMER);

            assertThat(List.of(light.civilDawn(), light.sunrise(), light.sunset(), light.civilDusk()))
                    .isSorted()
                    .allMatch(t -> t.matches("\\d{2}:\\d{2}"));
        }
    }

    @Nested
    @DisplayName("The gradient")
    class Gradient {

        @Test
        @DisplayName("Every stop is named, in event order, and spans the whole rule")
        void stopsAreNamedAndSpanTheRule() {
            TodaysLightResponse light = serviceAt("2026-06-21T09:00:00Z")
                    .buildFor(LAT, LON, POSTCODE, MIDSUMMER);

            assertThat(light.stops()).extracting(TodaysLightResponse.Stop::key).containsExactly(
                    "NIGHT_START", "NAUTICAL_DAWN", "CIVIL_DAWN", "SUNRISE", "GOLDEN_MORNING_END",
                    "SOLAR_NOON", "GOLDEN_EVENING_START", "SUNSET", "CIVIL_DUSK", "NAUTICAL_DUSK",
                    "NIGHT_END");
            assertThat(light.stops()).first()
                    .extracting(TodaysLightResponse.Stop::position).isEqualTo(0.0);
            assertThat(light.stops()).last()
                    .extracting(TodaysLightResponse.Stop::position).isEqualTo(100.0);
        }

        @Test
        @DisplayName("Positions never run backwards — the browser would hide it if they did")
        void positionsAreAscending() {
            for (LocalDate date : List.of(MIDSUMMER, MIDWINTER,
                    LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
                List<Double> positions = serviceAt("2026-06-21T09:00:00Z")
                        .buildFor(LAT, LON, POSTCODE, date).stops().stream()
                        .map(TodaysLightResponse.Stop::position).toList();

                assertThat(positions).as("stops on %s", date).isSorted();
                assertThat(positions).as("stops on %s", date).allMatch(p -> p >= 0 && p <= 100);
            }
        }

        @Test
        @DisplayName("The lit span is data: it narrows in winter and widens in summer")
        void thelitSpanFollowsTheSeason() {
            TodaysLightService service = serviceAt("2026-06-21T09:00:00Z");
            TodaysLightResponse summer = service.buildFor(LAT, LON, POSTCODE, MIDSUMMER);
            TodaysLightResponse winter = service.buildFor(LAT, LON, POSTCODE, MIDWINTER);

            double summerDay = positionOf(summer, "SUNSET") - positionOf(summer, "SUNRISE");
            double winterDay = positionOf(winter, "SUNSET") - positionOf(winter, "SUNRISE");

            // Northumberland midsummer is ~17h and midwinter ~7h, so this is a wide margin, not a
            // knife edge — the assertion fails only if the positions have stopped being computed.
            assertThat(summerDay).isGreaterThan(winterDay + 30);
        }

        @Test
        @DisplayName("Solar noon sits between the two golden hours")
        void solarNoonSitsBetweenTheGoldenHours() {
            TodaysLightResponse light = serviceAt("2026-06-21T09:00:00Z")
                    .buildFor(LAT, LON, POSTCODE, MIDSUMMER);

            assertThat(positionOf(light, "SOLAR_NOON"))
                    .isGreaterThan(positionOf(light, "GOLDEN_MORNING_END"))
                    .isLessThan(positionOf(light, "GOLDEN_EVENING_START"));
        }
    }

    @Nested
    @DisplayName("The far north, where the day does not fit inside the date")
    class FarNorth {

        /**
         * Lerwick, Shetland (ZE1) — the northernmost town in the UK, and far enough north that
         * midsummer civil dusk falls on the following morning. Orkney was tried first and is not
         * far enough: its dusk lands at 23:5x, in-day, and every assertion below passes without
         * the fix. The latitude is the fixture here.
         */
        private static final double LERWICK_LAT = 60.15;
        private static final double LERWICK_LON = -1.15;

        /** Unst, Shetland — far enough north that midsummer never reaches civil darkness. */
        private static final double UNST_LAT = 60.80;
        private static final double UNST_LON = -0.85;

        @Test
        @DisplayName("Midsummer dusk past midnight ends the rule, rather than restarting it")
        void duskAfterMidnightPinsToTheEndOfTheAxis() {
            // Lerwick's civil dusk on 21 June is 00:27 on the 22nd. As minutes-of-day that reads
            // 0.2% — the far LEFT of the rule — and `ascending` would then drag it up to sunset's
            // own position, deleting the evening blue hour from the gradient silently.
            TodaysLightResponse light = serviceAt("2026-06-21T09:00:00Z")
                    .buildFor(LERWICK_LAT, LERWICK_LON, POSTCODE, MIDSUMMER);

            assertThat(positionOf(light, "CIVIL_DUSK")).isEqualTo(100);
            assertThat(positionOf(light, "NAUTICAL_DUSK")).isEqualTo(100);
            // And the reason it is 100 rather than merely >= sunset: it must be strictly beyond
            // the sunset stop, which is still an ordinary in-day position.
            assertThat(positionOf(light, "SUNSET")).isLessThan(100).isGreaterThan(90);
        }

        @Test
        @DisplayName("The clock time still names the small hour it really is")
        void theClockTimeIsNotPinnedWithThePosition() {
            // Pinning is about the AXIS, not about the fact. A dusk at 00:1x is a true statement
            // and the row says so; only its place on a one-day rule is forced to the end.
            TodaysLightResponse light = serviceAt("2026-06-21T09:00:00Z")
                    .buildFor(LERWICK_LAT, LERWICK_LON, POSTCODE, MIDSUMMER);

            assertThat(light.civilDusk()).startsWith("00:");
        }

        @Test
        @DisplayName("A twilight that never happens is not reported as a time")
        void allNightTwilightFallsBackRatherThanPrintingASentinel() {
            // At 60.8°N the sun never reaches −6° at the solstice. solar-utils does not answer null
            // for that — it answers 00:00, the requested date's own midnight, for BOTH civil
            // boundaries. Believed, the row prints "01:00 blue · 03:29 golden · 22:40 golden ·
            // 23:10 blue": a blue hour claimed two and a half hours before sunrise, on a day that
            // has no blue hour at all.
            //
            // ⚠️ Asserted as VALUES against the offsets the fallback uses, derived from the same
            // SolarService this reads. The first version of this test asserted only that the two
            // civil times differed and that the four ran in order — and the defective output above
            // satisfies both, because only the dusk half was being rejected. It was green with the
            // bug present, which is how an adversarial review found the bug and not this suite.
            TodaysLightResponse light = serviceAt("2026-06-21T09:00:00Z")
                    .buildFor(UNST_LAT, UNST_LON, POSTCODE, MIDSUMMER);

            assertThat(light.civilDawn()).isEqualTo(
                    clockAt(solarService.sunriseUtc(UNST_LAT, UNST_LON, MIDSUMMER).minusMinutes(30)));
            assertThat(light.civilDusk()).isEqualTo(
                    clockAt(solarService.sunsetUtc(UNST_LAT, UNST_LON, MIDSUMMER).plusMinutes(30)));
            assertThat(List.of(light.civilDawn(), light.sunrise(), light.sunset(), light.civilDusk()))
                    .isSorted();
        }

        @Test
        @DisplayName("A golden hour that never happens does not collapse onto the stop beside it")
        void midwinterGoldenSentinelFallsBackToo() {
            // The same sentinel, on the other pair and in the other season: at Unst on 21 December
            // the sun never reaches +6°, so BOTH golden boundaries come back as 00:00. Passed
            // through, each computes position 0.0 and the ascending guard then lifts them onto
            // sunrise and solar noon — golden renders as two over-wide ramps with hard seams
            // instead of two bands, and the written ±60-minute fallbacks are unreachable in exactly
            // the case they exist for.
            //
            // Lerwick cannot reach this: its 21 December golden hours are real times. The latitude
            // is the fixture, and it has to be the higher one.
            TodaysLightResponse light = serviceAt("2026-12-21T09:00:00Z")
                    .buildFor(UNST_LAT, UNST_LON, POSTCODE, MIDWINTER);

            assertThat(positionOf(light, "GOLDEN_MORNING_END"))
                    .isGreaterThan(positionOf(light, "SUNRISE"));
            assertThat(positionOf(light, "GOLDEN_EVENING_START"))
                    .isLessThan(positionOf(light, "SUNSET"))
                    .isGreaterThan(positionOf(light, "SOLAR_NOON"));
        }

        @Test
        @DisplayName("Midsummer golden hours are real there, so the guard is not blanket-rejecting")
        void theGuardBelievesARealBoundary() {
            // The other half of the pair above, and the reason it is here: a guard that rejected
            // everything would make both assertions above pass. Unst's midsummer golden hours ARE
            // computed times, and they must survive rather than be replaced by ±60 minutes.
            TodaysLightResponse light = serviceAt("2026-06-21T09:00:00Z")
                    .buildFor(UNST_LAT, UNST_LON, POSTCODE, MIDSUMMER);

            assertThat(positionOf(light, "GOLDEN_MORNING_END"))
                    .isNotEqualTo(positionOf(light, "SUNRISE") + 60.0 / 1440 * 100);
            assertThat(positionOf(light, "GOLDEN_MORNING_END"))
                    .isGreaterThan(positionOf(light, "SUNRISE"))
                    .isLessThan(positionOf(light, "SOLAR_NOON"));
        }

        @Test
        @DisplayName("Midwinter still draws, with the lit band squeezed into the middle")
        void midwinterFarNorthStillDraws() {
            TodaysLightResponse light = serviceAt("2026-12-21T09:00:00Z")
                    .buildFor(LERWICK_LAT, LERWICK_LON, POSTCODE, MIDWINTER);

            double day = positionOf(light, "SUNSET") - positionOf(light, "SUNRISE");
            // Shetland gets under 6 hours of sun at the solstice — a quarter of the axis, and both
            // ends of the rule stay dark rather than one of them being pinned flat.
            assertThat(day).isBetween(20.0, 30.0);
            assertThat(positionOf(light, "CIVIL_DAWN")).isGreaterThan(0);
            assertThat(positionOf(light, "CIVIL_DUSK")).isLessThan(100);
        }
    }

    @Test
    @DisplayName("A day with no sunrise has no rule to draw")
    void polarDayReturnsNull() {
        SolarService polar = mock(SolarService.class);
        when(polar.sunriseUtc(eq(LAT), eq(LON), eq(MIDSUMMER))).thenReturn(null);

        TodaysLightService service = new TodaysLightService(polar, settingsService,
                Clock.fixed(Instant.parse("2026-06-21T09:00:00Z"), ZoneOffset.UTC));

        assertThat(service.buildFor(LAT, LON, POSTCODE, MIDSUMMER)).isNull();
        verifyNoInteractions(settingsService);
    }
}
