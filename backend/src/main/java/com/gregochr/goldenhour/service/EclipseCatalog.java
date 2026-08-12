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
     */
    public record Eclipse(BesselianElements elements, Integer nextComparable) {

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

    /**
     * The 2027 August 02 total solar eclipse — a partial from Britain, deepest in the far south-west
     * and shallowest in Shetland. Mid-morning, with the Sun high, so every UK location sees all of it.
     */
    private static final BesselianElements ECLIPSE_2027_08_02 = new BesselianElements(
            "2027 Aug 02",
            LocalDate.of(2027, 8, 2),
            10.000,
            76.0,
            new Poly(-0.0197720, 0.5447123, -0.0000446, -0.0000092),
            new Poly(0.1600610, -0.2111582, -0.0001217, 0.0000038),
            new Poly(17.7624702, -0.0101810, -0.0000040, 0.0000000),
            new Poly(0.5305960, 0.0000138, -0.0000128, 0.0000000),
            new Poly(-0.0154640, 0.0000137, -0.0000128, 0.0000000),
            new Poly(328.422546, 15.002100, 0.000000, 0.000000),
            0.0046064,
            0.0045834);

    /**
     * The 2028 January 26 annular solar eclipse — a partial from Britain, and the one that made the
     * horizon clamp necessary. Greatest coverage falls at 16:49 UT, by which time the Sun has set
     * over most of the country; what Britain actually sees is a Sun already more than half eaten
     * going down. Only the far south-west still has it above the horizon at the peak.
     */
    private static final BesselianElements ECLIPSE_2028_01_26 = new BesselianElements(
            "2028 Jan 26",
            LocalDate.of(2028, 1, 26),
            15.000,
            76.3,
            new Poly(-0.2052830, 0.4742570, -0.0000390, -0.0000053),
            new Poly(0.3402800, 0.1738587, 0.0000968, -0.0000021),
            new Poly(-18.7282505, 0.0100740, 0.0000050, 0.0000000),
            new Poly(0.5741170, 0.0000420, -0.0000099, 0.0000000),
            new Poly(0.0278400, 0.0000418, -0.0000099, 0.0000000),
            new Poly(41.891281, 14.998960, 0.000000, 0.000000),
            0.0047501,
            0.0047264);

    /**
     * The 2029 June 12 partial solar eclipse — the shallowest here, and a pre-dawn one: the peak is
     * around 03:10 UT with the Sun below the horizon almost everywhere in Britain, so most of the
     * country sees only the tail of it after sunrise. Shetland is the exception.
     */
    private static final BesselianElements ECLIPSE_2029_06_12 = new BesselianElements(
            "2029 Jun 12",
            LocalDate.of(2029, 6, 12),
            4.000,
            77.2,
            new Poly(-0.0107990, 0.5247606, 0.0000104, -0.0000065),
            new Poly(1.2954130, -0.0176365, -0.0002057, 0.0000003),
            new Poly(23.1593208, 0.0025910, -0.0000050, 0.0000000),
            new Poly(0.5566620, -0.0001027, -0.0000104, 0.0000000),
            new Poly(0.0104720, -0.0001022, -0.0000103, 0.0000000),
            new Poly(240.035584, 14.999200, 0.000000, 0.000000),
            0.0046048,
            0.0045819);

    /**
     * The 2030 June 01 annular solar eclipse — a partial from Britain at breakfast time, deepest in
     * the south-east, with the Sun low but comfortably up everywhere.
     */
    private static final BesselianElements ECLIPSE_2030_06_01 = new BesselianElements(
            "2030 Jun 01",
            LocalDate.of(2030, 6, 1),
            6.000,
            77.8,
            new Poly(-0.2693910, 0.5056371, 0.0000182, -0.0000057),
            new Poly(0.5519770, 0.0210150, -0.0001586, -0.0000002),
            new Poly(22.0613003, 0.0055810, -0.0000050, 0.0000000),
            new Poly(0.5661500, -0.0000130, -0.0000097, 0.0000000),
            new Poly(0.0199120, -0.0000129, -0.0000097, 0.0000000),
            new Poly(270.539825, 14.999700, 0.000000, 0.000000),
            0.0046120,
            0.0045890);

    /**
     * Every eclipse the app knows about, in date order — every solar eclipse visible from Britain
     * out to 2030.
     *
     * <p>Each entry's elements were checked by reduction before being seeded: the obscuration this
     * app computes at the deepest UK location agrees with the published UK figure for all five
     * (2026 96%, 2027 48%, 2028 55%, 2029 19%, 2030 49%). That is an independent check of the
     * transcription and of the reduction at once, and {@code EclipseCatalogTest} keeps it.
     *
     * <p><b>Only 2026 carries a return period, and that is not an oversight.</b>
     * {@code nextComparable} is documented as researched-or-null, and only 2026's has been
     * established — by reducing 2081 September 03 and confirming it is the next to reach anything
     * like the same depth over Britain (99% in Scilly, against 57–67% for everything between). For
     * the other four "comparable" would be a guess, so the field is null and the rarity line simply
     * does not appear. That is the degrade the field was designed for.
     *
     * <p>The design handoff proposed a per-eclipse "promote from N days ahead" lead time, so an
     * evening eclipse could take the Plan strip the night before — the drive being planned then.
     * It is not carried, because this implementation does not need it: {@code buildPromotedStrip}
     * scans every card on the pane, which spans several days, so an eclipse two days out already
     * competes for the strip on the same terms as tonight's. A field seeded and never read would
     * be a rule nobody could see working.
     */
    private static final List<Eclipse> ECLIPSES = List.of(
            new Eclipse(ECLIPSE_2026_08_12, 2081),
            new Eclipse(ECLIPSE_2027_08_02, null),
            new Eclipse(ECLIPSE_2028_01_26, null),
            new Eclipse(ECLIPSE_2029_06_12, null),
            new Eclipse(ECLIPSE_2030_06_01, null));

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
