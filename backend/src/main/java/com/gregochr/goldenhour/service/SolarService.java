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
}
