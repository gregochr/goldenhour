package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.model.NlcWindow;
import com.gregochr.goldenhour.service.NlcTwilightWindowCalculator.NlcWindows;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.assertj.core.data.Offset;

/**
 * Edge cases of {@link NlcTwilightWindowCalculator}: polar nights with no sunset or sunrise, a white night
 * where the sun never reaches -6 degrees, an inverted sunset/sunrise pair, the window-presence flag and
 * pre-2000 instants.
 */
class NlcTwilightWindowCalculatorEdgeCaseTest {

    private static final LocalDate EVENING = LocalDate.of(2026, 6, 21);

    @Test
    @DisplayName("no sunset (polar day) gives no windows at all")
    void compute_noSunset_noWindows() {
        SolarService solar = mock(SolarService.class);
        when(solar.sunsetUtc(80.0, 0.0, EVENING)).thenReturn(null);
        when(solar.sunriseUtc(80.0, 0.0, EVENING.plusDays(1))).thenReturn(LocalDateTime.of(2026, 6, 22, 1, 0));

        NlcWindows windows = new NlcTwilightWindowCalculator(solar).compute(80.0, 0.0, EVENING);

        assertThat(windows.evening()).isNull();
        assertThat(windows.morning()).isNull();
        assertThat(windows.hasAny()).isFalse();
    }

    @Test
    @DisplayName("no sunrise (polar night) gives no windows at all")
    void compute_noSunrise_noWindows() {
        SolarService solar = mock(SolarService.class);
        when(solar.sunsetUtc(80.0, 0.0, EVENING)).thenReturn(LocalDateTime.of(2026, 6, 21, 22, 0));
        when(solar.sunriseUtc(80.0, 0.0, EVENING.plusDays(1))).thenReturn(null);

        NlcWindows windows = new NlcTwilightWindowCalculator(solar).compute(80.0, 0.0, EVENING);

        assertThat(windows.hasAny()).isFalse();
    }

    @Test
    @DisplayName("a sunset that is not before the next sunrise gives no windows")
    void compute_sunsetNotBeforeSunrise_noWindows() {
        SolarService solar = mock(SolarService.class);
        LocalDateTime same = LocalDateTime.of(2026, 6, 21, 22, 0);
        when(solar.sunsetUtc(55.3, -2.1, EVENING)).thenReturn(same);
        when(solar.sunriseUtc(55.3, -2.1, EVENING.plusDays(1))).thenReturn(same);

        NlcWindows windows = new NlcTwilightWindowCalculator(solar).compute(55.3, -2.1, EVENING);

        assertThat(windows.hasAny()).isFalse();
    }

    @Test
    @DisplayName("a white night at 62N, where the sun never reaches -6 degrees, has neither window")
    void compute_whiteNight_noWindows() {
        NlcWindows windows = new NlcTwilightWindowCalculator(new SolarService()).compute(62.0, -2.0, EVENING);

        assertThat(windows.evening()).isNull();
        assertThat(windows.morning()).isNull();
        assertThat(windows.hasAny()).isFalse();
    }

    @Test
    @DisplayName("at 55N in midsummer the sun never reaches -16 degrees, so each window runs to solar midnight")
    void compute_midsummer_windowsMeetAtSolarMidnight() {
        NlcWindows windows = new NlcTwilightWindowCalculator(new SolarService()).compute(55.3, -2.1, EVENING);

        NlcWindow evening = windows.evening();
        NlcWindow morning = windows.morning();
        assertThat(evening).isNotNull();
        assertThat(morning).isNotNull();
        assertThat(evening.azimuth()).isEqualTo("NW");
        assertThat(morning.azimuth()).isEqualTo("NE");
        // Both windows touch solar midnight: the evening ends where the morning begins.
        assertThat(evening.end()).isEqualTo(morning.start());
    }

    @Test
    @DisplayName("hasAny is true when either window is present and false only when both are absent")
    void hasAny_combinations() {
        NlcWindow window = new NlcWindow("22:46", "23:52", "NW");

        assertThat(new NlcWindows(window, null).hasAny()).isTrue();
        assertThat(new NlcWindows(null, window).hasAny()).isTrue();
        assertThat(new NlcWindows(window, window).hasAny()).isTrue();
        assertThat(new NlcWindows(null, null).hasAny()).isFalse();
    }

    @Test
    @DisplayName("an instant before the year 2000 still gives a plausible solar altitude")
    void solarAltitude_before2000() {
        Instant midnightJune1990 = LocalDate.of(1990, 6, 21).atTime(0, 0).toInstant(ZoneOffset.UTC);
        Instant noonJune1990 = LocalDate.of(1990, 6, 21).atTime(12, 0).toInstant(ZoneOffset.UTC);

        assertThat(NlcTwilightWindowCalculator.solarAltitudeDeg(55.3, -2.1, midnightJune1990))
                .isCloseTo(-11.5, Offset.offset(1.5));
        assertThat(NlcTwilightWindowCalculator.solarAltitudeDeg(55.3, -2.1, noonJune1990))
                .isCloseTo(58.1, Offset.offset(1.5));
    }
}
