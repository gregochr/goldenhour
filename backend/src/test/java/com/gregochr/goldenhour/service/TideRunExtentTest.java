package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link TideRunExtent}.
 */
class TideRunExtentTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 16);
    private static final LocalDate TO = FROM.plusDays(3);

    private final LunarPhaseService lunar = mock(LunarPhaseService.class);
    private final TideSizeIndex index = mock(TideSizeIndex.class);
    private final List<LocationEntity> coastal = List.of(
            LocationEntity.builder().id(1L).name("Coastal 1").lat(55.0).lon(-1.5).enabled(true).build());
    private TideRunExtent extent;

    @BeforeEach
    void setUp() {
        extent = new TideRunExtent(lunar, index);
        sizes(TideSizeIndex.Sizes.UNMEASURED);
    }

    private void sizes(TideSizeIndex.Sizes sizes) {
        lenient().when(index.measure(eq(coastal), eq(FROM.minusDays(7)), eq(TO.plusDays(7))))
                .thenReturn(sizes);
    }

    /**
     * Stubs the lunar service for every date in a wide range.
     *
     * @param perigean dates whose nearest syzygy is perigean
     * @param tidal    dates the lunar arithmetic classifies as near syzygy
     */
    private void lunar(Set<LocalDate> perigean, Set<LocalDate> tidal) {
        for (int offset = -20; offset <= 20; offset++) {
            LocalDate d = FROM.plusDays(offset);
            lenient().when(lunar.nearestSyzygyIsPerigean(eq(d))).thenReturn(perigean.contains(d));
            lenient().when(lunar.classifyTide(eq(d))).thenReturn(tidal.contains(d)
                    ? LunarTideType.SPRING_TIDE : LunarTideType.REGULAR_TIDE);
        }
    }

    private static Set<LocalDate> range(int fromOffset, int toOffset) {
        Set<LocalDate> dates = new java.util.HashSet<>();
        for (int i = fromOffset; i <= toOffset; i++) {
            dates.add(FROM.plusDays(i));
        }
        return dates;
    }

    @Test
    @DisplayName("walks back across a lunar-only yesterday and stops at the first non-tidal day")
    void walksBackAcrossLunarOnlyDay() {
        lunar(Set.of(), range(-1, 1));

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM, FROM.plusDays(1)), false, FROM, TO, coastal);

        assertThat(result).isEqualTo(new TideRunExtent.Extent(FROM.minusDays(1), FROM.plusDays(1)));
    }

    @Test
    @DisplayName("walks back across a height-only day the index calls spring")
    void walksBackAcrossHeightOnlyDay() {
        lunar(Set.of(), Set.of());
        sizes(new TideSizeIndex.Sizes(Set.of(FROM.minusDays(1), FROM.minusDays(2)), Set.of(), true, Map.of()));

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM.minusDays(2));
        assertThat(result.end()).isEqualTo(FROM);
    }

    @Test
    @DisplayName("stops at a tidal day that belongs to the other kind of run")
    void stopsAtOtherKind() {
        lunar(Set.of(FROM.minusDays(1)), range(-5, 5));

        TideRunExtent.Extent spring = extent.extentOf(List.of(FROM), false, FROM, TO, coastal);

        assertThat(spring.start()).isEqualTo(FROM);
    }

    @Test
    @DisplayName("a king run walks only perigean days")
    void kingRunWalksPerigeanDays() {
        lunar(range(-3, 8), range(-3, 8));

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM, TO), true, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM.minusDays(3));
        assertThat(result.end()).isEqualTo(TO.plusDays(5));
    }

    @Test
    @DisplayName("walks forward past the window end by lunar arithmetic")
    void walksForwardPastToDate() {
        lunar(Set.of(), range(2, 5));

        TideRunExtent.Extent result = extent.extentOf(
                List.of(FROM.plusDays(2), TO), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM.plusDays(2));
        assertThat(result.end()).isEqualTo(FROM.plusDays(5));
    }

    @Test
    @DisplayName("a first candidate after fromDate is the start with no walk, even if fromDate qualifies")
    void noWalkWhenCandidatesAreInsideTheWindow() {
        lunar(Set.of(), range(-5, 10));

        TideRunExtent.Extent result = extent.extentOf(
                List.of(FROM.plusDays(1), FROM.plusDays(2)), false, FROM, TO, coastal);

        assertThat(result).isEqualTo(new TideRunExtent.Extent(FROM.plusDays(1), FROM.plusDays(2)));
        verify(index, never()).measure(eq(coastal), eq(FROM.minusDays(7)), eq(TO.plusDays(7)));
    }

    @Test
    @DisplayName("only the edge that touches the window walks")
    void onlyTheTouchingEdgeWalks() {
        lunar(Set.of(), range(-5, 10));

        TideRunExtent.Extent result = extent.extentOf(
                List.of(FROM.plusDays(1), TO), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM.plusDays(1));
        assertThat(result.end()).isEqualTo(TO.plusDays(7));
    }

    @Test
    @DisplayName("each walk is capped at seven days")
    void walksAreCapped() {
        lunar(Set.of(), range(-20, 20));

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM, TO), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM.minusDays(7));
        assertThat(result.end()).isEqualTo(TO.plusDays(7));
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
        lunar(Set.of(), Set.of());
        sizes(TideSizeIndex.Sizes.UNMEASURED);

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM);
    }

    @Test
    @DisplayName("a king-only height day does not extend the run")
    void kingOnlyHeightDoesNotExtend() {
        lunar(Set.of(), Set.of());
        sizes(new TideSizeIndex.Sizes(Set.of(), Set.of(FROM.minusDays(1)), true, Map.of()));

        TideRunExtent.Extent result = extent.extentOf(List.of(FROM), false, FROM, TO, coastal);

        assertThat(result.start()).isEqualTo(FROM);
    }

    @Test
    @DisplayName("the index is measured over one fixed window for both edges")
    void measuresOneFixedWindow() {
        lunar(Set.of(), Set.of());

        extent.extentOf(List.of(FROM, TO), false, FROM, TO, coastal);

        verify(index).measure(eq(coastal), eq(FROM.minusDays(7)), eq(TO.plusDays(7)));
    }
}
