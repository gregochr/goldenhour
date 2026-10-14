package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideSizeIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link DailyRangeHistory}. */
class DailyRangeHistoryTest {

    private static final LocationEntity SEAHAM = LocationEntity.builder().id(1L).name("Seaham").build();
    private static final LocalDate BUILT = LocalDate.of(2026, 10, 20);

    private TideRunBuilder tideRunBuilder;
    private TideSizeIndex tideSizeIndex;
    private DailyRangeHistory history;

    @BeforeEach
    void setUp() {
        tideRunBuilder = mock(TideRunBuilder.class);
        tideSizeIndex = mock(TideSizeIndex.class);
        history = new DailyRangeHistory(tideRunBuilder, tideSizeIndex);
    }

    private static List<LocalDate> yearBefore(LocalDate built) {
        List<LocalDate> dates = new ArrayList<>();
        for (int back = 365; back >= 1; back--) {
            dates.add(built.minusDays(back));
        }
        return dates;
    }

    private void stubYear(LocalDate built, int days, double value) {
        Map<LocalDate, Double> year = new LinkedHashMap<>();
        List<LocalDate> dates = yearBefore(built);
        for (int i = 0; i < days; i++) {
            year.put(dates.get(i), value);
        }
        when(tideRunBuilder.dailyRangesAt(eq(SEAHAM), eq(dates))).thenReturn(year);
    }

    @Test
    @DisplayName("the sample is the 365 civil days ending the day before the build date")
    void sampleWindowEndsTheDayBeforeTheBuildDate() {
        stubYear(BUILT, 365, 3.0);

        history.sampleFor(SEAHAM, BUILT);

        org.mockito.ArgumentCaptor<List<LocalDate>> asked = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(tideRunBuilder).dailyRangesAt(eq(SEAHAM), asked.capture());
        assertThat(asked.getValue()).hasSize(365);
        assertThat(asked.getValue().getFirst()).isEqualTo(LocalDate.of(2025, 10, 20));
        assertThat(asked.getValue().getLast()).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    @Test
    @DisplayName("299 derivable days is insufficient, 300 is sufficient")
    void sufficiencyBoundary() {
        stubYear(BUILT, 299, 3.0);
        assertThat(history.sampleFor(SEAHAM, BUILT).sufficient()).isFalse();
        assertThat(history.sampleFor(SEAHAM, BUILT).displayed()).isEmpty();

        history.evict();
        stubYear(BUILT, 300, 3.0);
        DailyRangeHistory.YearSample enough = history.sampleFor(SEAHAM, BUILT);

        assertThat(enough.sufficient()).isTrue();
        assertThat(enough.n()).isEqualTo(300);
        assertThat(enough.average()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("the average is the one-decimal mean of the raw ranges")
    void averageRoundsLikeTheHeadline() {
        Map<LocalDate, Double> year = new LinkedHashMap<>();
        List<LocalDate> dates = yearBefore(BUILT);
        for (int i = 0; i < 365; i++) {
            year.put(dates.get(i), i % 2 == 0 ? 3.04 : 3.07);
        }
        when(tideRunBuilder.dailyRangesAt(eq(SEAHAM), eq(dates))).thenReturn(year);

        // 183 x 3.04 + 182 x 3.07 over 365 = 3.0549... -> 3.1
        assertThat(history.sampleFor(SEAHAM, BUILT).average()).isEqualTo(3.1);
    }

    @Test
    @DisplayName("percentile counts sample days DISPLAYING at least the day's range: a 4.85 sample "
            + "(displays 4.9) counts for a 4.9 day, and the biggest day is top 1%")
    void percentileInDisplaySpace() {
        Map<LocalDate, Double> year = new LinkedHashMap<>();
        List<LocalDate> dates = yearBefore(BUILT);
        for (int i = 0; i < 365; i++) {
            year.put(dates.get(i), i < 40 ? 6.05 - 1.20 : 3.0);
        }
        when(tideRunBuilder.dailyRangesAt(eq(SEAHAM), eq(dates))).thenReturn(year);
        DailyRangeHistory.YearSample sample = history.sampleFor(SEAHAM, BUILT);

        assertThat(sample.percentileOf(4.9)).isEqualTo(11);
        assertThat(sample.percentileOf(5.0)).isEqualTo(1);
        assertThat(sample.percentileOf(3.0)).isEqualTo(100);
        assertThat(sample.percentileOf(2.0)).isEqualTo(100);
    }

    @Test
    @DisplayName("a mid-year value ranks by ceil(100 x count / n)")
    void midValuePercentile() {
        Map<LocalDate, Double> year = new LinkedHashMap<>();
        List<LocalDate> dates = yearBefore(BUILT);
        for (int i = 0; i < 365; i++) {
            year.put(dates.get(i), 1.0 + i * 0.01);
        }
        when(tideRunBuilder.dailyRangesAt(eq(SEAHAM), eq(dates))).thenReturn(year);

        // values 1.00 .. 4.64; days DISPLAYING >= 3.0 are 2.95 .. 4.64 = 170 -> ceil(17000 / 365) = 47
        assertThat(history.sampleFor(SEAHAM, BUILT).percentileOf(3.0)).isEqualTo(47);
    }

    @Test
    @DisplayName("the memo answers a repeat question once, and an eviction makes it read again")
    void memoHitAndEviction() {
        stubYear(BUILT, 365, 3.0);

        history.sampleFor(SEAHAM, BUILT);
        history.sampleFor(SEAHAM, BUILT);
        verify(tideRunBuilder, times(1)).dailyRangesAt(any(), anyList());

        history.evict();
        history.sampleFor(SEAHAM, BUILT);
        verify(tideRunBuilder, times(2)).dailyRangesAt(any(), anyList());
    }

    @Test
    @DisplayName("tide ingestion's eviction notice drops the memo")
    void subscribesToTheIndexEviction() {
        org.mockito.ArgumentCaptor<Runnable> hook = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(tideSizeIndex).onEvicted(hook.capture());
        stubYear(BUILT, 365, 3.0);
        history.sampleFor(SEAHAM, BUILT);

        hook.getValue().run();
        history.sampleFor(SEAHAM, BUILT);

        verify(tideRunBuilder, times(2)).dailyRangesAt(any(), anyList());
    }

    @Test
    @DisplayName("the memo turns over on the real UTC day, not the build date: a rewound build gets its own "
            + "entry and does not roll the other")
    void rewoundBuildDoesNotRollTheMemo() {
        LocalDate rewound = BUILT.minusDays(3);
        stubYear(BUILT, 365, 3.0);
        stubYear(rewound, 365, 3.0);

        history.sampleFor(SEAHAM, BUILT);
        history.sampleFor(SEAHAM, rewound);
        history.sampleFor(SEAHAM, BUILT);
        history.sampleFor(SEAHAM, rewound);

        verify(tideRunBuilder, times(2)).dailyRangesAt(any(), anyList());
    }

    @Test
    @DisplayName("a failed read propagates and is NOT memoised: the next call reads again")
    void failureIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        when(tideRunBuilder.dailyRangesAt(eq(SEAHAM), anyList())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("db down");
            }
            Map<LocalDate, Double> year = new LinkedHashMap<>();
            yearBefore(BUILT).forEach(d -> year.put(d, 3.0));
            return year;
        });

        assertThatThrownBy(() -> history.sampleFor(SEAHAM, BUILT)).isInstanceOf(IllegalStateException.class);

        assertThat(history.sampleFor(SEAHAM, BUILT).sufficient()).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("one-decimal rounding matches the headline %.1f over a sweep of height differences and x.x5 edges")
    void oneDecimalRoundingMatchesTheHeadlineFormat() {
        for (int high = 0; high <= 1200; high += 7) {
            for (int low = -300; low <= 400; low += 3) {
                double v = high / 100.0 + 0.001 * (high % 11) - low / 100.0 - 0.001 * (low % 7);
                assertThat(DailyRangeHistory.metresToOneDp(v)).as("%s", v).isEqualTo(expected(v));
            }
        }
        for (int i = 0; i < 2000; i++) {
            double edge = i / 100.0 + 0.05;
            assertThat(DailyRangeHistory.metresToOneDp(edge)).as("%s", edge).isEqualTo(expected(edge));
            assertThat(DailyRangeHistory.metresToOneDp(i / 20.0)).isEqualTo(expected(i / 20.0));
        }
        assertThat(DailyRangeHistory.metresToOneDp(8.966 - 1.516)).isEqualTo(7.4);
        assertThat(DailyRangeHistory.metresToOneDp(6.05 - 1.20)).isEqualTo(4.9);
    }

    private static double expected(double v) {
        return Double.parseDouble(String.format(java.util.Locale.UK, "%.1f", v));
    }
}
