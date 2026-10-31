package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.EnvironmentAgencyGaugeClient.GaugeReading;
import com.gregochr.goldenhour.repository.TideHighWater;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure arithmetic for the gauge check: high-water extraction from gauge readings, pairing with stored
 * predictions, and medians. No I/O, so each rule is testable on synthetic data.
 */
public final class TideGaugeMath {

    /** Half-width of the window over which a reading must be the maximum to count as a high water. */
    static final Duration LOCAL_MAX_HALF_WINDOW = Duration.ofHours(2);

    /** Half-width within which a measured high water is paired with a stored predicted one. */
    static final Duration PAIRING_LIMIT = Duration.ofHours(2);

    private static final int FIT_HALF_POINTS = 2;
    private static final double SUM_K2 = 10.0;
    private static final double SUM_K4 = 34.0;
    private static final double FIT_POINTS = 5.0;
    private static final double FIT_DETERMINANT = FIT_POINTS * SUM_K4 - SUM_K2 * SUM_K2;
    private static final double CONCAVITY_EPSILON = 1e-12;
    private static final double MILLIS_PER_MINUTE = 60_000.0;

    private TideGaugeMath() {
    }

    /**
     * A measured high water.
     *
     * @param time   refined peak time
     * @param height refined peak height, metres
     */
    public record ObservedHigh(Instant time, double height) { }

    /**
     * A measured high water paired with its stored prediction.
     *
     * @param timingErrorMinutes predicted minus observed time, minutes
     * @param heightErrorMetres  predicted minus observed height, metres
     */
    public record PairedHigh(double timingErrorMinutes, double heightErrorMetres) { }

    /**
     * Extracts high waters from readings sorted by ascending time.
     *
     * <p>A reading qualifies when it is the maximum of every reading within two hours either side
     * (the earliest of any tied plateau), the window is covered by readings with no gap longer than
     * twice the step, and the reading has two equally spaced neighbours each side. It is then refined
     * by a five-point quadratic peak.
     *
     * @param readings readings in ascending time order
     * @return the high waters, in time order
     */
    public static List<ObservedHigh> extractHighWaters(List<GaugeReading> readings) {
        List<ObservedHigh> highs = new ArrayList<>();
        int n = readings.size();
        for (int i = FIT_HALF_POINTS; i < n - FIT_HALF_POINTS; i++) {
            Duration step = Duration.between(readings.get(i - 1).time(), readings.get(i).time());
            if (step.isZero() || step.isNegative() || !equallySpaced(readings, i, step)) {
                continue;
            }
            if (!isWindowMaximum(readings, i, step)) {
                continue;
            }
            highs.add(refinePeak(readings, i, step));
        }
        return highs;
    }

    private static boolean equallySpaced(List<GaugeReading> r, int i, Duration step) {
        for (int k = -FIT_HALF_POINTS; k < FIT_HALF_POINTS; k++) {
            if (!Duration.between(r.get(i + k).time(), r.get(i + k + 1).time()).equals(step)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWindowMaximum(List<GaugeReading> r, int i, Duration step) {
        Instant centre = r.get(i).time();
        Instant from = centre.minus(LOCAL_MAX_HALF_WINDOW);
        Instant to = centre.plus(LOCAL_MAX_HALF_WINDOW);
        double value = r.get(i).height();
        Instant previous = null;
        Instant first = null;
        Instant last = null;
        Duration maxGap = step.multipliedBy(2);
        for (GaugeReading reading : r) {
            Instant t = reading.time();
            if (t.isBefore(from) || t.isAfter(to)) {
                continue;
            }
            if (first == null) {
                first = t;
            }
            if (previous != null && Duration.between(previous, t).compareTo(maxGap) > 0) {
                return false;
            }
            previous = t;
            last = t;
            boolean beats = t.isBefore(centre) ? reading.height() >= value : reading.height() > value;
            if (beats) {
                return false;
            }
        }
        return first != null
                && !first.isAfter(from.plus(step)) && !last.isBefore(to.minus(step));
    }

    /**
     * Fits a least-squares parabola to the five readings centred on {@code i} and returns its vertex,
     * or the raw reading when the fit is degenerate (not concave, or vertex outside the fitted span).
     */
    static ObservedHigh refinePeak(List<GaugeReading> r, int i, Duration step) {
        double sumY = 0;
        double sumKy = 0;
        double sumK2y = 0;
        for (int k = -FIT_HALF_POINTS; k <= FIT_HALF_POINTS; k++) {
            double y = r.get(i + k).height();
            sumY += y;
            sumKy += k * y;
            sumK2y += (double) k * k * y;
        }
        double c = (FIT_POINTS * sumK2y - SUM_K2 * sumY) / FIT_DETERMINANT;
        double a = (SUM_K4 * sumY - SUM_K2 * sumK2y) / FIT_DETERMINANT;
        double b = sumKy / SUM_K2;
        GaugeReading raw = r.get(i);
        if (c >= -CONCAVITY_EPSILON) {
            return new ObservedHigh(raw.time(), raw.height());
        }
        double vertexK = -b / (2 * c);
        if (Math.abs(vertexK) > FIT_HALF_POINTS) {
            return new ObservedHigh(raw.time(), raw.height());
        }
        double height = a + b * vertexK + c * vertexK * vertexK;
        long offsetMillis = Math.round(vertexK * step.toMillis());
        return new ObservedHigh(raw.time().plusMillis(offsetMillis), height);
    }

    /**
     * Pairs each measured high water with the nearest stored predicted high water within two hours,
     * using each prediction at most once.
     *
     * @param observed  measured high waters
     * @param predicted stored predicted high waters (UTC)
     * @return the pairs, in observed order
     */
    public static List<PairedHigh> pair(List<ObservedHigh> observed, List<TideHighWater> predicted) {
        List<PairedHigh> pairs = new ArrayList<>();
        boolean[] used = new boolean[predicted.size()];
        for (ObservedHigh high : observed) {
            int best = -1;
            Duration bestGap = null;
            for (int j = 0; j < predicted.size(); j++) {
                if (used[j]) {
                    continue;
                }
                Instant p = predicted.get(j).eventTime().toInstant(ZoneOffset.UTC);
                Duration gap = Duration.between(high.time(), p).abs();
                if (gap.compareTo(PAIRING_LIMIT) <= 0 && (bestGap == null || gap.compareTo(bestGap) < 0)) {
                    best = j;
                    bestGap = gap;
                }
            }
            if (best >= 0) {
                used[best] = true;
                TideHighWater match = predicted.get(best);
                Instant p = match.eventTime().toInstant(ZoneOffset.UTC);
                double minutes = Duration.between(high.time(), p).toMillis() / MILLIS_PER_MINUTE;
                pairs.add(new PairedHigh(minutes, match.heightMetres().doubleValue() - high.height()));
            }
        }
        return pairs;
    }

    /**
     * Median of the values; the mean of the middle two when the count is even.
     *
     * @param values at least one value
     * @return the median
     */
    public static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
