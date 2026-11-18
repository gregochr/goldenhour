package com.gregochr.goldenhour.model;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A reader's favourites as Ask reads them (find-and-favourites-plan F4b): the ENABLED locations that count as
 * favourites (starred places plus members of starred areas), and for each member of a starred area, the starred
 * area's name. Personal data: it lives on the asker's {@code AskUserContext} and reaches the model only when
 * {@code rank_spots} is called with {@code favourites: true}.
 *
 * @param locationIds    the favourite locations, enabled only
 * @param areaByLocation a starred area's name for each member location (the alphabetically first when a location
 *                       is in several starred areas); a place starred on its own has no entry
 */
public record FavouriteLocations(Set<Long> locationIds, Map<Long, String> areaByLocation) {

    /** No favourites. */
    public static final FavouriteLocations NONE = new FavouriteLocations(Set.of(), Map.of());

    /** Canonical constructor: takes immutable copies; null reads as empty. */
    public FavouriteLocations {
        locationIds = locationIds == null ? Set.of() : Set.copyOf(locationIds);
        areaByLocation = areaByLocation == null ? Map.of() : Map.copyOf(areaByLocation);
    }

    /**
     * Whether the reader has any favourite.
     *
     * @return true when at least one location is a favourite
     */
    public boolean isEmpty() {
        return locationIds.isEmpty();
    }

    /**
     * A stable fingerprint of the favourites, for a personal answer's cache key: the sorted location ids and the
     * sorted (location, area name) pairs, joined. It is the content itself, never a hash of it, so two different
     * sets can never collide, and a starred area's rename changes it (the answer named the area). Null for no
     * favourites, so a reader who never stars keeps the key they always had.
     *
     * @return the fingerprint, or null when empty
     */
    public String fingerprint() {
        if (locationIds.isEmpty()) {
            return null;
        }
        String ids = new TreeSet<>(locationIds).stream().map(String::valueOf).collect(Collectors.joining(","));
        String areas = new TreeMap<>(areaByLocation).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(";"));
        return ids + "|" + areas;
    }
}
