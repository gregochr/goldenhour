package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * States a tide run's real first and last date, which can lie outside the detect window.
 *
 * <p>The tide strategies only see the days inside the briefing window, so {@code dayNumber} and
 * {@code dayCount} describe the windowed slice of a run. The lanes need to know whether the run
 * began before, or continues after, what is served. This walks outward from a window edge, and only
 * from a window edge: inside the window the candidates are the truth, so a non-candidate in-window
 * day never extends a run.
 *
 * <p>A walked day belongs to the run when its nearest syzygy has the run's kind (perigean or not)
 * and it is tidal: either the lunar arithmetic calls it near syzygy, or the roster's measured
 * heights clear a spring threshold that day. Each walk is capped at {@value #MAX_RUN_REACH_DAYS}
 * days. No external call is made.
 */
@Component
public class TideRunExtent {

    /** The furthest a run is walked beyond the window edge, in days. */
    static final int MAX_RUN_REACH_DAYS = 7;

    private final LunarPhaseService lunarPhaseService;
    private final TideSizeIndex tideSizeIndex;

    /**
     * Constructs a {@code TideRunExtent}.
     *
     * @param lunarPhaseService decides which kind of run a date belongs to
     * @param tideSizeIndex     measures which dates carry spring-sized water
     */
    public TideRunExtent(LunarPhaseService lunarPhaseService, TideSizeIndex tideSizeIndex) {
        this.lunarPhaseService = lunarPhaseService;
        this.tideSizeIndex = tideSizeIndex;
    }

    /**
     * A run's real first and last date.
     *
     * @param start the first date of the run
     * @param end   the last date of the run
     */
    public record Extent(LocalDate start, LocalDate end) { }

    /**
     * Computes the real extent of the run formed by the given candidate days.
     *
     * @param candidates the dates inside the window that carry a topic, chronological
     * @param king       true for a king (perigean) run, false for a spring run
     * @param fromDate   first date of the detect window
     * @param toDate     last date of the detect window
     * @param coastal    the coastal roster the size index is measured against
     * @return the extent, or null when there are no candidates
     */
    public Extent extentOf(List<LocalDate> candidates, boolean king, LocalDate fromDate,
            LocalDate toDate, List<LocationEntity> coastal) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        LocalDate start = candidates.get(0);
        LocalDate end = candidates.get(candidates.size() - 1);
        boolean walkBack = start.equals(fromDate);
        boolean walkForward = end.equals(toDate);
        if (!walkBack && !walkForward) {
            return new Extent(start, end);
        }
        TideSizeIndex.Sizes sizes = tideSizeIndex.measure(coastal,
                fromDate.minusDays(MAX_RUN_REACH_DAYS), toDate.plusDays(MAX_RUN_REACH_DAYS));
        if (walkBack) {
            LocalDate d = fromDate.minusDays(1);
            for (int i = 0; i < MAX_RUN_REACH_DAYS && belongs(d, king, sizes); i++) {
                start = d;
                d = d.minusDays(1);
            }
        }
        if (walkForward) {
            LocalDate d = toDate.plusDays(1);
            for (int i = 0; i < MAX_RUN_REACH_DAYS && belongs(d, king, sizes); i++) {
                end = d;
                d = d.plusDays(1);
            }
        }
        return new Extent(start, end);
    }

    private boolean belongs(LocalDate date, boolean king, TideSizeIndex.Sizes sizes) {
        if (lunarPhaseService.nearestSyzygyIsPerigean(date) != king) {
            return false;
        }
        return lunarPhaseService.classifyTide(date) != LunarTideType.REGULAR_TIDE
                || sizes.springOn(date);
    }
}
