package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.model.DrivePointCandidate;
import com.gregochr.goldenhour.model.PlaceResult;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.util.GeoUtils;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the places an admin can choose as a location's drive point (reach-scoped-card-plan R1c): a village name or
 * a postcode, nearest to the location first.
 *
 * <p>A name goes to postcodes.io's place search, which is unsorted and nationwide ("Newton" has dozens), so the
 * matches are sorted by straight-line distance from the location and cut at {@link #MAX_DISTANCE_KM}. A postcode goes
 * through the existing postcode lookup and is offered as it is, nearest or not: the admin typed that exact place.
 */
@Service
public class DrivePointLookupService {

    /**
     * Furthest a named match may be from the location. A drive point is the nearest reachable place, so it is close
     * by definition; 25 km catches a Glenridding in another county from a summit on the boundary while keeping a
     * same-named village across the country out of the list. The same figure bounds a tide point
     * ({@code LocationService.MAX_TIDE_POINT_DISTANCE_KM}).
     */
    static final double MAX_DISTANCE_KM = com.gregochr.goldenhour.model.DrivePoint.MAX_DISTANCE_KM;

    /** How many matches the picker shows. */
    private static final int MAX_RESULTS = 10;

    private static final double TENTH = 10.0;

    /** A UK postcode with or without its space: outward code (one or two letters, a digit, an optional character). */
    private static final Pattern POSTCODE = Pattern.compile("^[A-Za-z]{1,2}\\d[A-Za-z\\d]?\\s*\\d[A-Za-z]{2}$");

    private final PostcodesIoClient postcodesIoClient;

    /**
     * Constructs the service.
     *
     * @param postcodesIoClient the postcode and place-name lookups
     */
    public DrivePointLookupService(PostcodesIoClient postcodesIoClient) {
        this.postcodesIoClient = postcodesIoClient;
    }

    /**
     * Searches by place name or postcode.
     *
     * @param query the village name or postcode typed by the admin
     * @param lat   the location's latitude, for sorting and the distance cut
     * @param lon   the location's longitude
     * @return up to ten candidates, nearest first; empty when nothing matches (not an error)
     * @throws IllegalArgumentException                                     if the query is blank (HTTP 400)
     * @throws com.gregochr.goldenhour.client.PostcodeLookupException        if the postcode is unknown (HTTP 400)
     * @throws com.gregochr.goldenhour.client.PostcodeLookupUnavailableException if postcodes.io fails (HTTP 502)
     */
    public List<DrivePointCandidate> search(String query, double lat, double lon) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Type a village name or a postcode");
        }
        String trimmed = query.trim();
        if (POSTCODE.matcher(trimmed).matches()) {
            PostcodeLookupResult postcode = postcodesIoClient.lookup(trimmed);
            return List.of(fromPostcode(postcode, lat, lon));
        }
        return postcodesIoClient.searchPlaces(trimmed).stream()
                .filter(Objects::nonNull)
                .map(place -> fromPlace(place, lat, lon))
                .filter(candidate -> candidate.distanceKm() <= MAX_DISTANCE_KM)
                .sorted(Comparator.comparingDouble(DrivePointCandidate::distanceKm))
                .limit(MAX_RESULTS)
                .toList();
    }

    private static DrivePointCandidate fromPlace(PlaceResult place, double lat, double lon) {
        String detail = Stream.of(place.localType(), place.countyUnitary() != null
                        ? place.countyUnitary() : place.region())
                .filter(Objects::nonNull)
                .filter(part -> !part.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return new DrivePointCandidate(place.name(), detail, place.latitude(), place.longitude(),
                roundedKm(lat, lon, place.latitude(), place.longitude()));
    }

    private static DrivePointCandidate fromPostcode(PostcodeLookupResult postcode, double lat, double lon) {
        // The place name reads "Patterdale, Westmorland and Furness": the part before the comma is the village.
        String placeName = postcode.placeName() == null ? postcode.postcode() : postcode.placeName();
        int comma = placeName.indexOf(',');
        String name = comma > 0 ? placeName.substring(0, comma).trim() : placeName;
        return new DrivePointCandidate(name, postcode.postcode() + ", " + placeName, postcode.latitude(),
                postcode.longitude(), roundedKm(lat, lon, postcode.latitude(), postcode.longitude()));
    }

    private static double roundedKm(double lat, double lon, double otherLat, double otherLon) {
        return Math.round(GeoUtils.distanceKm(lat, lon, otherLat, otherLon) * TENTH) / TENTH;
    }
}
