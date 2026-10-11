package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.repository.TideExtremeRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable view of the stored {@code tide_extreme} rows for a set of locations over one time
 * window, loaded with a single query and living for one briefing build.
 *
 * <p>Before this existed each coastal slot read the same location's rows two or three times
 * ({@code TideService}'s derivation, the tide-fit curve, and a MID want's midpoint lookup), about
 * 1,200 to 1,400 queries per build. A snapshot answers the identical question, "this location's
 * extremes between {@code from} and {@code to}, inclusive, ascending", from memory.
 *
 * <p><b>Never a silent empty.</b> A location the snapshot did not load, or a range outside the
 * loaded window, falls back to the per-location repository query rather than returning an empty
 * list, because empty means "no tide" downstream and would quietly drop a coastal slot. It is a
 * fallback and not an exception because an exception would abort the whole build.
 *
 * <p>It is never cached across builds, so no eviction rule applies.
 */
public final class TideExtremeSnapshot {

    /**
     * Days either side of a solar event that every tide read covers. The single source for
     * {@code TideService}'s extremes fetch and {@code BriefingSlotBuilder}'s curve fetch.
     */
    public static final int READ_WINDOW_DAYS = 2;

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * The naive-UTC span a snapshot was loaded over, both ends inclusive.
     *
     * @param from start of the window
     * @param to   end of the window
     */
    public record Window(LocalDateTime from, LocalDateTime to) {

        boolean contains(LocalDateTime start, LocalDateTime end) {
            return !start.isBefore(from) && !end.isAfter(to);
        }
    }

    private final Map<Long, List<TideExtremeEntity>> byLocation;
    private final Set<Long> coveredIds;
    private final Window window;
    private final int rowCount;

    private TideExtremeSnapshot(Map<Long, List<TideExtremeEntity>> byLocation, Set<Long> coveredIds,
            Window window, int rowCount) {
        this.byLocation = byLocation;
        this.coveredIds = coveredIds;
        this.window = window;
        this.rowCount = rowCount;
    }

    /**
     * The window that covers every read {@link #READ_WINDOW_DAYS} either side of a solar event on
     * any of the given dates, as naive UTC.
     *
     * <p>Starts at London midnight {@code READ_WINDOW_DAYS + 1} days before the first date and ends
     * at London midnight {@code READ_WINDOW_DAYS + 2} days after the last, so a solar event at any
     * hour of a date's UK day, however BST shifts it against UTC, is covered.
     *
     * @param dates the build's dates, ascending and non-empty
     * @return the window to load
     */
    static Window windowFor(List<LocalDate> dates) {
        LocalDateTime from = dates.getFirst().minusDays(READ_WINDOW_DAYS + 1L)
                .atStartOfDay(LONDON).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = dates.getLast().plusDays(READ_WINDOW_DAYS + 2L)
                .atStartOfDay(LONDON).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return new Window(from, to);
    }

    /**
     * Loads the rows for the given locations over the window with one query (none for no ids).
     *
     * @param repo        the tide extreme repository
     * @param locationIds the locations to cover
     * @param window      the span to load
     * @return the snapshot
     */
    static TideExtremeSnapshot load(TideExtremeRepository repo, Collection<Long> locationIds,
            Window window) {
        Set<Long> ids = new HashSet<>(locationIds);
        if (ids.isEmpty()) {
            return new TideExtremeSnapshot(Map.of(), Set.of(), window, 0);
        }
        List<TideExtremeEntity> rows = repo.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                ids, window.from(), window.to());
        Map<Long, List<TideExtremeEntity>> grouped = new HashMap<>();
        for (TideExtremeEntity row : rows) {
            grouped.computeIfAbsent(row.getLocationId(), k -> new ArrayList<>()).add(row);
        }
        return new TideExtremeSnapshot(grouped, ids, window, rows.size());
    }

    /**
     * The location's extremes between {@code from} and {@code to}, inclusive at both ends, ascending
     * by event time. Falls back to the per-location repository query when the location was not
     * loaded or the range is not inside the loaded window.
     *
     * @param repo       the repository used for the fallback
     * @param locationId the location primary key (null falls back to the live query)
     * @param from       range start (inclusive)
     * @param to         range end (inclusive)
     * @return the extremes in the range, possibly empty
     */
    public List<TideExtremeEntity> between(TideExtremeRepository repo, Long locationId,
            LocalDateTime from, LocalDateTime to) {
        if (locationId == null || !coveredIds.contains(locationId)
                || !window.contains(from, to)) {
            return repo.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(locationId, from, to);
        }
        List<TideExtremeEntity> rows = byLocation.getOrDefault(locationId, List.of());
        List<TideExtremeEntity> out = new ArrayList<>();
        for (TideExtremeEntity row : rows) {
            LocalDateTime t = row.getEventTime();
            if (!t.isBefore(from) && !t.isAfter(to)) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * Number of rows loaded, for the build's debug line.
     *
     * @return the loaded row count
     */
    public int rowCount() {
        return rowCount;
    }
}
