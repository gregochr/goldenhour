package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.model.TodaysLightResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * The boundary gates of {@link TodaysLightService}: a solar boundary that is missing, a midnight sentinel or on the
 * wrong side of its own event is replaced by an offset-derived stand-in, events that fall on another UK day sit at
 * the ends of the rule, and a home without usable coordinates or a day without a sunrise gives no light.
 */
class TodaysLightServiceBoundaryTest {

    private static final double LAT = 55.0;
    private static final double LON = -1.5;
    private static final String POSTCODE = "NE66 1NG";
    /** January: Europe/London is UTC, so UTC and local clocks agree. */
    private static final LocalDate DAY = LocalDate.of(2026, 1, 10);

    private SolarService solar;
    private UserSettingsService settings;
    private TodaysLightService service;

    @BeforeEach
    void setUp() {
        solar = mock(SolarService.class);
        settings = mock(UserSettingsService.class);
        service = new TodaysLightService(solar, settings,
                Clock.fixed(Instant.parse("2026-01-10T09:00:00Z"), ZoneOffset.UTC));
    }

    private static LocalDateTime at(int hour, int minute) {
        return DAY.atTime(hour, minute);
    }

    /** Stubs a day with plain sunrise 08:00 / sunset 16:00 and every other boundary as given. */
    private void stubDay(LocalDateTime civilDawn, LocalDateTime civilDusk, LocalDateTime goldenMorningEnd,
            LocalDateTime goldenEveningStart, LocalDateTime solarNoon) {
        when(solar.sunriseUtc(LAT, LON, DAY)).thenReturn(at(8, 0));
        when(solar.sunsetUtc(LAT, LON, DAY)).thenReturn(at(16, 0));
        stubBoundaries(civilDawn, civilDusk, goldenMorningEnd, goldenEveningStart, solarNoon);
    }

    private void stubBoundaries(LocalDateTime civilDawn, LocalDateTime civilDusk, LocalDateTime goldenMorningEnd,
            LocalDateTime goldenEveningStart, LocalDateTime solarNoon) {
        when(solar.goldenBlueWindow(LAT, LON, DAY, true))
                .thenReturn(new SolarService.SolarWindow(null, null, null, goldenMorningEnd));
        when(solar.goldenBlueWindow(LAT, LON, DAY, false))
                .thenReturn(new SolarService.SolarWindow(null, null, goldenEveningStart, null));
        when(solar.civilDawnUtc(LAT, LON, DAY)).thenReturn(civilDawn);
        when(solar.civilDuskUtc(LAT, LON, DAY)).thenReturn(civilDusk);
        when(solar.solarNoonUtc(LAT, LON, DAY)).thenReturn(solarNoon);
    }

    private static double position(TodaysLightResponse light, String key) {
        return light.stops().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().position();
    }

    @Test
    @DisplayName("every boundary on the right side of its event is used as given")
    void buildFor_validBoundaries_usedAsGiven() {
        stubDay(at(7, 0), at(17, 0), at(9, 0), at(15, 0), at(12, 0));

        TodaysLightResponse light = service.buildFor(LAT, LON, POSTCODE, DAY);

        assertThat(light.civilDawn()).isEqualTo("07:00");
        assertThat(light.sunrise()).isEqualTo("08:00");
        assertThat(light.sunset()).isEqualTo("16:00");
        assertThat(light.civilDusk()).isEqualTo("17:00");
        assertThat(position(light, "SOLAR_NOON")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("civil dawn after sunrise, or civil dusk before sunset, is replaced by half an hour either side")
    void buildFor_civilBoundaryOnWrongSide_fallsBack() {
        stubDay(at(8, 30), at(15, 0), at(9, 0), at(15, 0), at(12, 0));

        TodaysLightResponse light = service.buildFor(LAT, LON, POSTCODE, DAY);

        assertThat(light.civilDawn()).isEqualTo("07:30");
        assertThat(light.civilDusk()).isEqualTo("16:30");
    }

    @Test
    @DisplayName("a missing or midnight-sentinel civil boundary is replaced by half an hour either side")
    void buildFor_civilBoundaryMissingOrSentinel_fallsBack() {
        stubDay(null, DAY.atStartOfDay(), at(9, 0), at(15, 0), at(12, 0));

        TodaysLightResponse light = service.buildFor(LAT, LON, POSTCODE, DAY);

        assertThat(light.civilDawn()).isEqualTo("07:30");
        assertThat(light.civilDusk()).isEqualTo("16:30");
    }

    @Test
    @DisplayName("a golden-hour end outside the sunrise-to-sunset day, or missing, is replaced by sunrise + 60 minutes")
    void buildFor_goldenMorningEnd_fallsBack() {
        TodaysLightResponse missing = fallbackMorning(null);
        TodaysLightResponse beforeSunrise = fallbackMorning(at(7, 0));
        TodaysLightResponse afterSunset = fallbackMorning(at(17, 0));
        TodaysLightResponse sentinel = fallbackMorning(DAY.atStartOfDay());

        // sunrise 08:00 + 60 min = 09:00 = 37.5% of the day
        assertThat(position(missing, "GOLDEN_MORNING_END")).isEqualTo(37.5);
        assertThat(position(beforeSunrise, "GOLDEN_MORNING_END")).isEqualTo(37.5);
        assertThat(position(afterSunset, "GOLDEN_MORNING_END")).isEqualTo(37.5);
        assertThat(position(sentinel, "GOLDEN_MORNING_END")).isEqualTo(37.5);
    }

    private TodaysLightResponse fallbackMorning(LocalDateTime goldenMorningEnd) {
        stubDay(at(7, 0), at(17, 0), goldenMorningEnd, at(15, 0), at(12, 0));
        return service.buildFor(LAT, LON, POSTCODE, DAY);
    }

    @Test
    @DisplayName("a golden-hour start outside the sunrise-to-sunset day, or missing, becomes sunset - 60 minutes")
    void buildFor_goldenEveningStart_fallsBack() {
        // sunset 16:00 - 60 min = 15:00 = 62.5% of the day
        assertThat(position(fallbackEvening(null), "GOLDEN_EVENING_START")).isEqualTo(62.5);
        assertThat(position(fallbackEvening(at(7, 0)), "GOLDEN_EVENING_START")).isEqualTo(62.5);
        assertThat(position(fallbackEvening(at(17, 0)), "GOLDEN_EVENING_START")).isEqualTo(62.5);
    }

    private TodaysLightResponse fallbackEvening(LocalDateTime goldenEveningStart) {
        stubDay(at(7, 0), at(17, 0), at(9, 0), goldenEveningStart, at(12, 0));
        return service.buildFor(LAT, LON, POSTCODE, DAY);
    }

    @Test
    @DisplayName("a solar noon outside the day or a sentinel is replaced by the midpoint of sunrise and sunset")
    void buildFor_solarNoon_fallsBack() {
        assertThat(position(noon(null), "SOLAR_NOON")).isEqualTo(50.0);
        assertThat(position(noon(DAY.atStartOfDay()), "SOLAR_NOON")).isEqualTo(50.0);
        assertThat(position(noon(at(7, 0)), "SOLAR_NOON")).isEqualTo(50.0);
        assertThat(position(noon(at(17, 0)), "SOLAR_NOON")).isEqualTo(50.0);
    }

    private TodaysLightResponse noon(LocalDateTime solarNoon) {
        stubDay(at(7, 0), at(17, 0), at(9, 0), at(15, 0), solarNoon);
        return service.buildFor(LAT, LON, POSTCODE, DAY);
    }

    @Test
    @DisplayName("a day with no sunrise or no sunset has no light to draw")
    void buildFor_noSunriseOrSunset_null() {
        when(solar.sunriseUtc(LAT, LON, DAY)).thenReturn(null);
        when(solar.sunsetUtc(LAT, LON, DAY)).thenReturn(at(16, 0));
        assertThat(service.buildFor(LAT, LON, POSTCODE, DAY)).isNull();

        when(solar.sunriseUtc(LAT, LON, DAY)).thenReturn(at(8, 0));
        when(solar.sunsetUtc(LAT, LON, DAY)).thenReturn(null);
        assertThat(service.buildFor(LAT, LON, POSTCODE, DAY)).isNull();
    }

    @Test
    @DisplayName("stops that fall on the previous or next UK day sit at the two ends of the rule")
    void buildFor_stopsOnOtherDays_pinnedToTheEnds() {
        when(solar.sunriseUtc(LAT, LON, DAY)).thenReturn(at(0, 10));
        when(solar.sunsetUtc(LAT, LON, DAY)).thenReturn(at(23, 50));
        stubBoundaries(null, null, null, null, null);

        TodaysLightResponse light = service.buildFor(LAT, LON, POSTCODE, DAY);

        // civil dawn falls back to 23:40 the day before, civil dusk to 00:20 the day after.
        assertThat(position(light, "NAUTICAL_DAWN")).isEqualTo(0.0);
        assertThat(position(light, "CIVIL_DAWN")).isEqualTo(0.0);
        assertThat(position(light, "CIVIL_DUSK")).isEqualTo(100.0);
        assertThat(position(light, "NAUTICAL_DUSK")).isEqualTo(100.0);
        assertThat(light.stops()).extracting(TodaysLightResponse.Stop::position).isSorted();
    }

    @Test
    @DisplayName("a home with a latitude but no longitude, or no postcode, has no light and builds nothing")
    void getTodaysLight_incompleteHome_null() {
        Authentication auth = mock(Authentication.class);
        when(settings.getHomeLocation(auth)).thenReturn(
                new UserSettingsService.HomeLocation(7L, LAT, null, 30, POSTCODE, null));
        assertThat(service.getTodaysLight(auth)).isNull();

        when(settings.getHomeLocation(auth)).thenReturn(
                new UserSettingsService.HomeLocation(7L, LAT, LON, 30, null, null));
        assertThat(service.getTodaysLight(auth)).isNull();
        verifyNoInteractions(solar);
    }
}
