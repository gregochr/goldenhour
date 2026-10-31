package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TideExtremeEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The outcome of comparing the tide extremes about to be replaced with the ones WorldTides just
 * returned for the same span (tide-source-integrity-plan P2).
 *
 * @param pairs           how many stored extremes were paired with a new one of the same type
 * @param maxLagMinutes   largest timing difference over the pairs, in minutes
 * @param maxHeightMetres largest height difference over the pairs, in metres
 * @param examples        the largest-lag changed pairs, for the alert (at most {@link #MAX_EXAMPLES})
 */
public record TideDiff(int pairs, double maxLagMinutes, double maxHeightMetres, List<Pair> examples) {

    /** A timing difference above this many minutes counts as a change. */
    public static final double LAG_THRESHOLD_MINUTES = 5.0;

    /** A height difference above this many metres counts as a change. */
    public static final double HEIGHT_THRESHOLD_METRES = 0.05;

    /** Largest gap between a stored extreme and its new counterpart for them to be paired. */
    public static final Duration PAIRING_WINDOW = Duration.ofHours(3);

    /** How many example pairs an alert carries per location. */
    public static final int MAX_EXAMPLES = 2;

    private static final double SECONDS_PER_MINUTE = 60.0;

    /**
     * One stored extreme and the new extreme it was paired with.
     *
     * @param type         HIGH or LOW
     * @param beforeTime   the stored time
     * @param afterTime    the new time
     * @param beforeHeight the stored height in metres
     * @param afterHeight  the new height in metres
     */
    public record Pair(String type, LocalDateTime beforeTime, LocalDateTime afterTime,
            BigDecimal beforeHeight, BigDecimal afterHeight) {

        double lagMinutes() {
            return Math.abs(Duration.between(beforeTime, afterTime).getSeconds()) / SECONDS_PER_MINUTE;
        }

        double heightDelta() {
            return beforeHeight.subtract(afterHeight).abs().doubleValue();
        }
    }

    /**
     * Whether this comparison showed a change.
     *
     * @return true when the timing or height delta exceeds its threshold
     */
    public boolean changed() {
        return exceeds(maxLagMinutes, maxHeightMetres);
    }

    /**
     * Whether recorded deltas exceed either threshold; null deltas (no comparison) never do.
     *
     * @param lagMinutes   largest timing difference, or null
     * @param heightMetres largest height difference, or null
     * @return true when either exceeds its threshold
     */
    public static boolean exceeds(Double lagMinutes, Double heightMetres) {
        return (lagMinutes != null && lagMinutes > LAG_THRESHOLD_MINUTES)
                || (heightMetres != null && heightMetres > HEIGHT_THRESHOLD_METRES);
    }

    /**
     * Pairs each stored extreme with the nearest unused new extreme of the same type within
     * {@link #PAIRING_WINDOW}, and measures the differences. Stored extremes with no counterpart
     * (and new ones beyond the stored span) are not counted.
     *
     * @param stored the extremes about to be replaced
     * @param fresh  the extremes WorldTides just returned
     * @return the comparison; {@code pairs} is 0 when nothing could be paired
     */
    public static TideDiff compute(List<TideExtremeEntity> stored, List<TideExtremeEntity> fresh) {
        List<TideExtremeEntity> unused = new ArrayList<>(fresh);
        List<Pair> pairs = new ArrayList<>();
        for (TideExtremeEntity before : stored) {
            TideExtremeEntity best = null;
            long bestGap = Long.MAX_VALUE;
            for (TideExtremeEntity candidate : unused) {
                if (candidate.getType() != before.getType()) {
                    continue;
                }
                long gap = Math.abs(Duration.between(before.getEventTime(), candidate.getEventTime())
                        .getSeconds());
                if (gap <= PAIRING_WINDOW.getSeconds() && gap < bestGap) {
                    best = candidate;
                    bestGap = gap;
                }
            }
            if (best != null) {
                unused.remove(best);
                pairs.add(new Pair(before.getType().name(), before.getEventTime(), best.getEventTime(),
                        before.getHeightMetres(), best.getHeightMetres()));
            }
        }
        double maxLag = pairs.stream().mapToDouble(Pair::lagMinutes).max().orElse(0.0);
        double maxHeight = pairs.stream().mapToDouble(Pair::heightDelta).max().orElse(0.0);
        List<Pair> examples = pairs.stream()
                .filter(p -> exceeds(p.lagMinutes(), p.heightDelta()))
                .sorted(Comparator.comparingDouble(Pair::lagMinutes).reversed())
                .limit(MAX_EXAMPLES)
                .toList();
        return new TideDiff(pairs.size(), maxLag, maxHeight, examples);
    }
}
