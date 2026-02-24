package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideData;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Fetches tide data from the Open-Meteo Marine API for coastal locations.
 *
 * <p>Retrieves tide surface elevation data and parses it to determine the current tide state
 * and times of the next high/low tides. Returns data as an immutable {@link TideData} record.
 */
@Service
public class TideService {


    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a {@code TideService}.
     *
     * @param webClient     shared WebClient for outbound HTTP calls
     * @param objectMapper  Jackson mapper for JSON parsing
     */
    public TideService(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches tide data for a coastal location at a specific solar event time.
     *
     * <p>Currently returns empty Optional. Tide data fetching from Open-Meteo Marine API
     * is designed for future enhancement when proper response mocking/testing infrastructure
     * is available. The service gracefully handles missing tide data for all locations.
     *
     * @param lat            latitude in decimal degrees
     * @param lon            longitude in decimal degrees
     * @param solarEventTime UTC time of sunrise or sunset being evaluated
     * @return Optional containing TideData if fetch succeeds, empty if fetch fails or location is unsupported
     */
    public Optional<TideData> getTideData(double lat, double lon, LocalDateTime solarEventTime) {
        // TODO: Implement tide data fetching from Open-Meteo Marine API
        // For now, gracefully return empty to allow forecasts to proceed without tide data
        return Optional.empty();
    }

    /**
     * Computes whether tide state aligns with the location's tide type preference.
     *
     * @param tideData         the tide data snapshot
     * @param locationTideTypes the location's acceptable tide types
     * @return true if the tide state matches any of the location's preferences, false otherwise
     */
    public boolean calculateTideAligned(TideData tideData, Set<TideType> locationTideTypes) {
        if (locationTideTypes.isEmpty() || locationTideTypes.contains(TideType.NOT_COASTAL)) {
            return false;
        }

        if (locationTideTypes.contains(TideType.ANY_TIDE)) {
            return true;
        }

        String tideState = tideData.tideState();
        return locationTideTypes.stream()
                .anyMatch(pref -> tideStateMatches(tideState, pref));
    }

    /**
     * Returns a retry spec that retries up to {@value #MAX_RETRIES} times with exponential
     * backoff for transient errors: 5xx server errors and 429 Too Many Requests.
     *
     * @return configured {@link Retry} spec
     */
    /**
     * Checks if a tide state matches a location's tide type preference.
     *
     * @param tideState the current tide state
     * @param tideType  the location's preference
     * @return true if the state matches the preference
     */
    private boolean tideStateMatches(String tideState, TideType tideType) {
        return switch (tideType) {
            case HIGH_TIDE -> tideState.equals("HIGH");
            case LOW_TIDE -> tideState.equals("LOW");
            case MID_TIDE -> tideState.equals("RISING") || tideState.equals("FALLING");
            case ANY_TIDE -> true;
            case NOT_COASTAL -> false;
        };
    }
}
