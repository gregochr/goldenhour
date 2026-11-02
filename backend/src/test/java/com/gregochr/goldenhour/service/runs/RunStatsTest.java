package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.service.runs.RunStats.Sample;
import com.gregochr.goldenhour.service.runs.RunStats.Window;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic behind medians, 90th percentiles, "slow" and the speed rank, over a sliding window.
 */
class RunStatsTest {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final StatKey KEY = StatKey.ofCycle(CycleType.NIGHTLY);

    /** n samples one day apart with durations 100, 110, 120 ... seconds. */
    private static List<Sample> samples(int n) {
        List<Sample> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Sample(START.plusSeconds(86_400L * i), 100L + 10L * i, 1_000_000L + i));
        }
        return list;
    }

    private static RunStats stats(List<Sample> samples) {
        return new RunStats(Map.of(KEY, samples));
    }

    @Test
    @DisplayName("the window is the 30 runs before a run, strictly earlier, and slides as the run moves")
    void windowSlides() {
        RunStats stats = stats(samples(40));

        assertThat(stats.before(KEY, START.plusSeconds(86_400L * 35))).hasSize(30)
                .first().extracting(Sample::durationSeconds).isEqualTo(100L + 10L * 5);
        assertThat(stats.before(KEY, START.plusSeconds(86_400L * 3))).hasSize(3);
        assertThat(stats.before(KEY, START)).isEmpty();
        assertThat(stats.before(StatKey.SKY_EVAL, START.plusSeconds(86_400L * 3))).isEmpty();
    }

    @Test
    @DisplayName("median, nearest-rank p90 and the median cost over a window")
    void windowStatistics() {
        Window w = RunStats.windowOf(samples(10));

        assertThat(w.size()).isEqualTo(10);
        assertThat(w.medianSeconds()).isEqualTo(145L);
        assertThat(w.p90Seconds()).isEqualTo(180L);
        assertThat(w.medianCost()).isEqualTo(1_000_004L);
        assertThat(RunStats.windowOf(List.of())).isEqualTo(Window.EMPTY);
        assertThat(RunStats.percentile(List.of(), 0.9)).isNull();
        assertThat(RunStats.percentile(List.of(7L), 0.9)).isEqualTo(7L);
    }

    @Test
    @DisplayName("a window with no known cost has no median cost")
    void noCosts() {
        Window w = RunStats.windowOf(List.of(new Sample(START, 60L, null), new Sample(START.plusSeconds(1), 90L,
                null)));

        assertThat(w.medianCost()).isNull();
        assertThat(w.medianSeconds()).isEqualTo(75L);
    }

    @Test
    @DisplayName("slow means above the p90 of the previous window, and needs at least ten earlier runs")
    void slow() {
        RunStats stats = stats(samples(20));
        Instant at = START.plusSeconds(86_400L * 20);

        // The previous 20 runs run 100..290 seconds, p90 (rank 18) = 270.
        assertThat(stats.slow(KEY, at, 271L)).isTrue();
        assertThat(stats.slow(KEY, at, 270L)).isFalse();
        assertThat(stats.slow(KEY, at, null)).isFalse();
        // Only three earlier runs: not enough to judge.
        assertThat(stats.slow(KEY, START.plusSeconds(86_400L * 3), 100_000L)).isFalse();
    }

    @Test
    @DisplayName("faster-than is the share of the previous window that took longer, null on thin history")
    void fasterThan() {
        RunStats stats = stats(samples(20));
        Instant at = START.plusSeconds(86_400L * 20);

        assertThat(stats.fasterThanPercent(KEY, at, 195L)).isEqualTo(50);
        assertThat(stats.fasterThanPercent(KEY, at, 1L)).isEqualTo(100);
        assertThat(stats.fasterThanPercent(KEY, at, 10_000L)).isZero();
        assertThat(stats.fasterThanPercent(KEY, at, null)).isNull();
        assertThat(stats.fasterThanPercent(KEY, START.plusSeconds(86_400L * 3), 50L)).isNull();
    }

    @Test
    @DisplayName("the window statistics as of a moment come from the samples before it")
    void windowAsOf() {
        RunStats stats = stats(samples(12));

        assertThat(stats.window(KEY, START.plusSeconds(86_400L * 12)).size()).isEqualTo(12);
        assertThat(stats.window(KEY, START.plusSeconds(86_400L * 4)).size()).isEqualTo(4);
    }
}
