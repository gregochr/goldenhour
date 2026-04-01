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

    @Test
    @DisplayName("Sunrise azimuth on the equinox is approximately due East (90°)")
    void sunriseAzimuthDeg_equinox_isDueEast() {
        int azimuth = solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, LocalDate.of(2026, 3, 20));

        assertThat(azimuth).isCloseTo(90, org.assertj.core.data.Offset.offset(5));
    }

    @Test
    @DisplayName("Sunset azimuth on the equinox is approximately due West (270°)")
    void sunsetAzimuthDeg_equinox_isDueWest() {
        int azimuth = solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, LocalDate.of(2026, 3, 20));

        assertThat(azimuth).isCloseTo(270, org.assertj.core.data.Offset.offset(5));
    }

    @Test
    @DisplayName("Sunrise azimuth is north of East in summer and south of East in winter")
    void sunriseAzimuthDeg_isNorthOfEastInSummer_southOfEastInWinter() {
        int summer = solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, LocalDate.of(2026, 6, 21));
        int winter = solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, LocalDate.of(2026, 12, 21));

        assertThat(summer).isLessThan(90);
        assertThat(winter).isGreaterThan(90);
    }

    @Test
    @DisplayName("civilDawnUtc() is before sunrise")
    void civilDawnUtc_isBeforeSunrise() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime civilDawn = solarService.civilDawnUtc(DURHAM_LAT, DURHAM_LON, date);
        LocalDateTime sunrise = solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date);

        assertThat(civilDawn).isBefore(sunrise);
    }

    @Test
    @DisplayName("civilDuskUtc() is after sunset")
    void civilDuskUtc_isAfterSunset() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date);
        LocalDateTime civilDusk = solarService.civilDuskUtc(DURHAM_LAT, DURHAM_LON, date);

        assertThat(civilDusk).isAfter(sunset);
    }

    @Test
    @DisplayName("solarNoonUtc() falls between sunrise and sunset")
    void solarNoonUtc_isBetweenSunriseAndSunset() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date);
        LocalDateTime noon = solarService.solarNoonUtc(DURHAM_LAT, DURHAM_LON, date);
        LocalDateTime sunset = solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date);

        assertThat(noon).isAfter(sunrise).isBefore(sunset);
    }

    @Test
    @DisplayName("dayLengthMinutes() matches duration between sunrise and sunset")
    void dayLengthMinutes_matchesSunriseSunsetDuration() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date);
        LocalDateTime sunset = solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date);
        long expectedMinutes = java.time.Duration.between(sunrise, sunset).toMinutes();

        long actualMinutes = solarService.dayLengthMinutes(DURHAM_LAT, DURHAM_LON, date);

        assertThat(actualMinutes).isCloseTo(expectedMinutes, org.assertj.core.data.Offset.offset(2L));
    }

    // -------------------------------------------------------------------------
    // Nautical twilight (approximate: civil ± 35 min)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("nauticalDuskUtc is 35 minutes after civilDuskUtc")
    void nauticalDuskUtc_isCivilDuskPlus35Minutes() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime civilDusk = solarService.civilDuskUtc(DURHAM_LAT, DURHAM_LON, date);
        LocalDateTime nauticalDusk = solarService.nauticalDuskUtc(DURHAM_LAT, DURHAM_LON, date);

        assertThat(nauticalDusk).isEqualTo(civilDusk.plusMinutes(35));
    }

    @Test
    @DisplayName("nauticalDawnUtc is 35 minutes before civilDawnUtc")
    void nauticalDawnUtc_isCivilDawnMinus35Minutes() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime civilDawn = solarService.civilDawnUtc(DURHAM_LAT, DURHAM_LON, date);
        LocalDateTime nauticalDawn = solarService.nauticalDawnUtc(DURHAM_LAT, DURHAM_LON, date);

        assertThat(nauticalDawn).isEqualTo(civilDawn.minusMinutes(35));
    }

    @Test
    @DisplayName("nauticalDuskUtc on date D is before nauticalDawnUtc on date D+1")
    void nauticalDusk_isBeforeDawn_nextDay() {
        LocalDate date = LocalDate.of(2026, 3, 20);
        LocalDateTime dusk = solarService.nauticalDuskUtc(DURHAM_LAT, DURHAM_LON, date);
        LocalDateTime dawn = solarService.nauticalDawnUtc(DURHAM_LAT, DURHAM_LON, date.plusDays(1));

        assertThat(dusk).isBefore(dawn);
    }
}
