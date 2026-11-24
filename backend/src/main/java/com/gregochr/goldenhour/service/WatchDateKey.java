package com.gregochr.goldenhour.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The key of a watched almanac date (one-screen-plan D16): {@code <lower-kebab type>:<yyyy-mm-dd start date>}, for
 * example {@code king-tide:2026-12-03}.
 *
 * <p>The type is one an {@code *AlmanacSource} serves (its {@code TYPE} constant), and the date is the entry's START
 * date. It is deliberately not the feed entry's id ({@code type:start:end}), because a tide run's end date moves as the
 * forecast firms up; the client matches a watch to an entry by type family and an overlapping date range.
 *
 * <p>{@code nlc-season} is deliberately not watchable: the feed clips a season's served start to its first day, so
 * the key's start slides forward every day, and the 14-day expiry would end the watch two weeks into an eleven-week
 * season.
 */
public final class WatchDateKey {

    /** Every almanac type a reader may watch: the ones the almanac sources serve, bar the clipped NLC season. */
    static final Set<String> TYPES = Set.of(
            TideAlmanacSource.TYPE_SPRING,
            TideAlmanacSource.TYPE_KING,
            SupermoonAlmanacSource.TYPE,
            MeteorAlmanacSource.TYPE,
            EclipseAlmanacSource.TYPE,
            LunarEclipseAlmanacSource.TYPE,
            SolarAlignmentAlmanacSource.TYPE_EQUINOX,
            SolarAlignmentAlmanacSource.TYPE_SOLSTICE);

    /** The separator between the type and the date. */
    private static final char SEPARATOR = ':';

    /** What a stored key looks like, whatever its type: used only to un-watch, never to watch. */
    private static final Pattern STORABLE = Pattern.compile("[a-z0-9-]+:[A-Za-z0-9-]+");

    /** The longest key the {@code almanac_key} column holds. */
    private static final int MAX_LENGTH = 64;

    private WatchDateKey() {
    }

    /**
     * Validates a key to watch and returns its start date.
     *
     * @param key   the key as sent by the client; may be null
     * @param today the UK civil today; a start beyond the almanac's own horizon from it is refused
     * @return the start date the key names
     * @throws IllegalArgumentException if the key is not {@code <known type>:<ISO date>} within the horizon (HTTP 400)
     */
    public static LocalDate parse(String key, LocalDate today) {
        LocalDate start = tryParse(key);
        if (start == null || start.isAfter(today.plusDays(AlmanacService.DEFAULT_DAYS))) {
            throw new IllegalArgumentException("Not a date that can be watched.");
        }
        return start;
    }

    /**
     * The start date of a key, or null when it is not a known type and an ISO date. Never throws, so a stored row whose
     * type has since been withdrawn is skipped on a read and deleted on a write instead of failing the request.
     *
     * @param key a key; may be null
     * @return its start date, or null
     */
    static LocalDate tryParse(String key) {
        int cut = key == null ? -1 : key.indexOf(SEPARATOR);
        if (cut < 0 || !TYPES.contains(key.substring(0, cut))) {
            return null;
        }
        try {
            return LocalDate.parse(key.substring(cut + 1));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Checks a key to un-watch: shape and length only, not type, so a row stored under a withdrawn type can still be
     * removed.
     *
     * @param key the key as sent by the client; may be null
     * @throws IllegalArgumentException if it cannot be a stored key (HTTP 400)
     */
    public static void requireStorable(String key) {
        if (key == null || key.length() > MAX_LENGTH || !STORABLE.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a date that can be watched.");
        }
    }
}
