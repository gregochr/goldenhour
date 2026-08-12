package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BesselianElements;
import com.gregochr.goldenhour.model.BesselianElements.Poly;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The seeded table of solar eclipses this app knows about.
 *
 * <p>Follows the pattern {@code MeteorHotTopicStrategy.SHOWERS} established: a static, in-code
 * table of stable astronomical constants, with no migration and no table behind it. An eclipse's
 * elements are fixed for all time once published, so a database row would add a deployment step
 * and a failure mode in exchange for nothing.
 *
 * <p><b>Adding an eclipse is a data edit, not a code change.</b> Take the Besselian elements and
 * ΔT verbatim from NASA's published table for that eclipse
 * ({@code eclipse.gsfc.nasa.gov/SEsearch/SEdata.php?Ecl=<yyyymmdd>}) and add an entry. Everything
 * the UI shows — per-location magnitude, contact times, the Sun's altitude and bearing at maximum —
 * falls out of {@link com.gregochr.goldenhour.util.EclipseCalculator} from those numbers alone.
 *
 * <p>Only eclipses worth showing UK photographers belong here. There are two or three solar
 * eclipses a year worldwide and almost none of them reach these latitudes; the table is short by
 * design, and a location that sees nothing simply gets no topic.
 */
public final class EclipseCatalog {

    /**
     * Years between this eclipse and the next comparable one, above which the rarity line is worth
     * printing. Below it, "returns in 3 years" is not a reason to drive anywhere, and printing it
     * on every event would make the line ambient — which is the failure the design's own note
     * ("above roughly 10 years it earns the rarity line") exists to prevent.
     */
    public static final int RARITY_THRESHOLD_YEARS = 10;

    /**
     * One catalogued eclipse: its ephemeris, plus the two editorial facts that cannot be derived
     * from it.
     *
     * @param elements        the published Besselian elements — the whole of the astronomy
     * @param nextComparable  the year of the next eclipse of comparable depth visible from the UK,
     *                        or null when unknown. <b>Seeded, not derived.</b> Establishing it
     *                        would mean computing local circumstances for every eclipse in the
     *                        canon for the next century and defining "comparable" numerically;
     *                        this is a catalogue fact taken from the design handoff, and it is
     *                        null rather than guessed for anything not researched
     * @param promoteFromDays how many days ahead this eclipse may take the Plan pane's promoted
     *                        strip. An eclipse in the evening has to be decided the evening before,
     *                        because that is when the drive is planned — so 1, where an ordinary
     *                        almanac event is 0 and is promoted only on its own day
     */
    public record Eclipse(BesselianElements elements, Integer nextComparable, int promoteFromDays) {

        /**
         * The date the eclipse falls on.
         *
         * @return the eclipse's UTC date
         */
        public LocalDate date() {
            return elements.t0Date();
        }

        /**
         * Years from this eclipse to the next comparable one, or null when not seeded.
         *
         * @return the return period in years, or null
         */
        public Integer returnYears() {
            return nextComparable == null ? null : nextComparable - date().getYear();
        }

        /**
         * Whether the return period is long enough to be worth stating.
         *
         * @return true when a rarity line should be shown
         */
        public boolean isRare() {
            Integer years = returnYears();
            return years != null && years > RARITY_THRESHOLD_YEARS;
        }
    }

    /**
     * The 2026 August 12 total solar eclipse — a deep partial from the whole of the British Isles,
     * and the closest to total these islands have seen since 1999.
     *
     * <p>Elements verbatim from NASA's Five Millennium Canon, generated with the VSOP87/ELP2000-82
     * ephemerides. Verified by reduction: these numbers put London's contacts at 18:17:16, 19:13:13
     * and 20:06:09 BST with the Sun 10.2° up, against published circumstances of 18:17, 19:13 and
     * 20:06 with the Sun ~10.5° up. {@code EclipseCalculatorTest} pins that agreement, so a
     * transcription error in any coefficient below fails the build rather than shipping a wrong
     * time to the minute.
     */
    private static final BesselianElements ECLIPSE_2026_08_12 = new BesselianElements(
            "2026 Aug 12",
            LocalDate.of(2026, 8, 12),
            18.000,
            75.4,
            new Poly(0.4755140, 0.5189249, -0.0000773, -0.0000080),
            new Poly(0.7711830, -0.2301680, -0.0001246, 0.0000038),
            new Poly(14.7966700, -0.0120650, -0.0000030, 0.0000000),
            new Poly(0.5379550, 0.0000939, -0.0000121, 0.0000000),
            new Poly(-0.0081420, 0.0000935, -0.0000121, 0.0000000),
            new Poly(88.747787, 15.003090, 0.000000, 0.000000),
            0.0046141,
            0.0045911);

    /** Every eclipse the app knows about, in date order. */
    private static final List<Eclipse> ECLIPSES = List.of(
            new Eclipse(ECLIPSE_2026_08_12, 2081, 1));

    private EclipseCatalog() {
    }

    /**
     * The catalogued eclipses falling within the given range, inclusive of both ends.
     *
     * @param from first day of interest
     * @param to   last day of interest
     * @return the eclipses in the range, in date order; empty when none fall in it
     */
    public static List<Eclipse> between(LocalDate from, LocalDate to) {
        return ECLIPSES.stream()
                .filter(eclipse -> !eclipse.date().isBefore(from) && !eclipse.date().isAfter(to))
                .toList();
    }

    /**
     * The eclipse falling on the given date, if any.
     *
     * @param date the date to look up
     * @return the eclipse on that date, or empty
     */
    public static Optional<Eclipse> on(LocalDate date) {
        return ECLIPSES.stream().filter(eclipse -> eclipse.date().equals(date)).findFirst();
    }

    /**
     * Every catalogued eclipse, for tests that pin the table's contents.
     *
     * @return an unmodifiable view of the catalogue
     */
    public static List<Eclipse> all() {
        return ECLIPSES;
    }
}
