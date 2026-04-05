package com.gregochr.goldenhour.service;

import com.gregochr.solarutils.SolarCalculator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Calculates sunrise and sunset times using the NOAA/Meeus solar algorithm.
 *
 * <p>Wraps the {@code solar-utils} library. All times are returned as UTC {@link LocalDateTime}.
 */
@Service
public class SolarService {

    private final SolarCalculator calculator = new SolarCalculator();

    /**
     * Returns the UTC sunrise time for the given location and date.
     *
     * @param lat  latitude in decimal degrees
     * @param lon  longitude in decimal degrees
     * @param date the date to calculate sunrise for
     * @return UTC time of sunrise
     */
    public LocalDateTime sunriseUtc(double lat, double lon, LocalDate date) {
        return calculator.sunrise(lat, lon, date, ZoneOffset.UTC);
    }

    /**
     * Returns the UTC sunset time for the given location and date.
     *
     * @param lat  latitude in decimal degrees
     * @param lon  longitude in decimal degrees
     * @param date the date to calculate sunset for
     * @return UTC time of sunset
     */
    public LocalDateTime sunsetUtc(double lat, double lon, LocalDate date) {
        return calculator.sunset(lat, lon, date, ZoneOffset.UTC);
    }

    /**
     * Returns the compass azimuth (degrees clockwise from North) at which the sun rises.
     *
     * @param lat  latitude in decimal degrees
     * @param lon  longitude in decimal degrees
     * @param date the date to calculate the sunrise azimuth for
     * @return sunrise azimuth in whole degrees (0–359)
     */
    public int sunriseAzimuthDeg(double lat, double lon, LocalDate date) {
        return calculator.sunriseAzimuth(lat, lon, date);
    }

    /**
     * Returns the compass azimuth (degrees clockwise from North) at which the sun sets.
     *
     * @param lat  latitude in decimal degrees
     * @param lon  longitude in decimal degrees
     * @param date the date to calculate the sunset azimuth for
     * @return sunset azimuth in whole degrees (0–359)
     */
    public int sunsetAzimuthDeg(double lat, double lon, LocalDate date) {
        return calculator.sunsetAzimuth(lat, lon, date);
    }

    /**
     * Returns the start of civil dawn — when the sun reaches −6° before sunrise.
     * Marks the beginning of the golden/blue hour window in the morning.
     *
     * @param lat  latitude in decimal degrees
     * @param lon  longitude in decimal degrees
     * @param date the date to calculate civil dawn for
     * @return UTC time of civil dawn
     */
    public LocalDateTime civilDawnUtc(double lat, double lon, LocalDate date) {
        return calculator.civilDawn(lat, lon, date, ZoneOffset.UTC);
    }

    /**
     * Returns the end of civil dusk — when the sun reaches −6° after sunset.
     * Marks the end of the golden/blue hour window in the evening.
     *
     * @param lat  latitude in decimal degrees
     * @param lon  longitude in decimal degrees
     * @param date the date to calculate civil dusk for
     * @return UTC time of civil dusk
     */
    public LocalDateTime civilDuskUtc(double lat, double lon, LocalDate date) {
        return calculator.civilDusk(lat, lon, date, ZoneOffset.UTC);
    }

    /**
     * Returns the time of solar noon — when the sun is at its highest point.
     *
     * @param lat  latitude in decimal degrees
     * @param lon  longitude in decimal degrees
     * @param date the date to calculate solar noon for
     * @return UTC time of solar noon
     */
    public LocalDateTime solarNoonUtc(double lat, double lon, LocalDate date) {
        return calculator.solarNoon(lat, lon, date, ZoneOffset.UTC);
    }

    /**
     * Returns the day length in minutes from sunrise to sunset.
     *
     * @param lat  latitude in decimal degrees
     * @param lon  longitude in decimal degrees
     * @param date the date to calculate day length for
     * @return day length in minutes
     */
    public long dayLengthMinutes(double lat, double lon, LocalDate date) {
        return calculator.dayLengthMinutes(lat, lon, date);
    }

    /**
     * Approximate UTC nautical dusk — when the sun reaches −12° after sunset.
     *
     * <p>Solar-utils does not provide nautical twilight directly, so this
     * approximates it as civil dusk + 35 minutes. The same approach is used
     * by {@code AuroraForecastRunService} for computing dark windows.
     *
     * @param lat  latitude in decimal degrees
     * @param lon  longitude in decimal degrees
     * @param date the date to calculate nautical dusk for
     * @return approximate UTC time of nautical dusk
     */
    public LocalDateTime nauticalDuskUtc(double lat, double lon, LocalDate date) {
        return civilDuskUtc(lat, lon, date).plusMinutes(NAUTICAL_OFFSET_MINUTES);
    }

    /**
     * Approximate UTC nautical dawn — when the sun reaches −12° before sunrise.
     *
     * <p>Approximated as civil dawn − 35 minutes, consistent with the approach
     * used by {@code AuroraForecastRunService}.
     *
     * @param lat  latitude in decimal degrees
     * @param lon  longitude in decimal degrees
     * @param date the date to calculate nautical dawn for
     * @return approximate UTC time of nautical dawn
     */
    public LocalDateTime nauticalDawnUtc(double lat, double lon, LocalDate date) {
        return civilDawnUtc(lat, lon, date).minusMinutes(NAUTICAL_OFFSET_MINUTES);
    }

    /**
     * Offset in minutes between civil twilight (−6°) and nautical twilight (−12°).
     * Empirically ~30–40 minutes at UK latitudes; 35 is a reasonable middle value.
     */
    private static final long NAUTICAL_OFFSET_MINUTES = 35;

    /**
     * The four boundary times of the blue and golden hour window for a solar event.
     *
     * <p>For sunrise: blueHourStart (civil dawn, −6°) → blueHourEnd (sunrise, 0°) →
     * goldenHourStart (sunrise, 0°) → goldenHourEnd (+6°).
     * <p>For sunset: goldenHourStart (+6°) → goldenHourEnd (sunset, 0°) →
     * blueHourStart (sunset, 0°) → blueHourEnd (civil dusk, −6°).
     *
     * <p>All times are UTC {@link LocalDateTime}, consistent with the rest of SolarService.
     *
     * @param blueHourStart   when the blue hour begins (UTC)
     * @param blueHourEnd     when the blue hour ends (UTC)
     * @param goldenHourStart when the golden hour begins (UTC)
     * @param goldenHourEnd   when the golden hour ends (UTC)
     */
    public record SolarWindow(
            LocalDateTime blueHourStart,
            LocalDateTime blueHourEnd,
            LocalDateTime goldenHourStart,
            LocalDateTime goldenHourEnd) {
    }

    /**
     * Returns the elevation-based blue and golden hour window for a solar event.
     *
     * <p>All four boundaries are exact Meeus calculations — no fixed offsets.
     *
     * <p>For sunrise: blueHourStart = civil dawn (−6°), blueHourEnd = sunrise (0°),
     * goldenHourStart = sunrise (0°), goldenHourEnd = sun at +6°.
     *
     * <p>For sunset: goldenHourStart = sun at +6°, goldenHourEnd = sunset (0°),
     * blueHourStart = sunset (0°), blueHourEnd = civil dusk (−6°).
     *
     * @param lat       latitude in decimal degrees
     * @param lon       longitude in decimal degrees
     * @param date      date of the solar event
     * @param isSunrise true for sunrise window, false for sunset
     * @return the four boundary times, all UTC
     */
    public SolarWindow goldenBlueWindow(double lat, double lon, LocalDate date,
            boolean isSunrise) {
        if (isSunrise) {
            LocalDateTime blueStart = civilDawnUtc(lat, lon, date);
            LocalDateTime sunrise = sunriseUtc(lat, lon, date);
            LocalDateTime goldenEnd = calculator.goldenHourEnd(lat, lon, date, ZoneOffset.UTC);
            return new SolarWindow(blueStart, sunrise, sunrise, goldenEnd);
        } else {
            LocalDateTime goldenStart = calculator.goldenHourStart(lat, lon, date, ZoneOffset.UTC);
            LocalDateTime sunset = sunsetUtc(lat, lon, date);
            LocalDateTime blueEnd = civilDuskUtc(lat, lon, date);
            return new SolarWindow(sunset, blueEnd, goldenStart, sunset);
        }
    }
}
