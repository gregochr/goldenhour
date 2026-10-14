package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideSizeIndex;
import com.gregochr.goldenhour.util.DayScopedMemo;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The past year's daily tidal ranges at a tide run's representative port, so each day of a run can be
 * ranked against the year and compared with one average (tide-range-rank-plan.md §2, R1).
 *
 * <p>A day's range is exactly the one {@code dayRanges} serves ({@link TideRunBuilder#dailyRangesAt}),
 * so the rank and the bar can never be built from two definitions of "range". The sample is the 365
 * civil days ending the day before the feed's build date; a day counts only when both a high and a low
 * can be derived for it.
 *
 * <p><b>Ranked in display space.</b> Every sample value is rounded to one decimal place with the same
 * rule the served figure is spelt with ({@link #metresToOneDp}) before it is compared, and a day ranks
 * by how many sample values display at least as large as it does. Comparing raw values would let a
 * day that reads {@code 4.9 m} rank below a sample day that also reads {@code 4.9 m}.
 *
 * <p><b>Memo.</b> Keyed on the representative and the build date, and turned over on the real UTC day
 * (never the build date, so an admin's rewound build cannot roll it); dropped when {@link TideSizeIndex}
 * reports that stored extremes changed. A failed read is never stored: the exception leaves the loader
 * and the memo keeps nothing, so the next call reads again.
 */
@Component
public class DailyRangeHistory {

    /** Days in the year sample. */
    static final int SAMPLE_DAYS = 365;

    /** Fewest derivable days a sample may hold before it is trusted to rank or average anything. */
    static final int MIN_SAMPLE_DAYS = 300;

    private final TideRunBuilder tideRunBuilder;

    private final DayScopedMemo<SampleKey, YearSample> samples = new DayScopedMemo<>();

    /** The question {@link #samples} answers. */
    private record SampleKey(Long representativeId, LocalDate builtFor) { }

    /**
     * Constructs a {@code DailyRangeHistory}.
     *
     * @param tideRunBuilder supplies the per-day ranges
     * @param tideSizeIndex  announces when stored extremes change, which drops the memo
     */
    public DailyRangeHistory(TideRunBuilder tideRunBuilder, TideSizeIndex tideSizeIndex) {
        this.tideRunBuilder = tideRunBuilder;
        tideSizeIndex.onEvicted(this::evict);
    }

    /** Drops every memoised sample so the next request recomputes it. */
    public void evict() {
        samples.evictAll();
    }

    /**
     * The year sample for a representative, memoised for the UTC day.
     *
     * @param representative the run's representative port
     * @param builtFor       the UK civil date the feed is built for; the sample ends the day before
     * @return the sample, which may be insufficient ({@link YearSample#sufficient()})
     * @throws RuntimeException whatever the repository read threw; nothing is memoised in that case
     */
    public YearSample sampleFor(LocationEntity representative, LocalDate builtFor) {
        return samples.get(LocalDate.now(ZoneOffset.UTC), new SampleKey(representative.getId(), builtFor),
                () -> load(representative, builtFor));
    }

    private YearSample load(LocationEntity representative, LocalDate builtFor) {
        List<LocalDate> dates = new ArrayList<>(SAMPLE_DAYS);
        for (int back = SAMPLE_DAYS; back >= 1; back--) {
            dates.add(builtFor.minusDays(back));
        }
        Map<LocalDate, Double> ranges = tideRunBuilder.dailyRangesAt(representative, dates);
        if (ranges.size() < MIN_SAMPLE_DAYS) {
            return new YearSample(ranges.size(), 0.0, List.of(), false);
        }
        List<Double> displayed = ranges.values().stream().map(DailyRangeHistory::metresToOneDp).toList();
        double mean = ranges.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return new YearSample(displayed.size(), metresToOneDp(mean), List.copyOf(displayed), true);
    }

    /**
     * A range in metres to one decimal place by the rule the headline figure is spelt with
     * ({@code TideWording.metres}'s {@code "%.1f"}).
     *
     * @param metres a range in metres
     * @return the range rounded to one decimal place
     */
    static double metresToOneDp(double metres) {
        return Double.parseDouble(String.format(Locale.UK, "%.1f", metres));
    }

    /**
     * One representative's year of daily ranges.
     *
     * @param n          how many days were derivable
     * @param average    the mean daily range to one decimal place; meaningful only when sufficient
     * @param displayed  every derivable day's range to one decimal place; empty when insufficient
     * @param sufficient true when {@code n} reaches {@link #MIN_SAMPLE_DAYS}
     */
    public record YearSample(int n, double average, List<Double> displayed, boolean sufficient) {

        /**
         * Where a day's displayed range ranks in the year, as "top N%": the share of sample days
         * displaying at least as large a range, rounded up, never below 1.
         *
         * @param displayedRange the day's range already rounded to one decimal place
         * @return a percentile from 1 (biggest) to 100
         */
        public int percentileOf(double displayedRange) {
            long atLeast = displayed.stream().filter(s -> s >= displayedRange).count();
            return (int) Math.max(1L, (100L * atLeast + n - 1) / n);
        }
    }
}
