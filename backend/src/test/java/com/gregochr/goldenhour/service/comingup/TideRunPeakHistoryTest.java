package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.service.LunarPhaseService;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideSizeIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link TideRunPeakHistory}. */
@ExtendWith(MockitoExtension.class)
class TideRunPeakHistoryTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 3, 5);
    private static final LocationEntity REPRESENTATIVE =
            LocationEntity.builder().id(1L).name("Seaham").lat(54.84).lon(-1.33).build();
    private static final List<LocationEntity> ROSTER = List.of(REPRESENTATIVE);

    @Mock
    private TideSizeIndex tideSizeIndex;

    @Mock
    private TideRunBuilder tideRunBuilder;

    @Mock
    private LunarPhaseService lunarPhaseService;

    private TideRunPeakHistory history;

    @BeforeEach
    void setUp() {
        history = new TideRunPeakHistory(tideSizeIndex, tideRunBuilder, lunarPhaseService);
    }

    @Test
    @DisplayName("the window ends yesterday, never today — a run in progress must not score "
            + "against its own still-forming peak — and starts LOOKBACK_YEARS before that, not "
            + "before today")
    void windowEndsYesterdayAndStartsLookbackYearsBeforeThat() {
        when(tideSizeIndex.measure(eq(ROSTER), any(), any())).thenReturn(TideSizeIndex.Sizes.UNMEASURED);
        when(lunarPhaseService.classifyTide(any())).thenReturn(LunarTideType.REGULAR_TIDE);

        history.peakRanges(REPRESENTATIVE, ROSTER, TODAY, TODAY);

        LocalDate expectedTo = TODAY.minusDays(1);
        LocalDate expectedFrom = expectedTo.minusYears(TideRunPeakHistory.LOOKBACK_YEARS);
        verify(tideSizeIndex).measure(eq(ROSTER), eq(expectedFrom), eq(expectedTo));
    }

    @Test
    @DisplayName("when the run being scored itself started before today, the window ends the day "
            + "before THAT start, never merely yesterday — otherwise the run's own already-elapsed "
            + "days would qualify as roster-wide spring/king days and re-enter the very "
            + "distribution its own peak is compared against, scoring the run against itself")
    void windowClampsToTheRunsOwnStart_whenEarlierThanYesterday() {
        LocalDate runStart = TODAY.minusDays(3);
        when(tideSizeIndex.measure(eq(ROSTER), any(), any())).thenReturn(TideSizeIndex.Sizes.UNMEASURED);
        when(lunarPhaseService.classifyTide(any())).thenReturn(LunarTideType.REGULAR_TIDE);

        history.peakRanges(REPRESENTATIVE, ROSTER, TODAY, runStart);

        verify(tideSizeIndex).measure(eq(ROSTER), any(), eq(runStart.minusDays(1)));
    }

    @Test
    @DisplayName("a run starting AFTER today (fully in the future) does not shrink the window below "
            + "yesterday — only an earlier-than-today run start ever clamps it")
    void windowIsNotExtendedByAFutureRunStart() {
        LocalDate futureStart = TODAY.plusDays(10);
        when(tideSizeIndex.measure(eq(ROSTER), any(), any())).thenReturn(TideSizeIndex.Sizes.UNMEASURED);
        when(lunarPhaseService.classifyTide(any())).thenReturn(LunarTideType.REGULAR_TIDE);

        history.peakRanges(REPRESENTATIVE, ROSTER, TODAY, futureStart);

        verify(tideSizeIndex).measure(eq(ROSTER), any(), eq(TODAY.minusDays(1)));
    }

    @Test
    @DisplayName("groups consecutive qualifying days into one run and reads its peak at the fixed "
            + "representative — not once per day")
    void groupsConsecutiveDaysIntoOneRun() {
        LocalDate day1 = LocalDate.of(2027, 1, 10);
        LocalDate day2 = LocalDate.of(2027, 1, 11);
        TideSizeIndex.Sizes sizes = new TideSizeIndex.Sizes(Set.of(day1, day2), Set.of(), true);
        when(tideSizeIndex.measure(eq(ROSTER), any(), any())).thenReturn(sizes);
        when(tideRunBuilder.peakRangeAt(eq(REPRESENTATIVE), eq(List.of(day1, day2))))
                .thenReturn(OptionalDouble.of(4.6));

        List<Double> peaks = history.peakRanges(REPRESENTATIVE, ROSTER, TODAY, TODAY);

        assertThat(peaks).containsExactly(4.6);
    }

    @Test
    @DisplayName("two runs separated by a gap are two peaks, not one")
    void separatedRunsAreTwoPeaks() {
        LocalDate day1 = LocalDate.of(2027, 1, 10);
        LocalDate day2 = LocalDate.of(2027, 1, 24);
        TideSizeIndex.Sizes sizes = new TideSizeIndex.Sizes(Set.of(day1, day2), Set.of(), true);
        when(tideSizeIndex.measure(eq(ROSTER), any(), any())).thenReturn(sizes);
        when(tideRunBuilder.peakRangeAt(eq(REPRESENTATIVE), eq(List.of(day1))))
                .thenReturn(OptionalDouble.of(3.1));
        when(tideRunBuilder.peakRangeAt(eq(REPRESENTATIVE), eq(List.of(day2))))
                .thenReturn(OptionalDouble.of(5.0));

        List<Double> peaks = history.peakRanges(REPRESENTATIVE, ROSTER, TODAY, TODAY);

        assertThat(peaks).containsExactly(3.1, 5.0);
    }

    @Test
    @DisplayName("a run with no derivable peak at the representative contributes nothing")
    void undeliverableRunIsDropped() {
        LocalDate day1 = LocalDate.of(2027, 1, 10);
        TideSizeIndex.Sizes sizes = new TideSizeIndex.Sizes(Set.of(day1), Set.of(), true);
        when(tideSizeIndex.measure(eq(ROSTER), any(), any())).thenReturn(sizes);
        when(tideRunBuilder.peakRangeAt(eq(REPRESENTATIVE), eq(List.of(day1))))
                .thenReturn(OptionalDouble.empty());

        assertThat(history.peakRanges(REPRESENTATIVE, ROSTER, TODAY, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("an unmeasurable roster falls back to the lunar classifier, matching "
            + "TideAlmanacSource's own fallback rule")
    void unmeasurableRosterFallsBackToLunar() {
        LocalDate day1 = LocalDate.of(2027, 1, 10);
        LocalDate day2 = LocalDate.of(2027, 1, 11);
        when(lunarPhaseService.classifyTide(day1)).thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.classifyTide(day2)).thenReturn(LunarTideType.REGULAR_TIDE);

        List<List<LocalDate>> runs =
                history.groupIntoRuns(day1, day2, TideSizeIndex.Sizes.UNMEASURED);

        assertThat(runs).containsExactly(List.of(day1));
    }

    @Test
    @DisplayName("null representative, null/empty roster, null today, or null run start all yield "
            + "no history rather than throwing")
    void degenerateInputsYieldEmpty() {
        assertThat(history.peakRanges(null, ROSTER, TODAY, TODAY)).isEmpty();
        assertThat(history.peakRanges(REPRESENTATIVE, List.of(), TODAY, TODAY)).isEmpty();
        assertThat(history.peakRanges(REPRESENTATIVE, null, TODAY, TODAY)).isEmpty();
        assertThat(history.peakRanges(REPRESENTATIVE, ROSTER, null, TODAY)).isEmpty();
        assertThat(history.peakRanges(REPRESENTATIVE, ROSTER, TODAY, null)).isEmpty();
        verify(tideSizeIndex, never()).measure(any(), any(), any());
    }
}
