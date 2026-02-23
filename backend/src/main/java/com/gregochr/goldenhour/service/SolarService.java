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
}
