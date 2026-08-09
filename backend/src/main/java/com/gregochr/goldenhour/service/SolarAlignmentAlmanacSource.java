package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The four solar turning points of the year — both equinoxes and both solstices.
 *
 * <p>Equinoxes already have a hot-topic strategy and solstices have <strong>no code anywhere in the
 * project</strong>; the only matches for the word are local variables in a solar test. So half of
 * what the plan filed under "equinox/solstice" as though it were one existing thing had to be
 * written.
 *
 * <p><strong>These entries are sky-wide, and that is a decision rather than an omission.</strong>
 * {@code EquinoxHotTopicStrategy} narrows an equinox to the regions whose sunrise azimuth falls
 * within three degrees of due east, because on the Plan tab the question is "is this alignment
 * worth driving to tonight". At ninety days the question is "when is it", and the answer does not
 * depend on where you stand. Carrying a region list here would also mean a solar calculation per
 * location per day across the whole feed to produce something the reader cannot act on yet.
 *
 * <p>⚠️ <strong>The equinox anchors are duplicated from {@code EquinoxHotTopicStrategy}, which
 * keeps them private, and {@code SolarAlignmentAlmanacSourceTest} pins the two against each other
 * day by day across a full year.</strong> If either moves, that test fails rather than the feed
 * quietly disagreeing with the Plan tab about when the equinox is.
 */
@Component
public class SolarAlignmentAlmanacSource implements AlmanacSource {

    /** Machine-readable discriminator for an equinox. */
    static final String TYPE_EQUINOX = "equinox";

    /** Machine-readable discriminator for a solstice. */
    static final String TYPE_SOLSTICE = "solstice";

    /** Days either side of a turning point that the entry spans. Matches the equinox strategy. */
    static final int WINDOW_DAYS = 3;

    /** Approximate March equinox — must match {@code EquinoxHotTopicStrategy}. */
    static final MonthDay MARCH_EQUINOX = MonthDay.of(3, 20);

    /** Approximate September equinox — must match {@code EquinoxHotTopicStrategy}. */
    static final MonthDay SEPTEMBER_EQUINOX = MonthDay.of(9, 22);

    /** Approximate June solstice. */
    static final MonthDay JUNE_SOLSTICE = MonthDay.of(6, 21);

    /** Approximate December solstice. */
    static final MonthDay DECEMBER_SOLSTICE = MonthDay.of(12, 21);

    private static final String EQUINOX_DETAIL =
            "The sun rises almost exactly due east and sets almost exactly due west. Valleys,"
                    + " streets and rivers that run east–west line up with it for a few days"
                    + " either side, and only twice a year.";

    private static final String JUNE_DETAIL =
            "The longest day. The sun rises and sets at its most northerly, golden hour stretches"
                    + " out, and the sky never fully darkens — poor for stars, unmatched for a"
                    + " long northern twilight.";

    private static final String DECEMBER_DETAIL =
            "The shortest day. The sun stays low all day, so the light is soft and raking from"
                    + " dawn to dusk and golden hour effectively lasts from opening to close.";

    /** One turning point: its calendar anchor and the copy that describes it. */
    private record TurningPoint(MonthDay anchor, String type, String title, String detail) { }

    private static final List<TurningPoint> TURNING_POINTS = List.of(
            new TurningPoint(MARCH_EQUINOX, TYPE_EQUINOX, "Spring equinox", EQUINOX_DETAIL),
            new TurningPoint(JUNE_SOLSTICE, TYPE_SOLSTICE, "Summer solstice", JUNE_DETAIL),
            new TurningPoint(SEPTEMBER_EQUINOX, TYPE_EQUINOX, "Autumn equinox", EQUINOX_DETAIL),
            new TurningPoint(DECEMBER_SOLSTICE, TYPE_SOLSTICE, "Winter solstice", DECEMBER_DETAIL));

    @Override
    public List<AlmanacEvent> events(LocalDate from, LocalDate to) {
        Set<Integer> years = new LinkedHashSet<>();
        years.add(from.getYear());
        years.add(to.getYear());

        List<AlmanacEvent> events = new ArrayList<>();
        for (TurningPoint point : TURNING_POINTS) {
            for (int year : years) {
                LocalDate anchor = point.anchor().atYear(year);
                LocalDate start = anchor.minusDays(WINDOW_DAYS);
                LocalDate end = anchor.plusDays(WINDOW_DAYS);

                // Overlap, not containment: a window straddling the edge of the feed is still
                // news, and clipping its span would misreport when the alignment actually runs.
                if (end.isBefore(from) || start.isAfter(to)) {
                    continue;
                }
                events.add(new AlmanacEvent(start, end, AlmanacKind.ALMANAC, point.type(),
                        point.title(), point.detail(),
                        AlmanacEvent.metaOf("peakDate", anchor.toString()),
                        List.of()));
            }
        }
        return events;
    }

    /**
     * Returns true when the date falls within {@value #WINDOW_DAYS} days of either equinox.
     *
     * <p>Exists only so the agreement test can compare this class's anchors against
     * {@code EquinoxHotTopicStrategy.isNearEquinox} without reaching into either's internals.
     *
     * @param date the calendar date
     * @return true if near an equinox
     */
    static boolean isNearEquinox(LocalDate date) {
        LocalDate march = MARCH_EQUINOX.atYear(date.getYear());
        LocalDate september = SEPTEMBER_EQUINOX.atYear(date.getYear());
        return Math.abs(ChronoUnit.DAYS.between(march, date)) <= WINDOW_DAYS
                || Math.abs(ChronoUnit.DAYS.between(september, date)) <= WINDOW_DAYS;
    }
}
