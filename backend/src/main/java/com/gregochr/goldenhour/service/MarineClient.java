package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenMeteoMarineApi;
import com.gregochr.goldenhour.model.MarineWavePeak;
import com.gregochr.goldenhour.model.MarineWaveSample;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resilient wrapper around the Open-Meteo Marine Weather API, plus the sea-state selection helper.
 *
 * <p>Fetches a 7-day hourly sea-state series for a coastal location and picks the significant wave
 * height at a specific event time (the sunrise or sunset the briefing samples). Called from the briefing
 * triage pipeline — never from a {@code HotTopicStrategy}, which must stay API-call-free. Uses the
 * isolated {@code open-meteo-briefing} circuit breaker so a marine outage cannot trip the
 * forecast-run breaker (and vice versa), sharing the {@code open-meteo} rate limiter.
 */
@Service
public class MarineClient {

    /** Coordinates per batched request; callers chunk to this so one failure costs one chunk. */
    public static final int BATCH_COORD_LIMIT = 20;

    /**
     * One coordinate of a batched marine request.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     */
    public record Point(double lat, double lon) { }

    private static final String HOURLY_PARAMS =
            "wave_height,swell_wave_height,wave_direction,wave_period,swell_wave_period";
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
     * Fetches the 7-day hourly sea-state series for several locations in one request.
     *
     * <p>The caller chunks at {@link #BATCH_COORD_LIMIT}. A single coordinate goes through the
     * single-point exchange because Open-Meteo answers one point with a bare object, not an array.
     * A response whose length differs from the request is rejected, since a shifted list would pin
     * one location's waves to another.
     *
     * @param coords the points to fetch, at most {@link #BATCH_COORD_LIMIT}
     * @return one response per coordinate, in request order (an element may be null)
     */
    @Retry(name = "open-meteo-briefing")
    @CircuitBreaker(name = "open-meteo-briefing")
    @RateLimiter(name = "open-meteo")
    public List<OpenMeteoMarineResponse> fetchMarineBatch(List<Point> coords) {
        if (coords.size() == 1) {
            Point only = coords.getFirst();
            return Collections.singletonList(
                    marineApi.getMarine(only.lat(), only.lon(), HOURLY_PARAMS, TIMEZONE, FORECAST_DAYS));
        }
        String latitudes = coords.stream().map(c -> String.valueOf(c.lat())).collect(Collectors.joining(","));
        String longitudes = coords.stream().map(c -> String.valueOf(c.lon())).collect(Collectors.joining(","));
        List<OpenMeteoMarineResponse> responses =
                marineApi.getMarineBatch(latitudes, longitudes, HOURLY_PARAMS, TIMEZONE, FORECAST_DAYS);
        if (responses == null || responses.size() != coords.size()) {
            throw new IllegalStateException("Marine batch returned "
                    + (responses == null ? "no body" : responses.size() + " responses")
                    + " for " + coords.size() + " coordinates");
        }
        return responses;
    }

    /**
     * Picks the sea-state sample nearest to the given event time from a fetched marine series.
     *
     * <p>The event time is rounded to the nearest whole hour and matched against the marine
     * {@code hourly.time} strings (both UTC). Returns empty when the response has no series, the
     * hour is outside the fetched window, or the grid cell returned no wave value (a land point).
     *
     * @param response    a response from {@link #fetchMarineBatch}, or null
     * @param eventTimeUtc the UTC event time to sample at (e.g. sunrise or sunset), or null
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

    /**
     * Finds the highest-wave hour inside {@code [fromUtc, toUtc]} (both inclusive) of a fetched series.
     *
     * <p>The peak is the hourly stamp with the greatest {@code wave_height} among stamps inside the
     * window, ties going to the earlier hour; stamps with no wave value are ignored. When no stamp
     * falls inside (a window clipped to under an hour) it falls back to the hour nearest the window's
     * midpoint, which may be just outside the window. Empty on a land cell (no wave values), a missing
     * series, or a window with no usable hour.
     *
     * @param response a response from {@link #fetchMarineBatch}, or null
     * @param fromUtc  window start, UTC, or null
     * @param toUtc    window end, UTC, or null
     * @return the peak hour's sea state, or empty when unavailable
     */
    public Optional<MarineWavePeak> peakInWindow(OpenMeteoMarineResponse response,
            LocalDateTime fromUtc, LocalDateTime toUtc) {
        if (response == null || response.getHourly() == null || fromUtc == null || toUtc == null
                || toUtc.isBefore(fromUtc)) {
            return Optional.empty();
        }
        OpenMeteoMarineResponse.Hourly hourly = response.getHourly();
        List<String> times = hourly.getTime();
        if (times == null || times.isEmpty()) {
            return Optional.empty();
        }
        int best = -1;
        Double bestHeight = null;
        boolean anyInside = false;
        for (int i = 0; i < times.size(); i++) {
            LocalDateTime stamp = parseStamp(times.get(i));
            if (stamp == null || stamp.isBefore(fromUtc) || stamp.isAfter(toUtc)) {
                continue;
            }
            anyInside = true;
            Double height = doubleAt(hourly.getWaveHeight(), i);
            if (height != null && (bestHeight == null || height > bestHeight)) {
                best = i;
                bestHeight = height;
            }
        }
        if (!anyInside) {
            LocalDateTime midpoint = fromUtc.plus(Duration.between(fromUtc, toUtc).dividedBy(2));
            best = times.indexOf(
                    midpoint.plusMinutes(HALF_HOUR_MINUTES).truncatedTo(ChronoUnit.HOURS).format(HOUR_FORMAT));
            bestHeight = best < 0 ? null : doubleAt(hourly.getWaveHeight(), best);
        }
        if (best < 0 || bestHeight == null) {
            return Optional.empty();
        }
        return Optional.of(new MarineWavePeak(
                parseStamp(times.get(best)), bestHeight, intAt(hourly.getWaveDirection(), best),
                doubleAt(hourly.getWavePeriod(), best), doubleAt(hourly.getSwellWaveHeight(), best),
                doubleAt(hourly.getSwellWavePeriod(), best)));
    }

    private static LocalDateTime parseStamp(String stamp) {
        try {
            return stamp == null ? null : LocalDateTime.parse(stamp, HOUR_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Double doubleAt(List<Double> values, int idx) {
        return values != null && idx < values.size() ? values.get(idx) : null;
    }

    private static Integer intAt(List<Integer> values, int idx) {
        return values != null && idx < values.size() ? values.get(idx) : null;
    }
}
