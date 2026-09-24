package com.gregochr.goldenhour.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The seeded table of lunar eclipses this app knows about.
 *
 * <p>Follows the pattern {@link EclipseCatalog} established for the solar eclipse: a static,
 * in-code table of stable astronomical constants, with no migration and no table behind it. A
 * lunar eclipse's contacts are fixed for all time once published, so a database row would add a
 * deployment step and a failure mode in exchange for nothing.
 *
 * <h2>Why this is a separate catalogue, not a reuse of {@code EclipseCatalog}</h2>
 *
 * <p>A solar eclipse's depth varies sharply from place to place — the Moon's shadow is a narrow
 * cone on the ground — so {@code EclipseCatalog} stores published <em>Besselian elements</em> and
 * {@code EclipseCalculator} reduces them per observer. A lunar eclipse has no equivalent geometry:
 * the whole Earth stands between the Moon and the Sun, so the umbral magnitude and every contact
 * instant (P1, U1, U2, greatest eclipse, U3, U4, P4) are the same for every observer who can see the
 * Moon at all. What varies by place is only whether the Moon is above the horizon, which is why this
 * catalogue stores contact <em>instants</em> directly rather than elements to reduce, and why the
 * per-location work ({@link com.gregochr.goldenhour.util.LunarEclipseCalculator}) computes only
 * geometry — altitude, bearing, moonrise/moonset — never a magnitude of its own.
 *
 * <h2>Sources and verification</h2>
 *
 * <p>Contact times and umbral magnitudes are transcribed from NASA's decade lunar eclipse tables
 * ({@code eclipse.gsfc.nasa.gov/LEdecade/LEdecade2021.html}, which the 2026-09-24 session fetched
 * directly and which lists every eclipse 2021–2030 with its umbral magnitude and coarse visibility
 * region) and cross-checked against the per-eclipse detail pages at
 * {@code eclipsewise.com/lunar/LEprime/2001-2100/}, Fred Espenak's own site, which republishes the
 * same underlying <cite>Five Millennium Canon of Lunar Eclipses</cite> (Espenak &amp; Meeus, NASA/TP
 * 2009-214172) at contact-time precision. Where the two disagree at the sub-minute level, the
 * eclipsewise.com detail page's figure is taken, since the decade table itself is a rounded summary
 * of the same canon. Two further umbral-magnitude figures (2028 Jan 12, 0.0679) were independently
 * confirmed against Wikipedia's own eclipse-page transcription of the canon.
 *
 * <p><b>Every seeded entry was verified three ways before being added</b> — the same discipline
 * {@code EclipseCatalog} applies by reduction:
 * <ol>
 *   <li><b>Full-moon check.</b> {@code com.gregochr.solarutils.LunarCalculator} reports ≥99.9%
 *       illumination at every seeded {@code max} instant from a UK reference point (54.5°N, 2.5°W)
 *       — a transcribed date wrong by even a day would fail this, since illumination falls off
 *       fast either side of full moon.</li>
 *   <li><b>Contact ordering and kind/magnitude agreement</b>, enforced structurally by this record's
 *       compact constructor (below) rather than merely asserted in a test: {@code p1 < u1 < max <
 *       u4 < p4}, with {@code u1 < u2 < max < u3 < u4} additionally required whenever {@code u2}/
 *       {@code u3} are present, and {@code kind == TOTAL} if and only if {@code umbralMagnitude ≥
 *       1.0} if and only if {@code u2}/{@code u3} are non-null. A transcription that mismatched
 *       kind and magnitude, or that put a contact out of order, fails to construct at class-load
 *       time rather than shipping.</li>
 *   <li><b>UK-horizon check</b>, computed directly with {@code LunarCalculator} rather than trusted
 *       from NASA's coarse "visible from: …" continent list, because that list is not fine enough
 *       to answer "is the umbral phase above the horizon anywhere in the British Isles" on its own
 *       — see the exclusions below. Six reference points spanning the British Isles (54.5°N 2.5°W
 *       centre; 51.1°N 1.3°E Dover; 52.6°N 1.7°E Lowestoft; 60.15°N 1.15°W Shetland; 50.1°N 5.5°W
 *       the Lizard; 58.6°N 3.1°W Caithness) were sampled at U1, greatest eclipse and U4 for every
 *       umbral eclipse March 2025–2030; an eclipse is seeded only when at least one of those points
 *       shows the Moon above the horizon at one of those three instants.</li>
 * </ol>
 *
 * <h2>What is seeded, and what is deliberately excluded</h2>
 *
 * <p><b>Only umbral eclipses</b> (kind PARTIAL or TOTAL) are seeded — penumbral eclipses are
 * excluded outright rather than seeded-and-suppressed, because a penumbral shading is not a
 * photographable event and a row that can never raise a topic is a row a future session
 * un-suppresses by mistake (plan {@code lunar-eclipse-plan.md} §4 #4). That excludes 2027 Feb 20
 * (mag −0.057), 2027 Jul 18 (mag −1.068), 2027 Aug 17 (mag −0.525) and 2030 Dec 09 (mag −0.163) —
 * none of the four ever has an umbral phase to be visible or not.
 *
 * <p><b>Three umbral eclipses in range were checked and excluded</b> because the UK-horizon check
 * above found the Moon below the astronomical horizon at U1, greatest eclipse <em>and</em> U4, at
 * all six reference points:
 * <ul>
 *   <li><b>2026 Mar 03</b> (total, mag 1.15263) — NASA's own visibility list already omits Europe
 *       ("e Asia, Australia, Pacific, Americas"); the computed check confirms altitudes of −17° to
 *       −33° across the British Isles throughout U1–U4. This is UK daytime for a Pacific-hemisphere
 *       eclipse.</li>
 *   <li><b>2028 Jul 06</b> (partial, mag 0.39083) — NASA lists "Europe" among visible regions, which
 *       is true for the continent but not for these islands: at U4 the best of the six points
 *       (Dover) is still 4.9° <em>below</em> the horizon, and the Moon does not rise there until
 *       21:08 BST, 37 minutes <em>after</em> U4. The whole umbral phase is over before moonrise
 *       anywhere in Britain.</li>
 *   <li><b>2030 Jun 15</b> (partial, mag 0.50401) — the same shape as 2028 Jul 06: at U4 the best
 *       point (Dover) is still 2.1° below the horizon, with moonrise there at 20:59 BST, 13 minutes
 *       after U4 (20:46 BST). By P4 (the very end of the whole event, penumbra included) the Moon has
 *       only just cleared the horizon at the southern/eastern points and is still below it in
 *       Scotland — no part of the umbral phase is ever watchable from Britain.</li>
 * </ul>
 *
 * <p>These three exclusions are why the UK-horizon check exists at all rather than trusting NASA's
 * continent-level list: "Europe" spans roughly 40° of longitude, and an eclipse whose umbral phase
 * ends anywhere from 13 to 37 minutes before moonrise on this island is invisible here even though a
 * reader in Athens or Warsaw sees it rise mid-eclipse.
 *
 * <p><b>Every remaining umbral eclipse March 2025–2030 is seeded</b> — seven entries. The two 2025
 * entries are historical (the app cannot promote a topic for a date already past) and exist only so
 * a "first visible from here since …" sentence has a date to derive from downstream ({@code
 * lunar-eclipse-plan.md} §2.4); the design's own draft copy assumed the March 2025 event was the
 * most recent, which the September 2025 entry (also UK-visible, and later in the year) contradicts —
 * a derived sentence says whatever the catalogue says, not what a draft assumed.
 *
 * <h2>{@code nextComparable}</h2>
 *
 * <p>Unlike {@code EclipseCatalog}'s {@code nextComparable} — which requires researching whether a
 * <em>future</em> solar eclipse reaches comparable depth, since solar magnitude is
 * location-dependent and shallow near-misses are common — a lunar eclipse's magnitude is the same
 * everywhere, and this catalogue already excludes every eclipse this island cannot see. So
 * "comparable" collapses to "the next one in this table": {@link LunarEclipse#nextComparable()} and
 * {@link LunarEclipse#nextComparableKind()} are filled in automatically from each entry's successor
 * in date order, null for the last, computed once at class-load time rather than transcribed by
 * hand (transcribing eight cross-references by hand is exactly the kind of seed a mistyped date
 * would slip through undetected).
 */
public final class LunarEclipseCatalog {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** The kind of umbral eclipse. Penumbral eclipses are deliberately never represented here. */
    public enum Kind {
        PARTIAL,
        TOTAL
    }

    /**
     * One catalogued lunar eclipse: every published contact instant, in UTC, plus the two
     * editorial facts derived from this table rather than from ephemeris.
     *
     * @param date                the London civil date of greatest eclipse — not necessarily the
     *                            UTC calendar date of {@code max}; see
     *                            {@link LunarEclipseCatalog#londonCivilDateOfMax}
     * @param kind                PARTIAL or TOTAL
     * @param umbralMagnitude     fraction of the Moon's diameter inside the umbra at greatest
     *                            eclipse; {@code kind == TOTAL} iff this is {@code ≥ 1.0}
     * @param p1                  penumbral eclipse begins, UTC
     * @param u1                  umbral (partial) eclipse begins, UTC
     * @param u2                  totality begins, UTC — null for a partial eclipse
     * @param max                 greatest eclipse, UTC
     * @param u3                  totality ends, UTC — null for a partial eclipse
     * @param u4                  umbral (partial) eclipse ends, UTC
     * @param p4                  penumbral eclipse ends, UTC
     * @param nextComparable      the London civil date of the next catalogued UK-visible lunar
     *                            eclipse, or null for the last entry — see the class javadoc
     * @param nextComparableKind  {@code "total"} or {@code "partial"}, the kind of
     *                            {@link #nextComparable}; null exactly when it is
     */
    public record LunarEclipse(
            LocalDate date,
            Kind kind,
            double umbralMagnitude,
            LocalDateTime p1,
            LocalDateTime u1,
            LocalDateTime u2,
            LocalDateTime max,
            LocalDateTime u3,
            LocalDateTime u4,
            LocalDateTime p4,
            LocalDate nextComparable,
            String nextComparableKind) {

        private static final double TOTAL_MAGNITUDE_THRESHOLD = 1.0;

        /**
         * Validates the invariants a mistyped seed would break, so a transcription error fails at
         * class-load time rather than shipping a wrong figure.
         */
        public LunarEclipse {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(p1, "p1");
            Objects.requireNonNull(u1, "u1");
            Objects.requireNonNull(max, "max");
            Objects.requireNonNull(u4, "u4");
            Objects.requireNonNull(p4, "p4");

            boolean isTotal = kind == Kind.TOTAL;
            if (isTotal != (umbralMagnitude >= TOTAL_MAGNITUDE_THRESHOLD)) {
                throw new IllegalArgumentException(
                        "kind " + kind + " disagrees with umbralMagnitude " + umbralMagnitude);
            }
            if ((nextComparable == null) != (nextComparableKind == null)) {
                throw new IllegalArgumentException(
                        "nextComparable and nextComparableKind must be both null or both set");
            }

            if (!p1.isBefore(u1)) {
                throw new IllegalArgumentException("p1 must be before u1: " + p1 + " / " + u1);
            }
            // u1 < u4 follows transitively from either branch below (u1 < max < u4, with u2/u3
            // sandwiched in between for a total eclipse), so it is not checked again separately.
            //
            // The u2/u3-presence check and the ordering check that dereferences them are
            // deliberately ONE block per kind, not two — a static analyser (CodeQL flagged this in
            // review, #911) cannot see that `isTotal != (u2 != null)` earlier in the method implies
            // u2 is non-null here, so the null guard has to sit immediately before the dereference
            // it protects, in the same branch.
            if (isTotal) {
                if (u2 == null || u3 == null) {
                    throw new IllegalArgumentException(
                            "kind " + kind + " requires u2/u3 non-null iff TOTAL, got u2=" + u2 + " u3=" + u3);
                }
                if (!u1.isBefore(u2) || !u2.isBefore(max) || !max.isBefore(u3) || !u3.isBefore(u4)) {
                    throw new IllegalArgumentException(
                            "contacts out of order: u1=" + u1 + " u2=" + u2 + " max=" + max
                                    + " u3=" + u3 + " u4=" + u4);
                }
            } else {
                if (u2 != null || u3 != null) {
                    throw new IllegalArgumentException(
                            "kind " + kind + " requires u2/u3 non-null iff TOTAL, got u2=" + u2 + " u3=" + u3);
                }
                if (!u1.isBefore(max) || !max.isBefore(u4)) {
                    throw new IllegalArgumentException(
                            "contacts out of order: u1=" + u1 + " max=" + max + " u4=" + u4);
                }
            }
            if (!u4.isBefore(p4)) {
                throw new IllegalArgumentException("u4 must be before p4: " + u4 + " / " + p4);
            }
        }
    }

    /**
     * Derives the London civil date of an instant given in UTC — the calendar date a UK reader's
     * clock shows, which is not always the UTC calendar date: a 23:30 UTC instant in British Summer
     * Time reads 00:30 the next day locally.
     *
     * <p>Package-private so {@code LunarEclipseCatalogTest} can pin the midnight-crossing case
     * directly; none of the seven seeded entries happens to cross London midnight at {@code max}
     * (verified by inspection of the transcribed times below), so this method is the only place
     * that behaviour is exercised.
     *
     * @param utcInstant the instant, expressed as a UTC {@link LocalDateTime}
     * @return the London civil date of that instant
     */
    static LocalDate londonCivilDateOfMax(LocalDateTime utcInstant) {
        return utcInstant.atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).toLocalDate();
    }

    /**
     * 2025 Mar 14 total lunar eclipse — the most recent one before this catalogue's cutover date,
     * seeded so the "first visible from here since …" sentence has somewhere to start counting.
     * Umbral magnitude 1.18038; UK-visible sets-in-eclipse: the Moon is 12° up at U1 from the UK
     * centre reference point, has set by greatest eclipse (−3.5°), and every seeded reference point
     * shows a negative altitude by U4 — a morning eclipse the Moon sets during.
     */
    private static final LunarEclipse LE_2025_03_14 = raw(
            Kind.TOTAL, 1.18038,
            LocalDateTime.of(2025, 3, 14, 3, 57, 9),
            LocalDateTime.of(2025, 3, 14, 5, 9, 22),
            LocalDateTime.of(2025, 3, 14, 6, 25, 57),
            LocalDateTime.of(2025, 3, 14, 6, 58, 44),
            LocalDateTime.of(2025, 3, 14, 7, 32, 1),
            LocalDateTime.of(2025, 3, 14, 8, 48, 18),
            LocalDateTime.of(2025, 3, 14, 10, 0, 31));

    /**
     * 2025 Sep 07 total lunar eclipse — also historical, seeded for the same reason as the March
     * entry above, and worth seeding in its own right because the design draft's "first since March
     * 2025" copy is wrong: this eclipse is UK-visible too, later in the same year (plan
     * {@code lunar-eclipse-plan.md} §2.2's own caveat on this row). Umbral magnitude 1.36379;
     * rises-in-eclipse: the Moon is still 17°–22° below the horizon at U1 across all six reference
     * points, and up (8°–14°) by U4 — an evening eclipse the Moon rises already inside.
     */
    private static final LunarEclipse LE_2025_09_07 = raw(
            Kind.TOTAL, 1.36379,
            LocalDateTime.of(2025, 9, 7, 15, 28, 6),
            LocalDateTime.of(2025, 9, 7, 16, 26, 51),
            LocalDateTime.of(2025, 9, 7, 17, 30, 37),
            LocalDateTime.of(2025, 9, 7, 18, 11, 46),
            LocalDateTime.of(2025, 9, 7, 18, 53, 18),
            LocalDateTime.of(2025, 9, 7, 19, 56, 53),
            LocalDateTime.of(2025, 9, 7, 20, 55, 28));

    /**
     * 2026 Aug 28 deep partial lunar eclipse — the worked example throughout
     * {@code lunar-eclipse-plan.md} and the design bundle. Umbral magnitude 0.93187 (design copy
     * rounds this to "93%"). Independently checked at Dunstanburgh (55.49°N, 1.59°W): this
     * catalogue's {@code max} instant puts the Moon at 7.65° altitude, 241° azimuth (WSW) — against
     * the design's own stated "7° ± 2° up at 244° ± 4°" — and {@code MoonriseMoonsetCalculator}
     * puts moonset there at 06:18:46 BST, 3 minutes from the design's stated "06:16" and, in UTC
     * terms (05:18:46), inside {@code [u1, u4]} (02:33:21–05:52:09 UTC) — the Moon sets there while
     * still in the umbra, matching the design's "sets 06:16 still in shadow."
     */
    private static final LunarEclipse LE_2026_08_28 = raw(
            Kind.PARTIAL, 0.93187,
            LocalDateTime.of(2026, 8, 28, 1, 23, 29),
            LocalDateTime.of(2026, 8, 28, 2, 33, 21),
            null,
            LocalDateTime.of(2026, 8, 28, 4, 12, 52),
            null,
            LocalDateTime.of(2026, 8, 28, 5, 52, 9),
            LocalDateTime.of(2026, 8, 28, 7, 1, 59));

    /**
     * 2028 Jan 12 partial lunar eclipse — a shallow one, umbral magnitude 0.0679 (independently
     * confirmed against Wikipedia's own transcription of the Five Millennium Canon, since NASA's
     * decade-table summary rounds it to a coarser "0.066"). UK-visible throughout: the Moon is
     * 30°–43° up across all six reference points at every one of U1, greatest eclipse and U4 — high
     * in a January sky the whole time, never touching moonrise or moonset.
     */
    private static final LunarEclipse LE_2028_01_12 = raw(
            Kind.PARTIAL, 0.0679,
            LocalDateTime.of(2028, 1, 12, 2, 7, 24),
            LocalDateTime.of(2028, 1, 12, 3, 44, 46),
            null,
            LocalDateTime.of(2028, 1, 12, 4, 13, 0),
            null,
            LocalDateTime.of(2028, 1, 12, 4, 41, 38),
            LocalDateTime.of(2028, 1, 12, 6, 18, 50));

    /**
     * 2028 Dec 31 total lunar eclipse — a New Year's Eve evening event, umbral magnitude 1.24785.
     * Rises-in-eclipse at most of Britain: at the UK centre reference point the Moon is still 3.2°
     * below the horizon at U1 but up (7.8°) by greatest eclipse; further north, at Shetland (60.15°N),
     * it has already risen (+1.6°) by U1. Every reference point shows the Moon comfortably up
     * (18°–24°) by U4.
     */
    private static final LunarEclipse LE_2028_12_31 = raw(
            Kind.TOTAL, 1.24785,
            LocalDateTime.of(2028, 12, 31, 14, 3, 27),
            LocalDateTime.of(2028, 12, 31, 15, 7, 17),
            LocalDateTime.of(2028, 12, 31, 16, 16, 6),
            LocalDateTime.of(2028, 12, 31, 16, 52, 1),
            LocalDateTime.of(2028, 12, 31, 17, 28, 8),
            LocalDateTime.of(2028, 12, 31, 18, 36, 53),
            LocalDateTime.of(2028, 12, 31, 19, 40, 33));

    /**
     * 2029 Jun 26 total lunar eclipse — a pre-dawn event and the deepest of this catalogue, umbral
     * magnitude 1.8452 (totality alone runs 102.7 minutes). The Moon is up (4°–15°) at U1 across
     * every reference point, has set at the two most northerly ones (Shetland, Caithness) by
     * greatest eclipse, and has set everywhere by U4 (−6° to −13°) — a night the Moon sets while
     * still eclipsed.
     */
    private static final LunarEclipse LE_2029_06_26 = raw(
            Kind.TOTAL, 1.8452,
            LocalDateTime.of(2029, 6, 26, 0, 34, 11),
            LocalDateTime.of(2029, 6, 26, 1, 31, 57),
            LocalDateTime.of(2029, 6, 26, 2, 30, 48),
            LocalDateTime.of(2029, 6, 26, 3, 22, 8),
            LocalDateTime.of(2029, 6, 26, 4, 13, 28),
            LocalDateTime.of(2029, 6, 26, 5, 12, 17),
            LocalDateTime.of(2029, 6, 26, 6, 10, 12));

    /**
     * 2029 Dec 20 total lunar eclipse — a full-evening event, umbral magnitude 1.11895, the Moon
     * comfortably up (41°–63°) across every reference point at U1, greatest eclipse and U4 alike.
     * U4 and P4 fall after midnight UTC, on 21 December.
     */
    private static final LunarEclipse LE_2029_12_20 = raw(
            Kind.TOTAL, 1.11895,
            LocalDateTime.of(2029, 12, 20, 19, 42, 27),
            LocalDateTime.of(2029, 12, 20, 20, 54, 54),
            LocalDateTime.of(2029, 12, 20, 22, 14, 44),
            LocalDateTime.of(2029, 12, 20, 22, 41, 57),
            LocalDateTime.of(2029, 12, 20, 23, 9, 7),
            LocalDateTime.of(2029, 12, 21, 0, 29, 1),
            LocalDateTime.of(2029, 12, 21, 1, 41, 22));

    /**
     * Every eclipse this catalogue knows about, in date order, with {@code nextComparable}/
     * {@code nextComparableKind} filled in from each entry's successor — see the class javadoc.
     */
    private static final List<LunarEclipse> ECLIPSES = link(List.of(
            LE_2025_03_14,
            LE_2025_09_07,
            LE_2026_08_28,
            LE_2028_01_12,
            LE_2028_12_31,
            LE_2029_06_26,
            LE_2029_12_20));

    private LunarEclipseCatalog() {
    }

    /**
     * Builds one catalogue entry with {@code nextComparable}/{@code nextComparableKind} left null —
     * {@link #link} fills them in once the whole table is assembled — and {@code date} computed
     * from {@code max} rather than transcribed, so the date and the instant it is derived from can
     * never silently disagree.
     */
    private static LunarEclipse raw(Kind kind, double umbralMagnitude, LocalDateTime p1, LocalDateTime u1,
            LocalDateTime u2, LocalDateTime max, LocalDateTime u3, LocalDateTime u4, LocalDateTime p4) {
        return new LunarEclipse(londonCivilDateOfMax(max), kind, umbralMagnitude, p1, u1, u2, max, u3, u4, p4,
                null, null);
    }

    /**
     * Fills in {@code nextComparable}/{@code nextComparableKind} on every entry but the last from
     * its successor in the given (already date-ordered) list.
     */
    private static List<LunarEclipse> link(List<LunarEclipse> ordered) {
        List<LunarEclipse> linked = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            LunarEclipse entry = ordered.get(i);
            LunarEclipse next = i + 1 < ordered.size() ? ordered.get(i + 1) : null;
            LocalDate nextDate = next == null ? null : next.date();
            String nextKind = next == null ? null : next.kind().name().toLowerCase(Locale.ROOT);
            linked.add(new LunarEclipse(entry.date(), entry.kind(), entry.umbralMagnitude(), entry.p1(),
                    entry.u1(), entry.u2(), entry.max(), entry.u3(), entry.u4(), entry.p4(), nextDate, nextKind));
        }
        return List.copyOf(linked);
    }

    /**
     * The catalogued lunar eclipses falling within the given range, inclusive of both ends, keyed
     * on {@link LunarEclipse#date()}.
     *
     * @param from first day of interest
     * @param to   last day of interest
     * @return the eclipses in the range, in date order; empty when none fall in it
     */
    public static List<LunarEclipse> between(LocalDate from, LocalDate to) {
        return ECLIPSES.stream()
                .filter(eclipse -> !eclipse.date().isBefore(from) && !eclipse.date().isAfter(to))
                .toList();
    }

    /**
     * The eclipse whose London civil date of greatest eclipse falls on the given date, if any.
     *
     * @param date the date to look up
     * @return the eclipse on that date, or empty
     */
    public static Optional<LunarEclipse> on(LocalDate date) {
        return ECLIPSES.stream().filter(eclipse -> eclipse.date().equals(date)).findFirst();
    }

    /**
     * Every catalogued eclipse, for tests that pin the table's contents.
     *
     * @return an unmodifiable view of the catalogue
     */
    public static List<LunarEclipse> all() {
        return ECLIPSES;
    }
}
