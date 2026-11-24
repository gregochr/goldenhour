package com.gregochr.goldenhour.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * The key of a watched almanac date (one-screen-plan D16): {@code <lower-kebab type>:<yyyy-mm-dd start date>}, for
 * example {@code king-tide:2026-12-03}.
 *
 * <p>The type is one an {@code *AlmanacSource} serves (its {@code TYPE} constant), and the date is the entry's START
 * date. It is deliberately not the feed entry's id ({@code type:start:end}), because a tide run's end date moves as the
 * forecast firms up; the client matches a watch to an entry by type family and an overlapping date range.
 */
public final class WatchDateKey {

    /** Every almanac type a reader may watch: the ones the almanac sources serve. */
    static final Set<String> TYPES = Set.of(
            TideAlmanacSource.TYPE_SPRING,
            TideAlmanacSource.TYPE_KING,
            SupermoonAlmanacSource.TYPE,
            MeteorAlmanacSource.TYPE,
            NlcSeasonAlmanacSource.TYPE,
            EclipseAlmanacSource.TYPE,
            LunarEclipseAlmanacSource.TYPE,
            SolarAlignmentAlmanacSource.TYPE_EQUINOX,
            SolarAlignmentAlmanacSource.TYPE_SOLSTICE);

    /** The separator between the type and the date. */
    private static final char SEPARATOR = ':';

    private WatchDateKey() {
    }

    /**
     * Validates a key and returns its start date.
     *
     * @param key the key as sent by the client; may be null
     * @return the start date the key names
     * @throws IllegalArgumentException if the key is not {@code <known type>:<ISO date>} (HTTP 400)
     */
    public static LocalDate parse(String key) {
        int cut = key == null ? -1 : key.indexOf(SEPARATOR);
        if (cut < 0 || !TYPES.contains(key.substring(0, cut))) {
            throw new IllegalArgumentException("Not a date that can be watched: " + key);
        }
        return startDateOf(key);
    }

    /**
     * The start date of an already validated key (one the service stored).
     *
     * @param key a key {@link #parse} accepted
     * @return its start date
     * @throws IllegalArgumentException if the date part is not an ISO date (HTTP 400)
     */
    static LocalDate startDateOf(String key) {
        try {
            return LocalDate.parse(key.substring(key.indexOf(SEPARATOR) + 1));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Not a date that can be watched: " + key, e);
        }
    }
}
