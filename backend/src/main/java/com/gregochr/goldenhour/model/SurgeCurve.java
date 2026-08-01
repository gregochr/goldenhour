package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Hourly storm-surge elevation, in metres above the predicted astronomical tide, for one briefing
 * cycle's coastal locations.
 *
 * <p><b>Why this is a carrier rather than part of the briefing payload.</b> Hot topics are
 * recomputed live on every serve — {@code BriefingService.getCachedBriefing} calls
 * {@code hotTopicAggregator.getHotTopics} unconditionally and rebuilds from the live topics, never
 * from {@code cached.hotTopics()}. A series written into the persisted briefing JSON would
 * therefore be serialised on the build path and thrown away on the first request. Worse, because
 * {@code HotTopic} is a record whose {@code equals} compares every component, a persisted-but-not-
 * live series makes the cache-equality shortcut fail and <em>forces</em> the overwrite. So the
 * series rides an in-memory carrier populated during the build and read back at serve time —
 * the shape {@code NlcClarityService} and {@code MeteorClarityService} already use for exactly
 * this problem.
 *
 * <p><b>Values are {@link Double}, and the list is a {@link List}, deliberately.</b> A {@code
 * double[]} component would break record equality (arrays compare by identity), which is the same
 * cache shortcut described above — every request would rebuild the whole briefing response.
 *
 * <p><b>A null slot is a gap, not a zero.</b> The hourly weather array starts at 00:00 UTC with no
 * past days requested, so during British Summer Time the first hour of the London local day is
 * genuinely absent. Callers must break the drawn path at a null rather than interpolating through
 * it — a line through a gap descends to a value that was never forecast.
 *
 * @param byLocation surge metres keyed by location id, then by local date; each value is 24 hourly
 *                   slots for the Europe/London local day, oldest first, with null where the hour
 *                   could not be computed
 */
public record SurgeCurve(Map<Long, Map<LocalDate, List<Double>>> byLocation) {

    /** Hours in the local day a series covers. */
    public static final int HOURS_PER_DAY = 24;

    /** An empty curve — the state before the first build, and after any failed refresh. */
    public static final SurgeCurve EMPTY = new SurgeCurve(Map.of());

    /**
     * Canonical constructor, defensive against a null map so {@link #forLocation} never has to be.
     *
     * @param byLocation surge metres keyed by location id then local date
     */
    public SurgeCurve {
        byLocation = byLocation == null ? Map.of() : Map.copyOf(byLocation);
    }

    /**
     * The hourly series for one location on one local date.
     *
     * @param locationId the location
     * @param date       the Europe/London local date
     * @return the 24 hourly values, or null when this location has no series for that date
     */
    public List<Double> forLocation(Long locationId, LocalDate date) {
        if (locationId == null || date == null) {
            return null;
        }
        Map<LocalDate, List<Double>> byDate = byLocation.get(locationId);
        return byDate == null ? null : byDate.get(date);
    }

    /**
     * Whether this carrier holds nothing — the post-restart state, in which the surge pill falls
     * back to its fact chips rather than showing a chart.
     *
     * @return true when no location has a series
     */
    public boolean isEmpty() {
        return byLocation.isEmpty();
    }
}
