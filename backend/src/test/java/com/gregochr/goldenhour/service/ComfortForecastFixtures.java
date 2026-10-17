package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds Open-Meteo forecast responses for the wildlife comfort tests: one entry per UTC hour for
 * {@code days} whole days from midnight of {@code firstDate}, the same shape Open-Meteo returns
 * with {@code timezone=UTC}.
 *
 * <p>Values are deterministic functions of the hour's index {@code i} (0 at midnight of the first
 * day), so a test can state the literal it expects for any hour:
 * <ul>
 *   <li>temperature {@code 5.0 + i}, feels-like {@code 3.0 + i}</li>
 *   <li>precipitation probability {@code i % 100}</li>
 *   <li>wind speed {@code i + 0.125} m/s — a binary-exact three-decimal tie, to pin HALF_UP</li>
 *   <li>wind direction {@code (i * 10) % 360}</li>
 *   <li>precipitation {@code 0.125 * i} mm (also a tie for odd {@code i})</li>
 * </ul>
 * The colour series (cloud, visibility, humidity, weather code, boundary layer, shortwave) are
 * deliberately left absent: the comfort extraction must not need them.
 */
public final class ComfortForecastFixtures {

    private ComfortForecastFixtures() {
    }

    /**
     * Builds a response covering {@code days} whole UTC days from {@code firstDate}.
     *
     * @param firstDate the first day, from 00:00 UTC
     * @param days      how many whole days
     * @return a mutable response; tests null individual entries with {@code List.set}
     */
    public static OpenMeteoForecastResponse forecast(LocalDate firstDate, int days) {
        List<String> time = new ArrayList<>();
        List<Double> temperature = new ArrayList<>();
        List<Double> apparent = new ArrayList<>();
        List<Integer> probability = new ArrayList<>();
        List<Double> windSpeed = new ArrayList<>();
        List<Integer> windDirection = new ArrayList<>();
        List<Double> precipitation = new ArrayList<>();
        LocalDateTime start = firstDate.atStartOfDay();
        for (int i = 0; i < days * 24; i++) {
            time.add(start.plusHours(i).toString());
            temperature.add(5.0 + i);
            apparent.add(3.0 + i);
            probability.add(i % 100);
            windSpeed.add(i + 0.125);
            windDirection.add((i * 10) % 360);
            precipitation.add(0.125 * i);
        }
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(time);
        hourly.setTemperature2m(temperature);
        hourly.setApparentTemperature(apparent);
        hourly.setPrecipitationProbability(probability);
        hourly.setWindSpeed10m(windSpeed);
        hourly.setWindDirection10m(windDirection);
        hourly.setPrecipitation(precipitation);
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(hourly);
        return response;
    }
}
