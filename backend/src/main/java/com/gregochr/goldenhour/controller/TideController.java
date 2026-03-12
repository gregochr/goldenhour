package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.TideService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for tide extreme data.
 *
 * <p>Exposes a single endpoint that returns all tide extremes for a given location
 * and UTC calendar day, ordered chronologically. Intended to drive the daily tide
 * schedule display in the map popup and forecast card UI.
 */
@RestController
@RequestMapping("/api/tides")
public class TideController {

    private final LocationService locationService;
    private final TideService tideService;

    /**
     * Constructs a {@code TideController}.
     *
     * @param locationService service for resolving location names to entities
     * @param tideService     service for querying stored tide extremes
     */
    public TideController(LocationService locationService, TideService tideService) {
        this.locationService = locationService;
        this.tideService = tideService;
    }

    /**
     * Returns all tide extremes for a location on a given UTC calendar day.
     *
     * <p>Extremes are ordered chronologically by event time ascending.
     * Returns an empty array if no extremes are stored for that location and date
     * (e.g. non-coastal location or weekly refresh not yet run).
     *
     * @param locationName the configured location name
     * @param date         the target date in ISO format {@code yyyy-MM-dd} (UTC)
     * @return list of tide extremes for that day
     * @throws java.util.NoSuchElementException if no location with {@code locationName} exists (→ 404)
     */
    @GetMapping
    public List<TideExtremeEntity> getTidesForDate(
            @RequestParam String locationName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocationEntity location = locationService.findByName(locationName);
        return tideService.getTidesForDate(location.getId(), date);
    }

    /**
     * Returns aggregate tide height statistics for a location from all stored historical data.
     *
     * <p>Returns 204 No Content if no tide extremes are stored for the location.
     *
     * @param locationName the configured location name
     * @return tide stats or 204
     */
    @GetMapping("/stats")
    public ResponseEntity<TideStats> getTideStats(@RequestParam String locationName) {
        LocationEntity location = locationService.findByName(locationName);
        return tideService.getTideStats(location.getId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Returns tide height statistics for all coastal locations that have stored extremes.
     *
     * <p>The response is a map keyed by location name. Inland locations and coastal
     * locations with no stored data are omitted.
     *
     * @return map of location name to tide stats
     */
    @GetMapping("/stats/all")
    public Map<String, TideStats> getAllTideStats() {
        Map<String, TideStats> result = new LinkedHashMap<>();
        for (LocationEntity loc : locationService.findAllEnabled()) {
            if (!locationService.isCoastal(loc)) {
                continue;
            }
            tideService.getTideStats(loc.getId()).ifPresent(stats -> result.put(loc.getName(), stats));
        }
        return result;
    }
}
