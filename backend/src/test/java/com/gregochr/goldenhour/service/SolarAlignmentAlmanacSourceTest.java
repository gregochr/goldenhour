package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link SolarAlignmentAlmanacSource}. */
class SolarAlignmentAlmanacSourceTest {

    private final SolarAlignmentAlmanacSource source = new SolarAlignmentAlmanacSource();

    @Test
    @DisplayName("equinox anchors agree with EquinoxHotTopicStrategy on every day of a year — the "
            + "feed and the Plan tab must not disagree about when the equinox is")
    void equinoxAnchorsAgreeWithTheHotTopicStrategy() {
        // The strategy keeps its anchors private, so they are restated in the almanac source. This
        // is the test that makes that duplication safe: it compares the two day by day rather than
        // comparing constants, so a change to either window width or either date fails here.
        LocalDate day = LocalDate.of(2026, 1, 1);
        LocalDate endOfYear = LocalDate.of(2026, 12, 31);

        int agreements = 0;
        while (!day.isAfter(endOfYear)) {
            assertThat(SolarAlignmentAlmanacSource.isNearEquinox(day))
                    .as("equinox membership disagrees on %s", day)
                    .isEqualTo(EquinoxHotTopicStrategy.isNearEquinox(day));
            agreements++;
            day = day.plusDays(1);
        }
        // Guards against the loop silently not running — 365 comparisons or the test is vacuous.
        assertThat(agreements).isEqualTo(365);
    }

    @Test
    @DisplayName("a leap year is compared too, since February shifts every anchor after it")
    void equinoxAnchorsAgreeInALeapYear() {
        LocalDate day = LocalDate.of(2028, 1, 1);
        while (!day.isAfter(LocalDate.of(2028, 12, 31))) {
            assertThat(SolarAlignmentAlmanacSource.isNearEquinox(day))
                    .as("equinox membership disagrees on %s", day)
                    .isEqualTo(EquinoxHotTopicStrategy.isNearEquinox(day));
            day = day.plusDays(1);
        }
    }

    @Test
    @DisplayName("all four turning points appear in a full year and each spans seven days")
    void aFullYearCarriesFourTurningPoints() {
        List<AlmanacEvent> events =
                source.events(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThat(events).hasSize(4);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.dayCount()).isEqualTo(7);
            assertThat(e.kind()).isEqualTo(AlmanacKind.ALMANAC);
            assertThat(e.regions()).isEmpty();
        });
        assertThat(events).extracting(AlmanacEvent::title)
                .containsExactlyInAnyOrder("Spring equinox", "Summer solstice",
                        "Autumn equinox", "Winter solstice");
    }

    @Test
    @DisplayName("a window overlapping only the tail of an equinox still reports the full span")
    void anOverlappingWindowReportsTheTrueSpan() {
        // Window opens on the equinox itself; the entry began three days earlier and says so
        // rather than being clipped to look like it starts today.
        List<AlmanacEvent> events =
                source.events(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 21));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().startDate()).isEqualTo(LocalDate.of(2026, 3, 17));
        assertThat(events.getFirst().endDate()).isEqualTo(LocalDate.of(2026, 3, 23));
        assertThat(events.getFirst().meta()).containsEntry("peakDate", "2026-03-20");
    }

    @Test
    @DisplayName("a window entirely between turning points is empty")
    void aQuietWindowIsEmpty() {
        assertThat(source.events(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31))).isEmpty();
    }

    @Test
    @DisplayName("a window straddling new year picks up December and the following January onwards")
    void straddlesTheYearBoundary() {
        List<AlmanacEvent> events =
                source.events(LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 31));

        assertThat(events).extracting(AlmanacEvent::title).contains("Winter solstice");
        assertThat(events).extracting(AlmanacEvent::startDate)
                .contains(LocalDate.of(2026, 12, 18));
    }

    @Test
    @DisplayName("the solstice type is distinct from the equinox type so a client can style them apart")
    void solsticeAndEquinoxCarryDifferentTypes() {
        List<AlmanacEvent> june =
                source.events(LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 24));
        List<AlmanacEvent> march =
                source.events(LocalDate.of(2026, 3, 17), LocalDate.of(2026, 3, 23));

        assertThat(june.getFirst().type()).isEqualTo(SolarAlignmentAlmanacSource.TYPE_SOLSTICE);
        assertThat(march.getFirst().type()).isEqualTo(SolarAlignmentAlmanacSource.TYPE_EQUINOX);
    }

    @Test
    @DisplayName("the two solstices carry different copy — longest and shortest day are not the "
            + "same event with a different date")
    void solsticesDoNotShareCopy() {
        AlmanacEvent june = source.events(
                LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 24)).getFirst();
        AlmanacEvent december = source.events(
                LocalDate.of(2026, 12, 18), LocalDate.of(2026, 12, 24)).getFirst();

        assertThat(june.detail()).isNotEqualTo(december.detail());
        assertThat(june.detail()).contains("longest day");
        assertThat(december.detail()).contains("shortest day");
    }
}
