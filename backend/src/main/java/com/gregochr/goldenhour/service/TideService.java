package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import com.gregochr.goldenhour.model.TideData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fetches tide data from the Open-Meteo Marine API for coastal locations.
 *
 * <p>Retrieves three days of hourly sea surface height data centred on the solar event
 * date, finds high and low tide peaks from the timeseries, then classifies the tide state
 * at the solar event time as HIGH, LOW, or MID.
 *
 * <p>HIGH: solar event is within {@value #HIGH_LOW_THRESHOLD_MINUTES} minutes of a peak.
 * LOW: solar event is within {@value #HIGH_LOW_THRESHOLD_MINUTES} minutes of a trough.
 * MID: everything else — tide is neither fully in nor fully out.
 */
@Service
public class TideService {

    private static final Logger LOG = LoggerFactory.getLogger(TideService.class);

    private static final String MARINE_HOST = "marine-api.open-meteo.com";
    private static final String MARINE_PARAM = "sea_surface_height_above_mean_sea_level";
    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);
    private static final long HIGH_LOW_THRESHOLD_MINUTES = 90;
    private static final int HEIGHT_SCALE = 2;

    private final WebClient webClient;

    /**
     * Constructs a {@code TideService}.
     *
     * @param webClient shared WebClient for outbound HTTP calls
     */
    public TideService(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Fetches tide data for a coastal location at a specific solar event time.
     *
     * <p>Fetches three days of sea surface height data (day before, day of, day after)
     * to ensure surrounding tide peaks are captured. Returns empty if the API is
     * unreachable or does not provide sea level data for the location.
     *
     * @param lat            latitude in decimal degrees
     * @param lon            longitude in decimal degrees
     * @param solarEventTime UTC time of sunrise or sunset being evaluated
     * @return Optional containing TideData if fetch succeeds, empty otherwise
     */
    public Optional<TideData> getTideData(double lat, double lon, LocalDateTime solarEventTime) {
        try {
            OpenMeteoMarineResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https").host(MARINE_HOST).path("/v1/marine")
                            .queryParam("latitude", lat)
                            .queryParam("longitude", lon)
                            .queryParam("hourly", MARINE_PARAM)
                            .queryParam("timezone", "UTC")
                            .queryParam("start_date", solarEventTime.toLocalDate().minusDays(1))
                            .queryParam("end_date", solarEventTime.toLocalDate().plusDays(1))
                            .build())
                    .retrieve()
                    .bodyToMono(OpenMeteoMarineResponse.class)
                    .retryWhen(retryOnTransient())
                    .block();

            if (response == null
                    || response.getHourly() == null
                    || response.getHourly().getTime() == null
                    || response.getHourly().getSeaSurfaceHeight() == null) {
                LOG.warn("Marine API returned no sea level data for lat={}, lon={}", lat, lon);
                return Optional.empty();
            }

            return Optional.of(parseTideData(
                    response.getHourly().getTime(),
                    response.getHourly().getSeaSurfaceHeight(),
                    solarEventTime));

        } catch (Exception e) {
            LOG.warn("Failed to fetch tide data for lat={}, lon={}: {}", lat, lon, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Computes whether the tide state aligns with the location's photographer preference.
     *
     * @param tideData          the tide data snapshot at the solar event time
     * @param locationTideTypes the location's acceptable tide states
     * @return true if the tide state matches any of the location's preferences
     */
    public boolean calculateTideAligned(TideData tideData, Set<TideType> locationTideTypes) {
        if (locationTideTypes.isEmpty()) {
            return false;
        }

        if (locationTideTypes.contains(TideType.ANY_TIDE)) {
            return true;
        }

        TideState tideState = tideData.tideState();
        return locationTideTypes.stream()
                .anyMatch(pref -> tideStateMatches(tideState, pref));
    }

    /**
     * Parses a sea surface height timeseries into a {@link TideData} snapshot.
     *
     * <p>Package-private for unit testing.
     *
     * @param times          ISO-8601 timestamp strings from the API
     * @param heights        sea surface heights in metres (may contain nulls)
     * @param solarEventTime UTC time of the solar event
     * @return tide data snapshot including state and next high/low events
     */
    TideData parseTideData(List<String> times, List<Double> heights, LocalDateTime solarEventTime) {
        List<TideEvent> peaks = findPeaks(times, heights);
        List<TideEvent> troughs = findTroughs(times, heights);

        TideState state = classifyTideState(solarEventTime, peaks, troughs);
        TideEvent nextHigh = firstAfter(peaks, solarEventTime);
        TideEvent nextLow = firstAfter(troughs, solarEventTime);

        return new TideData(
                state,
                nextHigh != null ? nextHigh.time() : null,
                nextHigh != null ? nextHigh.heightMetres() : null,
                nextLow != null ? nextLow.time() : null,
                nextLow != null ? nextLow.heightMetres() : null);
    }

    /**
     * Classifies the tide state at {@code eventTime} given the known peaks and troughs.
     *
     * <p>Package-private for unit testing.
     *
     * @param eventTime UTC time of the solar event
     * @param peaks     high tide events in the timeseries
     * @param troughs   low tide events in the timeseries
     * @return HIGH, LOW, or MID
     */
    TideState classifyTideState(LocalDateTime eventTime,
            List<TideEvent> peaks, List<TideEvent> troughs) {
        boolean nearHigh = peaks.stream().anyMatch(p ->
                Math.abs(ChronoUnit.MINUTES.between(p.time(), eventTime))
                        <= HIGH_LOW_THRESHOLD_MINUTES);
        if (nearHigh) {
            return TideState.HIGH;
        }

        boolean nearLow = troughs.stream().anyMatch(t ->
                Math.abs(ChronoUnit.MINUTES.between(t.time(), eventTime))
                        <= HIGH_LOW_THRESHOLD_MINUTES);
        if (nearLow) {
            return TideState.LOW;
        }

        return TideState.MID;
    }

    /**
     * Finds local maxima (high tide peaks) in the height timeseries.
     *
     * <p>Package-private for unit testing.
     *
     * @param times   ISO-8601 timestamp strings
     * @param heights sea surface heights in metres
     * @return list of high tide events, in chronological order
     */
    List<TideEvent> findPeaks(List<String> times, List<Double> heights) {
        List<TideEvent> peaks = new ArrayList<>();
        for (int i = 1; i < heights.size() - 1; i++) {
            Double prev = heights.get(i - 1);
            Double curr = heights.get(i);
            Double next = heights.get(i + 1);
            if (prev != null && curr != null && next != null && curr > prev && curr > next) {
                peaks.add(new TideEvent(
                        LocalDateTime.parse(times.get(i)),
                        BigDecimal.valueOf(curr).setScale(HEIGHT_SCALE, RoundingMode.HALF_UP)));
            }
        }
        return peaks;
    }

    /**
     * Finds local minima (low tide troughs) in the height timeseries.
     *
     * <p>Package-private for unit testing.
     *
     * @param times   ISO-8601 timestamp strings
     * @param heights sea surface heights in metres
     * @return list of low tide events, in chronological order
     */
    List<TideEvent> findTroughs(List<String> times, List<Double> heights) {
        List<TideEvent> troughs = new ArrayList<>();
        for (int i = 1; i < heights.size() - 1; i++) {
            Double prev = heights.get(i - 1);
            Double curr = heights.get(i);
            Double next = heights.get(i + 1);
            if (prev != null && curr != null && next != null && curr < prev && curr < next) {
                troughs.add(new TideEvent(
                        LocalDateTime.parse(times.get(i)),
                        BigDecimal.valueOf(curr).setScale(HEIGHT_SCALE, RoundingMode.HALF_UP)));
            }
        }
        return troughs;
    }

    private TideEvent firstAfter(List<TideEvent> events, LocalDateTime time) {
        return events.stream()
                .filter(e -> e.time().isAfter(time))
                .findFirst()
                .orElse(null);
    }

    private boolean tideStateMatches(TideState tideState, TideType tideType) {
        return switch (tideType) {
            case HIGH_TIDE -> tideState == TideState.HIGH;
            case LOW_TIDE -> tideState == TideState.LOW;
            case MID_TIDE -> tideState == TideState.MID;
            case ANY_TIDE -> true;
            case NOT_COASTAL -> false;
        };
    }

    private Retry retryOnTransient() {
        return Retry.backoff(MAX_RETRIES, RETRY_BACKOFF)
                .filter(ex -> ex instanceof WebClientResponseException wex
                        && (wex.getStatusCode().is5xxServerError()
                            || wex.getStatusCode().value() == 429));
    }

    /**
     * A high or low tide event identified from the sea surface height timeseries.
     *
     * @param time         UTC time of the peak or trough
     * @param heightMetres sea surface height in metres at that point
     */
    record TideEvent(LocalDateTime time, BigDecimal heightMetres) {
    }
}
