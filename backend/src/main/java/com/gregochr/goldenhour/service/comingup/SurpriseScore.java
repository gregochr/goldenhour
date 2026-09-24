package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.model.comingup.ComingUpBands;

import java.util.List;

/**
 * The surprise model's pure maths (README §3, plan D4): {@code S = rarity + magnitude}, both
 * surprisal in bits.
 *
 * <p>Every number this class needs comes in as a parameter or a {@link ComingUpScoringProperties}
 * knob — nothing here is a topic-specific constant, so the UI and every caller share one place the
 * transform happens.
 */
public final class SurpriseScore {

    /**
     * Magnitude assigned to an occurrence with no per-topic distribution to compare against — the
     * median, by definition typical (README §3, plan D4): "a shower's ZHR is a catalogue constant,
     * so its occurrence is by definition typical" unless a real per-occurrence distribution exists.
     */
    public static final double DEFAULT_MAGNITUDE_BITS = 1.0;

    private SurpriseScore() {
    }

    /**
     * The rarity component: {@code log2(meanGapDays)}.
     *
     * @param meanGapDays the mean gap between occurrences, in days; must be positive
     * @return the rarity in bits
     * @throws IllegalArgumentException if {@code meanGapDays} is not positive
     */
    public static double rarity(double meanGapDays) {
        if (meanGapDays <= 0) {
            throw new IllegalArgumentException("meanGapDays must be positive, got " + meanGapDays);
        }
        return Math.log(meanGapDays) / Math.log(2);
    }

    /**
     * The magnitude component and whether it was trusted as an exact percentile or bucketed
     * against a cold-start sample (plan D4).
     *
     * @param bits      the magnitude in bits — a fixed reference value under cold start, else
     *                  {@code -log2(P(X >= x))}
     * @param coldStart true when the supporting history had fewer than
     *                  {@link ComingUpScoringProperties.Magnitude#getColdStartMinObservations()}
     *                  observations (or none at all) — the caller must not badge from this value
     */
    public record MagnitudeResult(double bits, boolean coldStart) {
    }

    /**
     * The magnitude component from a topic's own historical intensity distribution.
     *
     * <p>Cold start (plan D4) means "assume high rarity, list it, do not badge": below
     * {@link ComingUpScoringProperties.Magnitude#getColdStartMinObservations()} stored
     * observations, the value is snapped to one of four fixed reference points (README §3) rather
     * than trusted as an exact percentile a thin sample cannot support.
     *
     * @param history the topic's own past intensities, any order, non-null entries; empty or null
     *                is treated as no distribution at all
     * @param value   this occurrence's own intensity, on the same scale as {@code history}
     * @param config  the magnitude knobs
     * @return the magnitude and whether it was cold-start bucketed
     */
    public static MagnitudeResult magnitudeFromHistory(List<Double> history, double value,
            ComingUpScoringProperties.Magnitude config) {
        if (history == null || history.isEmpty()) {
            return new MagnitudeResult(DEFAULT_MAGNITUDE_BITS, true);
        }
        int n = history.size();
        long countGe = history.stream().filter(h -> h != null && h >= value).count();
        double rank = (double) countGe / n;

        if (n < config.getColdStartMinObservations()) {
            return new MagnitudeResult(bucketedMagnitude(rank, config), true);
        }
        // Laplace (add-one) smoothing: an occurrence bigger than everything on record would
        // otherwise divide by zero and claim infinite bits from a finite sample.
        double smoothedP = Math.max(countGe, 1.0) / (n + 1.0);
        return new MagnitudeResult(-Math.log(smoothedP) / Math.log(2), false);
    }

    private static double bucketedMagnitude(double rank, ComingUpScoringProperties.Magnitude config) {
        if (rank <= config.getP97Rank()) {
            return config.getP97Bits();
        }
        if (rank <= config.getP95Rank()) {
            return config.getP95Bits();
        }
        if (rank <= config.getP90Rank()) {
            return config.getP90Bits();
        }
        return config.getMedianBits();
    }

    /**
     * The delivery band a score falls in, lower-inclusive (README §3: {@code >=} comparisons).
     *
     * @param bits  the total surprise score
     * @param bands the band edges
     * @return {@code "interrupt"}, {@code "announce"}, {@code "list"}, or {@code "onRequest"}
     */
    public static String bandOf(double bits, ComingUpBands bands) {
        if (bits >= bands.interrupt()) {
            return "interrupt";
        }
        if (bits >= bands.announce()) {
            return "announce";
        }
        if (bits >= bands.list()) {
            return "list";
        }
        return "onRequest";
    }

    /**
     * A plain-language phrase for how often something happens — the rarity component read back
     * out in the mean gap it was built from, never the raw log2 unit (lunar-eclipse plan §2.9: "no
     * more bits" applies to display copy, not to the wire, so {@code rarityBits} itself is
     * unaffected). {@link #rarity} is exactly {@code log2(meanGapDays)}, so {@code days =
     * 2^bits} recovers it losslessly; this is the one place that inverse is taken.
     *
     * <p>The buckets are natural-language calendar units, chosen so every mean-gap-days value
     * actually configured in {@code application-example.yml} lands on the word that describes it
     * — a spring tide's 14.8-day gap reads "a fortnight", a meteor shower's or an NLC season's
     * 365.25-day gap reads "a year", a lunar eclipse's 900-day gap reads "every two to three
     * years", a solar eclipse's 1500-day gap (4.1 years — outside that band) reads "every 4
     * years" — rather than tuned to hit only the four phrases the design bundle happens to name.
     *
     * <p>The returned phrase completes "once ___": {@code "It comes round about once " +
     * gapWord(bits) + ", which is rare enough to flag on its own."}
     *
     * <p>⚠️ <b>The phrase ships alone — never {@code phrase (3.9)}.</b> Every quant line that
     * calls this used to append the raw bits figure in brackets, which put back exactly the log2
     * unit this method exists to replace: {@code bits} is unbounded surprisal, so a bracketed
     * {@code 3.9} offers a reader no denominator to reason about — there is no "out of". The
     * number stays on the wire where a consumer can use it ({@code
     * ComingUpConditionPeak.bits}/{@code ComingUpConditionOccurrence.bits}/{@code
     * ComingUpEntry.bits}); it simply does not belong in a sentence a photographer reads.
     *
     * @param bits the rarity component, in bits ({@code log2(meanGapDays)})
     * @return a phrase such as {@code "a fortnight"}, {@code "a year"} or {@code "every 4 years"}
     */
    public static String gapWord(double bits) {
        return gapWordFromDays(Math.pow(2, bits));
    }

    private static String gapWordFromDays(double meanGapDays) {
        if (meanGapDays <= 1.5) {
            return "a day";
        }
        if (meanGapDays <= 10.0) {
            return "a week";
        }
        if (meanGapDays <= 25.0) {
            return "a fortnight";
        }
        if (meanGapDays <= 45.0) {
            return "a month";
        }
        if (meanGapDays <= 100.0) {
            return "a couple of months";
        }
        if (meanGapDays <= 274.0) {
            return "six months";
        }
        if (meanGapDays <= 548.0) {
            return "a year";
        }
        if (meanGapDays <= 1200.0) {
            return "every two to three years";
        }
        long years = Math.round(meanGapDays / 365.25);
        return "every " + years + " years";
    }
}
