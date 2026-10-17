package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.repository.LocationPoint;
import com.gregochr.goldenhour.service.evaluation.RatingValidator;
import com.gregochr.goldenhour.util.GeoUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Marks a served sky slot that is rated far above every nearby place that sees the same sky
 * (docs/engineering/neighbour-guard-plan.md §2). A lone high star among several low neighbours is far
 * more likely to be model noise than a real local difference, and the harm is when a surface NAMES
 * that place as the best; the guard only marks it, and the slot keeps its star.
 *
 * <p>A slot is an <b>outlier</b> when all of these hold:
 * <ol>
 *   <li>It is a <b>candidate</b>: not canopy, in a region, in a SUNRISE or SUNSET summary, with a
 *       location id, a known point and a {@code skyRating} of at least {@code min-rating}.</li>
 *   <li><b>No supporter.</b> No other sky slot in the same window (same date and target type, across
 *       every region) within {@code radius-km} is rated within {@code margin - 1} stars below the
 *       candidate, or at or above it. A nearby place that agrees clears the slot at ANY height.</li>
 *   <li><b>Enough witnesses.</b> At least {@code min-neighbours} of the nearby sky slots are at a
 *       similar height: both without an elevation, or both with one and no more than
 *       {@code max-elevation-gap-m} apart. A place with an elevation is judged only against others
 *       with one, so a fell top is never outvoted by places whose height is unknown.</li>
 * </ol>
 *
 * <p>It reads {@code skyRating}, never the served {@code claudeRating}, which averages in each
 * coastal place's own tide score. A slot with no location id, no point or no {@code skyRating} is
 * neither a candidate nor a neighbour (fail open), and neither is a {@code skyRating} outside Claude's
 * 1 to 5 scale (a bad row, read as absent). Pure: no clock, no I/O, no logging.
 *
 * <p>A neighbour rated <em>above</em> the candidate also clears it. The plan's wording ("within
 * {@code margin - 1} of it") is silent on direction, but the guard exists for a rating far ABOVE the
 * places beside it; read literally, a 3-star slot with two 5-star neighbours would be flagged.
 */
public final class NeighbourOutlierGuard {

    /** Kilometres to miles; the radius is converted once so distance stays on {@link GeoUtils}. */
    static final double MILES_PER_KM = 0.621371;

    /** Kilometres in one degree of latitude, rounded down so the bounding box is never too small. */
    private static final double KM_PER_DEGREE_LATITUDE = 110.0;

    /** Floor on the cosine used to widen the longitude box, so a polar latitude cannot divide by zero. */
    private static final double MIN_COSINE = 0.01;

    /**
     * The fixed rating below which a pick is never named after an outlier was skipped. It is the
     * least rating any surface will name as a pick (Ask's {@code MIN_PICK_RATING}) and is
     * deliberately NOT the configurable {@code min-rating}: the client mirrors this constant, and a
     * configurable server floor would let the BEST BET destination and the best-reachable line
     * disagree about the same window.
     */
    public static final int NAMED_FLOOR = 3;

    private NeighbourOutlierGuard() {
    }

    /**
     * One flagged slot: a place in a window.
     *
     * @param locationId the flagged location
     * @param date       the window's date
     * @param targetType the window's event, SUNRISE or SUNSET
     */
    public record Key(long locationId, LocalDate date, TargetType targetType) {
    }

    /**
     * What one pass found.
     *
     * @param flagged          the slots marked as outliers
     * @param ratedSkySlots    regioned sky slots in SUNRISE or SUNSET windows carrying a Claude rating
     * @param withSkyRating    how many of those also carry a {@code skyRating}
     * @param windows          the windows holding at least one such slot
     * @param flaggedRatings   each flagged slot's sky rating, for the log
     */
    public record Result(Set<Key> flagged, int ratedSkySlots, int withSkyRating, int windows,
            Map<Key, Integer> flaggedRatings) {

        /**
         * The share of rated sky slots that carry a sky rating, as a whole percent.
         *
         * @return 0 to 100; 100 when there are no rated sky slots
         */
        public int coveragePercent() {
            return ratedSkySlots == 0 ? 100 : Math.round(100f * withSkyRating / ratedSkySlots);
        }
    }

    /** A sky slot that can take part: id, sky rating and point. */
    private record Entry(long id, int rating, double lat, double lon, Integer elevation) {
    }

    /**
     * Applies the rule to every window of a response.
     *
     * @param days   the served days (after the honesty filter, so zero-coverage regions are empty)
     * @param points the enabled locations' points by id
     * @param config the rule's thresholds
     * @return the flagged slots and the counts the log reports
     */
    public static Result flag(List<BriefingDay> days, Map<Long, LocationPoint> points,
            NeighbourGuardProperties config) {
        Set<Key> flagged = new HashSet<>();
        Map<Key, Integer> ratings = new HashMap<>();
        int rated = 0;
        int withSky = 0;
        int windows = 0;
        double radiusMiles = config.getRadiusKm() * MILES_PER_KM;
        for (BriefingDay day : days) {
            for (BriefingEventSummary summary : day.eventSummaries()) {
                if (summary.targetType() != TargetType.SUNRISE
                        && summary.targetType() != TargetType.SUNSET) {
                    continue;
                }
                List<Entry> entries = new ArrayList<>();
                boolean any = false;
                for (BriefingRegion region : summary.regions()) {
                    for (BriefingSlot slot : region.slots()) {
                        if (slot.canopy()) {
                            continue;
                        }
                        if (slot.claudeRating() != null) {
                            rated++;
                            any = true;
                            if (RatingValidator.isInRange(slot.skyRating())) {
                                withSky++;
                            }
                        }
                        Entry entry = entryOf(slot, points);
                        if (entry != null) {
                            entries.add(entry);
                        }
                    }
                }
                if (any) {
                    windows++;
                }
                for (Entry outlier : outliersIn(entries, config, radiusMiles)) {
                    Key key = new Key(outlier.id(), day.date(), summary.targetType());
                    flagged.add(key);
                    ratings.put(key, outlier.rating());
                }
            }
        }
        return new Result(Set.copyOf(flagged), rated, withSky, windows, Map.copyOf(ratings));
    }

    private static Entry entryOf(BriefingSlot slot, Map<Long, LocationPoint> points) {
        if (slot.locationId() == null || !RatingValidator.isInRange(slot.skyRating())) {
            return null;
        }
        LocationPoint point = points.get(slot.locationId());
        if (point == null) {
            return null;
        }
        return new Entry(slot.locationId(), slot.skyRating(), point.lat(), point.lon(),
                point.elevationMetres());
    }

    private static List<Entry> outliersIn(List<Entry> entries, NeighbourGuardProperties config,
            double radiusMiles) {
        List<Entry> outliers = new ArrayList<>();
        for (Entry candidate : entries) {
            if (candidate.rating() >= config.getMinRating()
                    && isOutlier(candidate, entries, config, radiusMiles)) {
                outliers.add(candidate);
            }
        }
        return outliers;
    }

    private static boolean isOutlier(Entry candidate, List<Entry> entries,
            NeighbourGuardProperties config, double radiusMiles) {
        double radiusKm = config.getRadiusKm();
        double latBox = radiusKm / KM_PER_DEGREE_LATITUDE;
        int witnesses = 0;
        for (Entry other : entries) {
            if (other.id() == candidate.id() || !withinBox(candidate, other, radiusKm, latBox)
                    || GeoUtils.distanceMiles(candidate.lat(), candidate.lon(), other.lat(), other.lon())
                            > radiusMiles) {
                continue;
            }
            // Supporter: rated at or above the candidate, or fewer than `margin` stars below it.
            // Any height; one is enough to clear the slot.
            if (candidate.rating() - other.rating() < config.getMargin()) {
                return false;
            }
            if (similarHeight(candidate, other, config.getMaxElevationGapM())) {
                witnesses++;
            }
        }
        return witnesses >= config.getMinNeighbours();
    }

    /** The cheap rectangle test; wider than the radius everywhere, so it never rejects a true neighbour. */
    private static boolean withinBox(Entry a, Entry b, double radiusKm, double latBox) {
        if (Math.abs(a.lat() - b.lat()) > latBox) {
            return false;
        }
        double widest = Math.max(Math.abs(a.lat()), Math.abs(b.lat()));
        double cosine = Math.max(Math.cos(Math.toRadians(widest)), MIN_COSINE);
        double lonBox = radiusKm / (KM_PER_DEGREE_LATITUDE * cosine);
        return Math.abs(a.lon() - b.lon()) <= lonBox;
    }

    private static boolean similarHeight(Entry a, Entry b, int maxGapMetres) {
        if (a.elevation() == null || b.elevation() == null) {
            return a.elevation() == null && b.elevation() == null;
        }
        return Math.abs(a.elevation() - b.elevation()) <= maxGapMetres;
    }
}
