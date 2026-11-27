package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A reader's favourites, served already merged (find-and-favourites-plan D2): the client never walks area
 * membership.
 *
 * <p>Personal data: it lives under {@code /api/user/settings/favourites}, which {@code HttpCachingConfig} never
 * caches, and it rides on no shared payload.
 *
 * @param places      the starred places, oldest star first (a disabled location is kept here)
 * @param areas       the starred areas, oldest star first
 * @param locationIds the starred places plus the members of the starred areas, de-duplicated, ENABLED
 *                    locations only, in id order. Watched dates contribute nothing here
 * @param dates       the watched almanac dates (one-screen-plan D16), oldest watch first; a date whose start passed
 *                    more than the grace period ago is not served
 */
public record FavouritesResponse(List<Place> places, List<Area> areas, List<Long> locationIds, List<Date> dates) {

    /**
     * A starred place.
     *
     * @param locationId the location
     * @param createdAt  when it was starred (UTC wall clock)
     */
    public record Place(Long locationId, LocalDateTime createdAt) {
    }

    /**
     * A starred area.
     *
     * @param areaId    the area
     * @param createdAt when it was starred (UTC wall clock)
     */
    public record Area(Long areaId, LocalDateTime createdAt) {
    }

    /**
     * A watched almanac date.
     *
     * @param key       the lower-kebab type, a colon and the ISO start date
     * @param createdAt when it was watched (UTC wall clock)
     */
    public record Date(String key, LocalDateTime createdAt) {
    }
}
