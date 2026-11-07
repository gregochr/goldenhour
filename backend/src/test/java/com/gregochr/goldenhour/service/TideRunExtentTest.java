package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TideRunExtent}.
 */
@ExtendWith(MockitoExtension.class)
class TideRunExtentTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 16);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private LunarPhaseService lunar;

    @Mock
    private TideSizeIndex index;

    private final List<LocationEntity> coastal = List.of(
            LocationEntity.builder().id(1L).name("Coastal 1").lat(55.0).lon(-1.5).enabled(true).build());
    private TideRunExtent extent;

    @BeforeEach
    void setUp() {
        extent = new TideRunExtent(lunar, index);
    }

    private void sizes(TideSizeIndex.Sizes sizes) {
        when(index.measure(eq(coastal), eq(FROM.minusDays(7)), eq(TO.plusDays(7)))).thenReturn(sizes);
    }

    private void stub(LocalDate date, boolean perigean, LunarTideType tide) {
        when(lunar.nearestSyzygyIsPerigean(eq(date))).thenReturn(perigean);
        when(lunar.classifyTide(eq(date))).thenReturn(tide);
    }

    /** Stubs a date that belongs to the run: its syzygy has the run's kind and it is tidal. */
    private void belongs(LocalDate date, boolean king) {
        stub(date, king, LunarTideType.SPRING_TIDE);
    }

    /** Stubs a date of the run's kind that is not tidal (the lunar arithmetic calls it regular). */
    private void regular(LocalDate date, boolean king) {
        stub(date, king, LunarTideType.REGULAR_TIDE);
    }

    /** Stubs a date whose nearest syzygy is the other kind of run, so it never reaches the tidal test. */
    private void otherKind(LocalDate date, boolean king) {
        when(lunar.nearestSyzygyIsPerigean(eq(date))).thenReturn(!king);
    }

    private static void neverAsked(LunarPhaseService service, LocalDate date) {
        verify(service, never()).nearestSyzygyIsPerigean(eq(date));
    }

    @Test
    @DisplayName("walks back across a lunar-only yesterday and stops at the first non-tidal day")
    void walksBackAcrossLunarOnlyDay() {
        sizes(TideSizeIndex.Sizes.UNMEASURED);
        belongs(FROM.minusDays(1), false);
        regular(FROM.minusDays(2), false);

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM, FROM.plusDays(1)), false, FROM, TO, coastal);

        assertThat(result).isEqualTo(new TideRunExtent.Extent(FROM.minusDays(1), FROM.plusDays(1)));
        neverAsked(lunar, FROM.minusDays(3));
    }

    @Test
    @DisplayName("walks back across a height-only day the index calls spring")
    void walksBackAcrossHeightOnlyDay() {
        sizes(new TideSizeIndex.Sizes(Set.of(FROM.minusDays(1), FROM.minusDays(2)), Set.of(), true, Map.of()));
        regular(FROM.minusDays(1), false);
        regular(FROM.minusDays(2), false);
        regular(FROM.minusDays(3), false);

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM.minusDays(2));
        assertThat(result.end()).isEqualTo(FROM);
        neverAsked(lunar, FROM.minusDays(4));
    }

    @Test
    @DisplayName("stops at a tidal day that belongs to the other kind of run")
    void stopsAtOtherKind() {
        sizes(TideSizeIndex.Sizes.UNMEASURED);
        otherKind(FROM.minusDays(1), false);

        TideRunExtent.Extent spring = extent.extentOf(List.of(FROM), false, FROM, TO, coastal);

        assertThat(spring.start()).isEqualTo(FROM);
        neverAsked(lunar, FROM.minusDays(2));
        verify(lunar, never()).classifyTide(eq(FROM.minusDays(1)));
    }

    @Test
    @DisplayName("a king run walks only perigean days")
    void kingRunWalksPerigeanDays() {
        sizes(TideSizeIndex.Sizes.UNMEASURED);
        for (int i = 1; i <= 3; i++) {
            belongs(FROM.minusDays(i), true);
        }
        otherKind(FROM.minusDays(4), true);
        for (int i = 1; i <= 5; i++) {
            belongs(TO.plusDays(i), true);
        }
        otherKind(TO.plusDays(6), true);

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM, TO), true, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM.minusDays(3));
        assertThat(result.end()).isEqualTo(TO.plusDays(5));
    }

    @Test
    @DisplayName("walks forward past the window end by lunar arithmetic")
    void walksForwardPastToDate() {
        sizes(TideSizeIndex.Sizes.UNMEASURED);
        belongs(TO.plusDays(1), false);
        belongs(TO.plusDays(2), false);
        regular(TO.plusDays(3), false);

        TideRunExtent.Extent result = extent.extentOf(
                List.of(FROM.plusDays(2), TO), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM.plusDays(2));
        assertThat(result.end()).isEqualTo(FROM.plusDays(5));
        neverAsked(lunar, FROM.minusDays(1));
        neverAsked(lunar, TO.plusDays(4));
    }

    @Test
    @DisplayName("a first candidate after fromDate is the start with no walk, even if fromDate qualifies")
    void noWalkWhenCandidatesAreInsideTheWindow() {
        TideRunExtent.Extent result = extent.extentOf(
                List.of(FROM.plusDays(1), FROM.plusDays(2)), false, FROM, TO, coastal);

        assertThat(result).isEqualTo(new TideRunExtent.Extent(FROM.plusDays(1), FROM.plusDays(2)));
        verify(index, never()).measure(eq(coastal), eq(FROM.minusDays(7)), eq(TO.plusDays(7)));
        verifyNoInteractions(lunar);
    }

    @Test
    @DisplayName("only the edge that touches the window walks")
    void onlyTheTouchingEdgeWalks() {
        sizes(TideSizeIndex.Sizes.UNMEASURED);
        for (int i = 1; i <= 7; i++) {
            belongs(TO.plusDays(i), false);
        }

        TideRunExtent.Extent result = extent.extentOf(
                List.of(FROM.plusDays(1), TO), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM.plusDays(1));
        assertThat(result.end()).isEqualTo(TO.plusDays(7));
        neverAsked(lunar, FROM.minusDays(1));
    }

    @Test
    @DisplayName("each walk is capped at seven days")
    void walksAreCapped() {
        sizes(TideSizeIndex.Sizes.UNMEASURED);
        for (int i = 1; i <= 7; i++) {
            belongs(FROM.minusDays(i), false);
            belongs(TO.plusDays(i), false);
        }

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM, TO), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM.minusDays(7));
        assertThat(result.end()).isEqualTo(TO.plusDays(7));
        neverAsked(lunar, FROM.minusDays(8));
        neverAsked(lunar, TO.plusDays(8));
    }

    @Test
    @DisplayName("no candidates gives a null extent and touches nothing")
    void emptyCandidates() {
        assertThat(extent.extentOf(List.of(), false, FROM, TO, coastal)).isNull();
        assertThat(extent.extentOf(null, false, FROM, TO, coastal)).isNull();
        verifyNoInteractions(lunar);
        verifyNoInteractions(index);
    }

    @Test
    @DisplayName("an unmeasured index leaves only the lunar arm")
    void unmeasuredIndexUsesLunarOnly() {
        sizes(TideSizeIndex.Sizes.UNMEASURED);
        regular(FROM.minusDays(1), false);

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM);
    }

    @Test
    @DisplayName("a king-only height day does not extend the run")
    void kingOnlyHeightDoesNotExtend() {
        sizes(new TideSizeIndex.Sizes(Set.of(), Set.of(FROM.minusDays(1)), true, Map.of()));
        regular(FROM.minusDays(1), false);

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM);
    }

    @Test
    @DisplayName("the index is measured over one fixed window for both edges")
    void measuresOneFixedWindow() {
        sizes(TideSizeIndex.Sizes.UNMEASURED);
        otherKind(FROM.minusDays(1), false);
        otherKind(TO.plusDays(1), false);

        extent.extentOf(List.of(FROM, TO), false, FROM, TO, coastal);

        verify(index).measure(eq(coastal), eq(FROM.minusDays(7)), eq(TO.plusDays(7)));
    }
}
