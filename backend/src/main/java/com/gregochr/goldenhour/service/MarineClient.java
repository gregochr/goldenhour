package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenMeteoMarineApi;
import com.gregochr.goldenhour.model.MarineWaveSample;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Resilient wrapper around the Open-Meteo Marine Weather API, plus the sea-state selection helper.
 *
 * <p>Fetches a 7-day hourly sea-state series for a coastal location and picks the significant wave
 * height at a specific event time (typically the aligned high water). Called from the briefing
 * triage pipeline — never from a {@code HotTopicStrategy}, which must stay API-call-free. Uses the
 * isolated {@code open-meteo-briefing} circuit breaker so a marine outage cannot trip the
 * forecast-run breaker (and vice versa), sharing the {@code open-meteo} rate limiter.
 */
@Service
public class MarineClient {

    private static final String HOURLY_PARAMS = "wave_height,swell_wave_height,wave_direction";
    private static final String TIMEZONE = "UTC";
    private static final int FORECAST_DAYS = 7;
    private static final int HALF_HOUR_MINUTES = 30;

    /** Open-Meteo hourly timestamp format, UTC (e.g. {@code 2026-07-11T07:00}). */
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final OpenMeteoMarineApi marineApi;

    /**
     * Constructs a {@code MarineClient}.
     *
     * @param marineApi the declarative Open-Meteo Marine HTTP proxy
     */
    public MarineClient(OpenMeteoMarineApi marineApi) {
        this.marineApi = marineApi;
    }

    /**
     * Fetches the 7-day hourly sea-state series for a coastal location.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return the deserialised marine response (hourly arrays may be empty for a land grid cell)
     */
    @Retry(name = "open-meteo-briefing")
    @CircuitBreaker(name = "open-meteo-briefing")
    @RateLimiter(name = "open-meteo")
    public OpenMeteoMarineResponse fetchMarine(double lat, double lon) {
        return marineApi.getMarine(lat, lon, HOURLY_PARAMS, TIMEZONE, FORECAST_DAYS);
    }

    /**
     * Picks the sea-state sample nearest to the given event time from a fetched marine series.
     *
     * <p>The event time is rounded to the nearest whole hour and matched against the marine
     * {@code hourly.time} strings (both UTC). Returns empty when the response has no series, the
     * hour is outside the fetched window, or the grid cell returned no wave value (a land point).
     *
     * @param response    a response from {@link #fetchMarine(double, double)}, or null
     * @param eventTimeUtc the UTC event time to sample at (e.g. high water), or null
     * @return the sea-state sample at that hour, or empty when unavailable
     */
    public Optional<MarineWaveSample> sampleAt(OpenMeteoMarineResponse response, LocalDateTime eventTimeUtc) {
        if (response == null || response.getHourly() == null || eventTimeUtc == null) {
            return Optional.empty();
        }
        OpenMeteoMarineResponse.Hourly hourly = response.getHourly();
        List<String> times = hourly.getTime();
        if (times == null || times.isEmpty()) {
            return Optional.empty();
        }
        String key = eventTimeUtc.plusMinutes(HALF_HOUR_MINUTES).truncatedTo(ChronoUnit.HOURS).format(HOUR_FORMAT);
        int idx = times.indexOf(key);
        if (idx < 0) {
            return Optional.empty();
        }
        Double wave = doubleAt(hourly.getWaveHeight(), idx);
        if (wave == null) {
            return Optional.empty();
        }
        return Optional.of(new MarineWaveSample(
                wave, doubleAt(hourly.getSwellWaveHeight(), idx), intAt(hourly.getWaveDirection(), idx)));
    }

    private static Double doubleAt(List<Double> values, int idx) {
        return values != null && idx < values.size() ? values.get(idx) : null;
    }

    private static Integer intAt(List<Integer> values, int idx) {
        return values != null && idx < values.size() ? values.get(idx) : null;
    }
}
