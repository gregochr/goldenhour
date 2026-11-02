package com.gregochr.goldenhour.service.runs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The finished-run samples the Day view's statistics are taken over, one ascending series per population, with the
 * arithmetic on them. The window slides in memory: a run is compared with the (up to) {@value #WINDOW} runs of its
 * population that started before it, so a past day's "slow" flag does not change as later runs arrive.
 */
final class RunStats {

    /** How many earlier runs a statistic is taken over. */
    static final int WINDOW = 30;

    /** Fewer earlier runs than this and no percentile, "slow" flag or speed rank is served. */
    static final int MIN_WINDOW = 10;

    /** The percentile "slow" is judged against. */
    private static final double SLOW_PERCENTILE = 0.9;

    /**
     * One finished run of a population.
     *
     * @param start           when it started
     * @param durationSeconds how long it took
     * @param costMicro       what it cost in micro-dollars, or null when unknown
     */
    record Sample(Instant start, long durationSeconds, Long costMicro) {
    }

    /**
     * The statistics of one window of samples.
     *
     * @param size          how many samples
     * @param medianSeconds the median duration, null when the window is empty
     * @param p90Seconds    the 90th-percentile duration, null when the window is empty
     * @param medianCost    the median known cost in micro-dollars, null when no sample has one
     */
    record Window(int size, Long medianSeconds, Long p90Seconds, Long medianCost) {

        /** The statistics of nothing. */
        static final Window EMPTY = new Window(0, null, null, null);
    }

    private final Map<StatKey, List<Sample>> series;

    /**
     * Wraps the series.
     *
     * @param series the samples per population, each ascending by start
     */
    RunStats(Map<StatKey, List<Sample>> series) {
        this.series = series;
    }

    /**
     * The window a run is judged against: the last {@value #WINDOW} samples that started before it.
     *
     * @param key   the run's population
     * @param start the run's start
     * @return the samples, ascending; empty when none
     */
    List<Sample> before(StatKey key, Instant start) {
        List<Sample> all = series.getOrDefault(key, List.of());
        int end = 0;
        while (end < all.size() && all.get(end).start().isBefore(start)) {
            end++;
        }
        return all.subList(Math.max(0, end - WINDOW), end);
    }

    /**
     * The statistics over the window before a moment.
     *
     * @param key  the population
     * @param asOf the moment
     * @return the median and 90th percentile; {@link Window#EMPTY} when no sample is earlier
     */
    Window window(StatKey key, Instant asOf) {
        return windowOf(before(key, asOf));
    }

    /**
     * Whether a run was slower than the 90th percentile of the window before it.
     *
     * @param key             the run's population
     * @param start           the run's start
     * @param durationSeconds the run's duration, null for none
     * @return true when the window has at least {@value #MIN_WINDOW} samples and the run took longer than its p90
     */
    boolean slow(StatKey key, Instant start, Long durationSeconds) {
        if (durationSeconds == null) {
            return false;
        }
        Window w = window(key, start);
        return w.size() >= MIN_WINDOW && w.p90Seconds() != null && durationSeconds > w.p90Seconds();
    }

    /**
     * The share of the window before a run that the run was faster than.
     *
     * @param key             the run's population
     * @param start           the run's start
     * @param durationSeconds the run's duration, null for none
     * @return 0 to 100, or null when the window has fewer than {@value #MIN_WINDOW} samples or the run has no duration
     */
    Integer fasterThanPercent(StatKey key, Instant start, Long durationSeconds) {
        if (durationSeconds == null) {
            return null;
        }
        List<Sample> w = before(key, start);
        if (w.size() < MIN_WINDOW) {
            return null;
        }
        long slower = w.stream().filter(s -> s.durationSeconds() > durationSeconds).count();
        return (int) Math.round(100.0 * slower / w.size());
    }

    /**
     * The statistics of a list of samples.
     *
     * @param samples the samples
     * @return their median and 90th percentile
     */
    static Window windowOf(List<Sample> samples) {
        if (samples.isEmpty()) {
            return Window.EMPTY;
        }
        List<Long> durations = new ArrayList<>(samples.stream().map(Sample::durationSeconds).toList());
        Collections.sort(durations);
        List<Long> costs = new ArrayList<>(samples.stream().map(Sample::costMicro)
                .filter(java.util.Objects::nonNull).toList());
        Collections.sort(costs);
        return new Window(samples.size(), AdminRunsService.median(durations), percentile(durations, SLOW_PERCENTILE),
                AdminRunsService.median(costs));
    }

    /**
     * The nearest-rank percentile of an ascending list.
     *
     * @param ascending the values, ascending
     * @param fraction  the percentile as a fraction, 0 to 1
     * @return the value at rank {@code ceil(fraction * n)}; null for an empty list
     */
    static Long percentile(List<Long> ascending, double fraction) {
        if (ascending.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(fraction * ascending.size());
        return ascending.get(Math.min(ascending.size(), Math.max(1, rank)) - 1);
    }
}
