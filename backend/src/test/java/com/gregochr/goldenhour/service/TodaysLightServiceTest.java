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
