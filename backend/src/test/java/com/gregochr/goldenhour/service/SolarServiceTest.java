package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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

    // -------------------------------------------------------------------------
    // Southern hemisphere
    // -------------------------------------------------------------------------

    private static final double SYDNEY_LAT = -33.8688;
    private static final double SYDNEY_LON = 151.2093;

    @Test
    @DisplayName("Southern hemisphere: December is summer, June is winter")
    void southernHemisphere_decemberLongerThanJune() {
        LocalDate decSolstice = LocalDate.of(2026, 12, 21);
        LocalDate junSolstice = LocalDate.of(2026, 6, 21);

        long decDayMinutes = solarService.dayLengthMinutes(SYDNEY_LAT, SYDNEY_LON, decSolstice);
        long junDayMinutes = solarService.dayLengthMinutes(SYDNEY_LAT, SYDNEY_LON, junSolstice);

        assertThat(decDayMinutes).isGreaterThan(junDayMinutes);
    }

    @Test
    @DisplayName("Southern hemisphere: sunrise azimuth is south of East in December")
    void southernHemisphere_sunriseAzimuth_southOfEastInDecember() {
        int azimuth = solarService.sunriseAzimuthDeg(SYDNEY_LAT, SYDNEY_LON,
                LocalDate.of(2026, 12, 21));
        // In southern hemisphere summer, sunrise is south of East (azimuth > 90)
        // Actually no — sunrise is SOUTH of east which is < 90 degrees
        // Wait — at southern hemisphere summer solstice, the sun rises south of east
        // In compass degrees, south of east means azimuth > 90 for southern latitudes...
        // Actually the sun rises further south, so azimuth is between ~115-120
        assertThat(azimuth).isGreaterThan(90);
    }

    // -------------------------------------------------------------------------
    // Equator
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // goldenBlueWindow — sunrise
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Sunrise window: blue hour starts at civil dawn, ends at sunrise")
    void sunriseWindow_blueHourBoundaries() {
        LocalDate date = LocalDate.of(2026, 4, 5);
        SolarService.SolarWindow w = solarService.goldenBlueWindow(
                DURHAM_LAT, DURHAM_LON, date, true);

        LocalDateTime civilDawn = solarService.civilDawnUtc(DURHAM_LAT, DURHAM_LON, date);
        LocalDateTime sunrise = solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date);

        assertThat(w.blueHourStart()).isEqualTo(civilDawn);
        assertThat(w.blueHourEnd()).isEqualTo(sunrise);
        assertThat(w.goldenHourStart()).isEqualTo(sunrise);
    }

    @Test
    @DisplayName("Sunrise window: golden hour end is after sunrise (sun at +6°)")
    void sunriseWindow_goldenHourEndAfterSunrise() {
        LocalDate date = LocalDate.of(2026, 4, 5);
        SolarService.SolarWindow w = solarService.goldenBlueWindow(
                DURHAM_LAT, DURHAM_LON, date, true);

        assertThat(w.goldenHourEnd()).isAfter(w.goldenHourStart());
    }

    @Test
    @DisplayName("Sunrise window: golden hour is shorter than 60 mins in spring at 55°N")
    void sunriseWindow_springGoldenHourShorterThan60Min() {
        LocalDate date = LocalDate.of(2026, 4, 5);
        SolarService.SolarWindow w = solarService.goldenBlueWindow(
                DURHAM_LAT, DURHAM_LON, date, true);

        long goldenMinutes = Duration.between(w.goldenHourStart(), w.goldenHourEnd()).toMinutes();
        assertThat(goldenMinutes).isBetween(25L, 50L);
    }

    // -------------------------------------------------------------------------
    // goldenBlueWindow — sunset
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Sunset window: blue hour starts at sunset, ends at civil dusk")
    void sunsetWindow_blueHourBoundaries() {
        LocalDate date = LocalDate.of(2026, 4, 5);
        SolarService.SolarWindow w = solarService.goldenBlueWindow(
                DURHAM_LAT, DURHAM_LON, date, false);

        LocalDateTime sunset = solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date);
        LocalDateTime civilDusk = solarService.civilDuskUtc(DURHAM_LAT, DURHAM_LON, date);

        assertThat(w.blueHourStart()).isEqualTo(sunset);
        assertThat(w.blueHourEnd()).isEqualTo(civilDusk);
        assertThat(w.goldenHourEnd()).isEqualTo(sunset);
    }

    @Test
    @DisplayName("Sunset window: golden hour start is before sunset (sun at +6°)")
    void sunsetWindow_goldenHourStartBeforeSunset() {
        LocalDate date = LocalDate.of(2026, 4, 5);
        SolarService.SolarWindow w = solarService.goldenBlueWindow(
                DURHAM_LAT, DURHAM_LON, date, false);

        assertThat(w.goldenHourStart()).isBefore(w.goldenHourEnd());
    }

    @Test
    @DisplayName("Midsummer sunset golden hour exceeds 60 minutes at 55°N")
    void midsummerSunset_goldenHourExceeds60Min() {
        LocalDate midsummer = LocalDate.of(2026, 6, 21);
        SolarService.SolarWindow w = solarService.goldenBlueWindow(
                DURHAM_LAT, DURHAM_LON, midsummer, false);

        long goldenMinutes = Duration.between(w.goldenHourStart(), w.goldenHourEnd()).toMinutes();
        assertThat(goldenMinutes).isGreaterThan(60);
    }

    @Test
    @DisplayName("Equinox sunset golden hour is shorter than solstice at 55°N")
    void equinoxSunset_goldenHourShorterThanSolstice() {
        // At high latitudes the sun sets more obliquely near solstices,
        // producing longer golden hours than at the equinox
        LocalDate equinox = LocalDate.of(2026, 3, 20);
        LocalDate solstice = LocalDate.of(2026, 6, 21);
        SolarService.SolarWindow equinoxW = solarService.goldenBlueWindow(
                DURHAM_LAT, DURHAM_LON, equinox, false);
        SolarService.SolarWindow solsticeW = solarService.goldenBlueWindow(
                DURHAM_LAT, DURHAM_LON, solstice, false);

        long equinoxGolden = Duration.between(equinoxW.goldenHourStart(), equinoxW.goldenHourEnd()).toMinutes();
        long solsticeGolden = Duration.between(solsticeW.goldenHourStart(), solsticeW.goldenHourEnd()).toMinutes();

        assertThat(equinoxGolden).isLessThan(solsticeGolden);
    }

    // -------------------------------------------------------------------------
    // Equator
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Equator: day length is approximately 12 hours year-round")
    void equator_dayLengthApprox12Hours() {
        double equatorLat = 0.0;
        double equatorLon = 0.0;

        long marchDay = solarService.dayLengthMinutes(equatorLat, equatorLon,
                LocalDate.of(2026, 3, 20));
        long juneDay = solarService.dayLengthMinutes(equatorLat, equatorLon,
                LocalDate.of(2026, 6, 21));
        long decDay = solarService.dayLengthMinutes(equatorLat, equatorLon,
                LocalDate.of(2026, 12, 21));

        // All within ~15 minutes of 720 (12 hours)
        assertThat(marchDay).isBetween(705L, 735L);
        assertThat(juneDay).isBetween(705L, 735L);
        assertThat(decDay).isBetween(705L, 735L);
    }
}
