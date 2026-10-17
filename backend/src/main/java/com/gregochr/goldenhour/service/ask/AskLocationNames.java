package com.gregochr.goldenhour.service.ask;

import java.util.Map;

/**
 * The enabled locations by id — name and coordinates — for the two things the live snapshot cannot
 * say: what an earlier pick of a thread was called once its slot has left the forecast, and how far
 * a spot is from home ({@code rank_spots.nearHome}; {@link AskSnapshot.Slot} carries no coordinates).
 * A seam over the repository so both can be tested without a database. Read lazily, and read once
 * per conversation: {@link #memoised} is what a conversation hands to both readers.
 */
@FunctionalInterface
interface AskLocationNames {

    /** Knows no places: every dropped pick reads as {@code a place no longer listed}. */
    AskLocationNames NONE = Map::of;

    /**
     * An enabled location as the Ask engine needs it.
     *
     * @param name the location name
     * @param lat  the latitude
     * @param lon  the longitude
     */
    record Place(String name, double lat, double lon) {
    }

    /**
     * Every enabled location, by id.
     *
     * @return the places
     */
    Map<Long, Place> byId();

    /**
     * A view of a lookup that reads its delegate at most once, on first use.
     *
     * @param delegate the lookup to memoise
     * @return the memoised lookup
     */
    static AskLocationNames memoised(AskLocationNames delegate) {
        return new AskLocationNames() {
            private Map<Long, Place> loaded;

            @Override
            public synchronized Map<Long, Place> byId() {
                if (loaded == null) {
                    loaded = delegate.byId();
                }
                return loaded;
            }
        };
    }
}
