package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SolarService}.
 */
class SolarServiceTest {

    private static final double DURHAM_LAT = 54.7753;
    private static final double DURHAM_LON = -1.5849;

    private final SolarService solarService = new SolarService();

    @Test
    @DisplayName("Sunrise is before sunset for Durham UK in summer")
    void sunriseUtc_isBefore_sunsetUtc_inSummer() {
        LocalDate summerDate = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, summerDate);
        LocalDateTime sunset = solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, summerDate);

        assertThat(sunrise).isBefore(sunset);
    }

    @Test
    @DisplayName("Sunrise is before sunset for Durham UK in winter")
    void sunriseUtc_isBefore_sunsetUtc_inWinter() {
        LocalDate winterDate = LocalDate.of(2026, 12, 21);
        LocalDateTime sunrise = solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, winterDate);
        LocalDateTime sunset = solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, winterDate);

        assertThat(sunrise).isBefore(sunset);
    }

    @Test
    @DisplayName("Summer day is longer than winter day for Durham UK")
    void summerDay_isLonger_thanWinterDay() {
        LocalDate summer = LocalDate.of(2026, 6, 21);
        LocalDate winter = LocalDate.of(2026, 12, 21);

        long summerDayMinutes = java.time.Duration.between(
                solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, summer),
                solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, summer)).toMinutes();

        long winterDayMinutes = java.time.Duration.between(
                solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, winter),
                solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, winter)).toMinutes();

        assertThat(summerDayMinutes).isGreaterThan(winterDayMinutes);
    }

    @Test
    @DisplayName("Sunset for Durham in summer is in the evening (after 18:00 UTC)")
    void summerSunset_isInEvening() {
        LocalDate summerDate = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, summerDate);

        assertThat(sunset.getHour()).isGreaterThanOrEqualTo(18);
    }

    @Test
    @DisplayName("Sunrise for Durham in winter is in the morning (before 09:00 UTC)")
    void winterSunrise_isInMorning() {
        LocalDate winterDate = LocalDate.of(2026, 12, 21);
        LocalDateTime sunrise = solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, winterDate);

        assertThat(sunrise.getHour()).isLessThan(9);
    }
}
