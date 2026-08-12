package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gregochr.goldenhour.model.BesselianElements;
import com.gregochr.goldenhour.model.EclipseCircumstances;
import com.gregochr.goldenhour.util.EclipseCalculator;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The seeded eclipse table.
 *
 * <p><b>The point of this class is the transcription check.</b> Each catalogue entry is fourteen
 * numbers copied by hand from NASA's published table, and a single mistyped digit produces a
 * plausible-looking eclipse at the wrong time or the wrong depth — the kind of error no amount of
 * internal consistency will reveal, because every downstream figure is derived from the same wrong
 * elements. So each entry is reduced at the UK location that published sources name as its deepest,
 * and the obscuration this app computes is checked against the figure those sources quote.
 *
 * <p>Agreement to about a point across five independent eclipses is not something a transcription
 * error survives, and it checks the reduction at the same time.
 */
class EclipseCatalogTest {

    /**
     * An eclipse, the UK location published sources name as its deepest, and the coverage they
     * quote there.
     *
     * @param date            the eclipse's date
     * @param place           a human-readable name for the reduction point, for assertion messages
     * @param latitude        that point's latitude
     * @param longitude       that point's longitude, east positive
     * @param publishedPct    the obscuration published for it, as a whole percentage
     */
    private record Published(LocalDate date, String place, double latitude, double longitude,
            int publishedPct) { }

    /**
     * The published UK figures, from the Royal Observatory / Sky at Night guides to UK eclipses.
     *
     * <p>Every one of these reduction points has the Sun ABOVE the horizon at greatest eclipse, so
     * these assertions exercise the raw reduction and are unaffected by the horizon clamp. That is
     * deliberate: this class checks the elements, and {@code EclipseCalculatorTest} checks the clamp.
     */
    private static final List<Published> PUBLISHED = List.of(
            new Published(LocalDate.of(2026, 8, 12), "Isles of Scilly", 49.9160, -6.3220, 96),
            new Published(LocalDate.of(2027, 8, 2), "Isles of Scilly", 49.9160, -6.3220, 48),
            new Published(LocalDate.of(2028, 1, 26), "Cornwall", 50.0657, -5.7132, 55),
            new Published(LocalDate.of(2029, 6, 12), "Shetland", 60.1546, -1.1494, 19),
            // Dover, not London: the published 49% is quoted for "southeast England", and London
            // sits a point shallower than the corner of Kent the figure actually describes.
            // Choosing the point the source means is what lets the tolerance below be tight.
            new Published(LocalDate.of(2030, 6, 1), "Dover", 51.1279, 1.3134, 49));

    /**
     * Percentage points of slack — published figures are quoted rounded, and for a region rather
     * than a point.
     *
     * <p>Deliberately tight. The five entries land within 0.6 points of their published figures, so
     * a full point of slack is generous, and it has to stay generous-but-small to be worth anything:
     * at the 1.6 this class started with, a mistyped digit in the 2028 x-velocity coefficient moved
     * Cornwall's coverage by 1.65 points and the test still passed. That mutant now fails.
     */
    private static final double TOLERANCE_PCT = 1.0;

    /** The UTC maximum this build computes at a point, truncated to the minute that gets printed. */
    private static LocalTime maximumAt(LocalDate date, double latitude, double longitude) {
        return EclipseCalculator.circumstances(elementsOn(date), latitude, longitude)
                .orElseThrow(() -> new AssertionError("not visible at " + latitude + ", " + longitude))
                .maximumUtc()
                .toLocalTime()
                .truncatedTo(ChronoUnit.MINUTES);
    }

    private static BesselianElements elementsOn(LocalDate date) {
        return EclipseCatalog.on(date)
                .orElseThrow(() -> new AssertionError("not catalogued: " + date))
                .elements();
    }

    @Nested
    @DisplayName("The elements, checked against published UK coverage")
    class Transcription {

        @Test
        @DisplayName("every seeded eclipse reproduces the coverage published for it")
        void everyEntryMatchesItsPublishedFigure() {
            for (Published expected : PUBLISHED) {
                BesselianElements elements = EclipseCatalog.on(expected.date())
                        .orElseThrow(() -> new AssertionError("not catalogued: " + expected.date()))
                        .elements();

                Optional<EclipseCircumstances> seen = EclipseCalculator.circumstances(
                        elements, expected.latitude(), expected.longitude());

                assertThat(seen)
                        .as("%s should see the %s eclipse", expected.place(), expected.date())
                        .isPresent();
                assertThat(seen.get().obscuration() * 100)
                        .as("%s eclipse at %s: published %d%%",
                                expected.date(), expected.place(), expected.publishedPct())
                        .isCloseTo(expected.publishedPct(), within(TOLERANCE_PCT));
            }
        }

        @Test
        @DisplayName("2027 also reproduces the Sun's published altitude, which timing errors move")
        void theSunsAltitudeMatchesToo() {
            // A second, independent quantity on the same elements, and one that coverage alone does
            // not constrain: altitude follows the CLOCK, so a coefficient error that shifts the
            // eclipse in time shows up here even where it barely moves the depth. Published for the
            // Isles of Scilly on 2027 August 02: 37 degrees above the eastern horizon.
            EclipseCircumstances c = EclipseCalculator
                    .circumstances(elementsOn(LocalDate.of(2027, 8, 2)), 49.9160, -6.3220)
                    .orElseThrow();

            assertThat(c.sunAltitudeDeg()).isCloseTo(37.0, within(1.5));
        }

        @Test
        @DisplayName("the maximum each entry produces is pinned, to catch an accidental edit")
        void maximumTimesArePinned() {
            // ⚠️ NOT an independent check, and it must not be read as one. No source publishes UK
            // maximum times for these four to the minute, so these values come from THIS build.
            // Their job is change detection: 56 coefficients were copied in by hand and a later
            // accidental edit to any of them moves a time here. The coverage assertions above are
            // the correctness check; this one is the tripwire.
            //
            // Asserted to the minute because that is what every surface prints — a mutation moving
            // the eclipse by seconds is not a defect this app can express.
            assertThat(maximumAt(LocalDate.of(2027, 8, 2), 49.9160, -6.3220))
                    .isEqualTo(LocalTime.of(8, 54));
            assertThat(maximumAt(LocalDate.of(2028, 1, 26), 50.0657, -5.7132))
                    .isEqualTo(LocalTime.of(16, 48));
            assertThat(maximumAt(LocalDate.of(2029, 6, 12), 60.1546, -1.1494))
                    .isEqualTo(LocalTime.of(3, 15));
            assertThat(maximumAt(LocalDate.of(2030, 6, 1), 51.1279, 1.3134))
                    .isEqualTo(LocalTime.of(5, 20));
        }

        @Test
        @DisplayName("each entry's designation names the date its elements are for")
        void designationMatchesTheDate() {
            // Cheap, and it catches the copy-paste failure the block structure invites: elements
            // pasted under the wrong constant, which every numerical assertion above would still
            // pass if two entries were swapped wholesale.
            for (EclipseCatalog.Eclipse eclipse : EclipseCatalog.all()) {
                assertThat(eclipse.elements().designation())
                        .as("designation of the %s entry", eclipse.date())
                        .contains(String.valueOf(eclipse.date().getYear()));
                assertThat(eclipse.elements().t0Date()).isEqualTo(eclipse.date());
            }
        }
    }

    @Nested
    @DisplayName("The table itself")
    class Table {

        @Test
        @DisplayName("holds every UK-visible eclipse out to 2030, in date order")
        void isInStrictDateOrder() {
            List<LocalDate> dates = EclipseCatalog.all().stream().map(EclipseCatalog.Eclipse::date).toList();

            assertThat(dates).containsExactly(
                    LocalDate.of(2026, 8, 12),
                    LocalDate.of(2027, 8, 2),
                    LocalDate.of(2028, 1, 26),
                    LocalDate.of(2029, 6, 12),
                    LocalDate.of(2030, 6, 1));
            assertThat(dates).isSorted();
        }

        @Test
        @DisplayName("only the 2026 eclipse claims a return period, because only its was researched")
        void onlyTheResearchedReturnPeriodIsSeeded() {
            // `nextComparable` is documented as researched-or-null. 2026's was established by
            // reducing 2081 September 03 and confirming nothing between reaches the same depth over
            // Britain — 99% in Scilly, against 57-67% for the four entries in between. For those
            // four, "comparable" would be a guess, so the field is null and the rarity line simply
            // does not appear.
            assertThat(EclipseCatalog.all())
                    .filteredOn(eclipse -> eclipse.returnYears() != null)
                    .extracting(EclipseCatalog.Eclipse::date)
                    .containsExactly(LocalDate.of(2026, 8, 12));

            assertThat(EclipseCatalog.on(LocalDate.of(2026, 8, 12)).orElseThrow().returnYears())
                    .isEqualTo(55);
        }

        @Test
        @DisplayName("only the 2026 eclipse is rare enough to earn a rarity line")
        void onlyTheDeepOneIsRare() {
            assertThat(EclipseCatalog.all())
                    .filteredOn(EclipseCatalog.Eclipse::isRare)
                    .extracting(EclipseCatalog.Eclipse::date)
                    .containsExactly(LocalDate.of(2026, 8, 12));
        }

        @Test
        @DisplayName("between() finds each entry on its own date and nothing on the days around it")
        void betweenFindsEachEntry() {
            for (EclipseCatalog.Eclipse eclipse : EclipseCatalog.all()) {
                LocalDate date = eclipse.date();
                assertThat(EclipseCatalog.between(date, date))
                        .as("between() on %s", date)
                        .extracting(EclipseCatalog.Eclipse::date)
                        .containsExactly(date);
                assertThat(EclipseCatalog.between(date.plusDays(1), date.plusDays(30)))
                        .as("the month after %s", date)
                        .noneMatch(other -> other.date().equals(date));
            }
        }

        @Test
        @DisplayName("a range spanning the whole table returns all of it, once each")
        void aWideRangeReturnsEverything() {
            assertThat(EclipseCatalog.between(LocalDate.of(2026, 1, 1), LocalDate.of(2031, 1, 1)))
                    .hasSameSizeAs(EclipseCatalog.all());
        }
    }
}
