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
        LocalDate closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            // Full moon first: it excludes about twenty-six nights in twenty-nine, so the perigee
            // distance is only worth computing for the handful that survive.
            boolean fullMoon = lunarPhaseService.isFullMoon(date);
            double fromPerigee = fullMoon
                    ? lunarPhaseService.daysFromNearestPerigee(date)
                    : Double.MAX_VALUE;
            boolean qualifies = fullMoon && fromPerigee <= PERIGEE_WINDOW_DAYS;

            if (qualifies) {
                if (spanStart == null) {
                    spanStart = date;
                    closestDistance = Double.MAX_VALUE;
                }
                spanEnd = date;
                if (fromPerigee < closestDistance) {
                    closestDistance = fromPerigee;
                    closest = date;
                }
            } else if (spanStart != null) {
                events.add(toEvent(spanStart, spanEnd, closest));
                spanStart = null;
            }
        }

        if (spanStart != null) {
            events.add(toEvent(spanStart, spanEnd, closest));
        }
        return events;
    }

    private static AlmanacEvent toEvent(LocalDate start, LocalDate end, LocalDate closest) {
        return new AlmanacEvent(start, end, AlmanacKind.ALMANAC, TYPE, TITLE, DETAIL,
                // The peak night is the one nearest perigee, which is not always the middle of the
                // span — naming it lets a reader pick one night out of three.
                AlmanacEvent.metaOf("peakDate", closest == null ? null : closest.toString()),
                List.of());
    }
}
