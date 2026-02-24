package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TideState;
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
 * <p>Retrieves tide surface elevation data and classifies it as HIGH, LOW, or MID
 * based on proximity to the nearest tidal peak or trough. Returns data as an
 * immutable {@link TideData} record.
 */
@Service
public class TideService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a {@code TideService}.
     *
     * @param webClient    shared WebClient for outbound HTTP calls
     * @param objectMapper Jackson mapper for JSON parsing
     */
    public TideService(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches tide data for a coastal location at a specific solar event time.
     *
     * <p>Currently returns empty Optional. Tide data fetching from Open-Meteo Marine API
     * is planned for a future iteration. The service gracefully handles missing tide data.
     *
     * @param lat            latitude in decimal degrees
     * @param lon            longitude in decimal degrees
     * @param solarEventTime UTC time of sunrise or sunset being evaluated
     * @return Optional containing TideData if fetch succeeds, empty otherwise
     */
    public Optional<TideData> getTideData(double lat, double lon, LocalDateTime solarEventTime) {
        // TODO: Implement tide data fetching from Open-Meteo Marine API
        return Optional.empty();
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
     * Checks if a tide state matches a location's tide type preference.
     *
     * @param tideState the current tide state
     * @param tideType  the location's preference
     * @return true if the state matches the preference
     */
    private boolean tideStateMatches(TideState tideState, TideType tideType) {
        return switch (tideType) {
            case HIGH_TIDE -> tideState == TideState.HIGH;
            case LOW_TIDE -> tideState == TideState.LOW;
            case MID_TIDE -> tideState == TideState.MID;
            case ANY_TIDE -> true;
            case NOT_COASTAL -> false;
        };
    }
}
