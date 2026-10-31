package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.LocationPoint;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared builders for the neighbour guard's tests: sky and canopy slots with a chosen sky rating,
 * points placed a given distance east and north of a fixed origin, and the window hierarchy around
 * them.
 */
final class NeighbourGuardFixtures {

    /** A day with both windows' worth of fixtures. */
    static final LocalDate D = LocalDate.of(2026, 10, 18);

    /** Latitude of the fixed origin; 55 degrees north, where a degree of longitude is ~0.57 of a degree of latitude. */
    static final double ORIGIN_LAT = 55.0;
    /** Longitude of the fixed origin. */
    static final double ORIGIN_LON = -2.0;

    private static final double KM_PER_DEGREE = 111.19;

    private NeighbourGuardFixtures() {
    }

    /**
     * A sky slot whose served star and sky star are the same.
     *
     * @param id     the location id, or null
     * @param name   the location name
     * @param rating the served and sky rating
     * @return the slot
     */
    static BriefingSlot sky(Long id, String name, int rating) {
        return sky(id, name, rating, rating);
    }

    /**
     * A sky slot whose served star (tide averaged in) may differ from its sky star.
     *
     * @param id     the location id, or null
     * @param name   the location name
     * @param rating the served {@code claudeRating}
     * @param sky    the {@code skyRating}, or null for a row that carries none
     * @return the slot
     */
    static BriefingSlot sky(Long id, String name, int rating, Integer sky) {
        return new BriefingSlot(id, name, LocalDateTime.of(D, java.time.LocalTime.of(6, 0)), Verdict.GO,
                null, BriefingSlot.TideInfo.NONE, List.of(), rating, sky, 60, 55, "summary",
                DisplayVerdict.resolve(rating, Verdict.GO), null, false);
    }

    /**
     * A sky slot with no rating at all.
     *
     * @param id   the location id
     * @param name the location name
     * @return the slot
     */
    static BriefingSlot unrated(Long id, String name) {
        return new BriefingSlot(id, name, LocalDateTime.of(D, java.time.LocalTime.of(6, 0)), Verdict.GO,
                null, BriefingSlot.TideInfo.NONE, List.of());
    }

    /**
     * A rated woodland (canopy) slot.
     *
     * @param id     the location id
     * @param name   the location name
     * @param rating the rating
     * @return the slot
     */
    static BriefingSlot wood(Long id, String name, int rating) {
        return BriefingSlot.canopySlot(id, name, LocalDateTime.of(D, java.time.LocalTime.of(6, 0)),
                Verdict.GO, null, List.of()).withClaudeScores(rating, rating, 60, 55, "s", null);
    }

    /**
     * A point {@code northKm} north and {@code eastKm} east of the origin, on the spherical-Earth
     * degree lengths (so the haversine in the guard reads back the requested distance).
     *
     * @param id        the location id
     * @param northKm   kilometres north (negative for south)
     * @param eastKm    kilometres east (negative for west)
     * @param elevation the elevation in metres, or null
     * @return the point
     */
    static LocationPoint at(long id, double northKm, double eastKm, Integer elevation) {
        double lat = ORIGIN_LAT + northKm / KM_PER_DEGREE;
        double lon = ORIGIN_LON + eastKm / (KM_PER_DEGREE * Math.cos(Math.toRadians(ORIGIN_LAT)));
        return new LocationPoint(id, lat, lon, elevation);
    }

    /**
     * Points keyed by id, in an immutable map as the provider serves them (so a lookup of a null id
     * throws, as it does in production).
     *
     * @param points the points
     * @return the map
     */
    static Map<Long, LocationPoint> points(LocationPoint... points) {
        Map<Long, LocationPoint> map = new HashMap<>();
        for (LocationPoint point : points) {
            map.put(point.id(), point);
        }
        return Map.copyOf(map);
    }

    /**
     * A region holding the slots, scored and glossed so the projector can pick it.
     *
     * @param name  the region name
     * @param slots the slots
     * @return the region
     */
    static BriefingRegion region(String name, BriefingSlot... slots) {
        return new BriefingRegion(name, Verdict.GO, "summary", List.of(), List.of(slots), null, null,
                null, null, "Colour likely tonight", "Detail", DisplayVerdict.WORTH_IT,
                slots.length, null, null).withSampleSufficient(true);
    }

    /**
     * A region the honesty filter treats as having no coverage: no scored location, so it empties the
     * region's slots on the way past.
     *
     * @param name  the region name
     * @param slots the raw slots the filter will empty
     * @return the region
     */
    static BriefingRegion zeroCoverageRegion(String name, BriefingSlot... slots) {
        return new BriefingRegion(name, Verdict.GO, "summary", List.of(), List.of(slots), null, null,
                null, null, null, null, DisplayVerdict.WORTH_IT, 0, null, null);
    }

    /**
     * A day with one summary of the given event holding the regions.
     *
     * @param date    the date
     * @param type    the event
     * @param regions the regions
     * @return the day
     */
    static BriefingDay day(LocalDate date, TargetType type, BriefingRegion... regions) {
        return new BriefingDay(date, List.of(new BriefingEventSummary(type, List.of(regions), List.of())));
    }

    /**
     * A day with one summary of the given event holding the regions and some unregioned slots.
     *
     * @param date       the date
     * @param type       the event
     * @param unregioned the unregioned slots
     * @param regions    the regions
     * @return the day
     */
    static BriefingDay dayWithUnregioned(LocalDate date, TargetType type, List<BriefingSlot> unregioned,
            BriefingRegion... regions) {
        return new BriefingDay(date, List.of(new BriefingEventSummary(type, List.of(regions), unregioned)));
    }
}
