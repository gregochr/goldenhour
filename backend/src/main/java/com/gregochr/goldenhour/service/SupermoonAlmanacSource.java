package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Every supermoon in the feed's range.
 *
 * <p>A full moon within {@value #PERIGEE_WINDOW_DAYS} days of perigee, matching
 * {@code SupermoonHotTopicStrategy}'s definition exactly — the two must agree or the Plan tab and
 * the feed would disagree about whether a given night qualifies.
 *
 * <p>It exists separately for the same reason {@link MeteorAlmanacSource} does: the strategy
 * returns on its first match, which is indistinguishable from "all of them" over four days and
 * loses every subsequent one over ninety. A 90-day window holds up to three full moons.
 *
 * <p>Consecutive qualifying days are collapsed into one entry rather than emitted separately. The
 * full-moon and perigee tests are both windowed, so a single supermoon typically satisfies them on
 * two or three adjacent nights; without grouping, one event would appear as three rows claiming to
 * be three supermoons.
 */
@Component
public class SupermoonAlmanacSource implements AlmanacSource {

    /** Machine-readable discriminator. */
    static final String TYPE = "supermoon";

    /** Maximum days from perigee for a full moon to count — matches the hot-topic strategy. */
    private static final double PERIGEE_WINDOW_DAYS = 3.0;

    /** Safety bound on the outward walk that finds a span's true first and last night. */
    private static final int MAX_SPAN_WALK_DAYS = 10;

    private static final String TITLE = "Supermoon";

    private static final String DETAIL =
            "The full moon coincides with its closest approach to Earth, so it rises noticeably"
                    + " larger and brighter. Moonrise over the coast or behind a landmark is the"
                    + " shot; be in position before the sun has fully gone.";

    private final LunarPhaseService lunarPhaseService;

    /**
     * Constructs a {@code SupermoonAlmanacSource}.
     *
     * @param lunarPhaseService supplies the full-moon and perigee tests
     */
    public SupermoonAlmanacSource(LunarPhaseService lunarPhaseService) {
        this.lunarPhaseService = lunarPhaseService;
    }

    @Override
    public List<AlmanacEvent> events(LocalDate from, LocalDate to) {
        List<AlmanacEvent> events = new ArrayList<>();
        LocalDate spanStart = null;
        LocalDate spanEnd = null;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (qualifies(date)) {
                if (spanStart == null) {
                    spanStart = date;
                }
                spanEnd = date;
            } else if (spanStart != null) {
                events.add(completeSpan(spanStart, spanEnd));
                spanStart = null;
            }
        }

        if (spanStart != null) {
            events.add(completeSpan(spanStart, spanEnd));
        }
        return events;
    }

    /**
     * Extends the span outwards to the supermoon's true first and last night, then names its peak.
     *
     * <p>Same reasoning as the tide source: {@link AlmanacSource} promises a true span, and a
     * three-night supermoon straddling the edge of the feed would otherwise be reported as a
     * one-night event whose named peak is simply the nearest night that happened to be visible
     * rather than the one nearest perigee.
     */
    private AlmanacEvent completeSpan(LocalDate firstSeen, LocalDate lastSeen) {
        LocalDate start = firstSeen;
        for (int i = 0; i < MAX_SPAN_WALK_DAYS && qualifies(start.minusDays(1)); i++) {
            start = start.minusDays(1);
        }
        LocalDate end = lastSeen;
        for (int i = 0; i < MAX_SPAN_WALK_DAYS && qualifies(end.plusDays(1)); i++) {
            end = end.plusDays(1);
        }

        LocalDate closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            double fromPerigee = lunarPhaseService.daysFromNearestPerigee(date);
            if (fromPerigee < closestDistance) {
                closestDistance = fromPerigee;
                closest = date;
            }
        }
        return toEvent(start, end, closest);
    }

    /**
     * Full moon first: it excludes about twenty-six nights in twenty-nine, so the perigee distance
     * is only worth computing for the handful that survive.
     */
    private boolean qualifies(LocalDate date) {
        return lunarPhaseService.isFullMoon(date)
                && lunarPhaseService.daysFromNearestPerigee(date) <= PERIGEE_WINDOW_DAYS;
    }

    private static AlmanacEvent toEvent(LocalDate start, LocalDate end, LocalDate closest) {
        return new AlmanacEvent(start, end, AlmanacKind.ALMANAC, TYPE, TITLE, DETAIL,
                // The peak night is the one nearest perigee, which is not always the middle of the
                // span — naming it lets a reader pick one night out of three.
                AlmanacEvent.metaOf("peakDate", closest == null ? null : closest.toString()),
                List.of());
    }
}
