package com.gregochr.goldenhour.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Accumulates the per-run results of a pass^k eval for one fixture and renders a report.
 *
 * <p>For a single fixture the harness runs the scorer N times against the same frozen input
 * and feeds each returned star rating to {@link #record(int)}. The report then answers two
 * questions:
 * <ol>
 *   <li><b>Did it pass?</b> — the pass count and pass rate (how many of N runs landed in band).</li>
 *   <li><b>Which way is it miscalibrated when it misses?</b> — the {@link MissDirection}
 *       breakdown: misses below the band (the scorer too cautious) versus above it
 *       (too generous). A strong fixture that misses DOWN means the prompt under-rates good
 *       conditions; a flat fixture that misses UP means it over-rates poor ones.</li>
 * </ol>
 *
 * <p>This class is deliberately pure (no Claude, no I/O) so the bucketing logic can be unit
 * tested with stubbed ratings.
 */
public final class PassRateReport {

    private static final double PERCENT = 100.0;

    private final String fixtureName;
    private final RatingBand band;
    private final List<Integer> ratings = new ArrayList<>();
    private int belowMisses;
    private int aboveMisses;
    private int passes;

    /**
     * Creates a report for one fixture.
     *
     * @param fixtureName the fixture's name (for the rendered header)
     * @param band        the expected rating band
     */
    public PassRateReport(String fixtureName, RatingBand band) {
        this.fixtureName = fixtureName;
        this.band = band;
    }

    /**
     * Records one run's star rating, bucketing it by direction relative to the band.
     *
     * @param rating the star rating returned by the scorer (1–5)
     */
    public void record(int rating) {
        ratings.add(rating);
        switch (band.classify(rating)) {
            case BELOW -> belowMisses++;
            case ABOVE -> aboveMisses++;
            case IN_BAND -> passes++;
            default -> throw new IllegalStateException("Unhandled direction");
        }
    }

    /**
     * @return the number of runs recorded so far
     */
    public int runs() {
        return ratings.size();
    }

    /**
     * @return the number of runs whose rating landed in band
     */
    public int passes() {
        return passes;
    }

    /**
     * @return the number of misses below the band (scorer too cautious)
     */
    public int belowMisses() {
        return belowMisses;
    }

    /**
     * @return the number of misses above the band (scorer too generous)
     */
    public int aboveMisses() {
        return aboveMisses;
    }

    /**
     * @return the pass rate in {@code [0.0, 1.0]}, or 0.0 if no runs were recorded
     */
    public double passRate() {
        return ratings.isEmpty() ? 0.0 : (double) passes / ratings.size();
    }

    /**
     * @return true if every recorded run landed in band (strict pass^k) and at least one ran
     */
    public boolean allPassed() {
        return !ratings.isEmpty() && belowMisses == 0 && aboveMisses == 0;
    }

    /**
     * Renders a multi-line, human-readable report of this fixture's pass^k outcome.
     *
     * @return the report text
     */
    public String render() {
        return String.format(Locale.ROOT,
                "Fixture: %s  band=%s%n"
                        + "  runs=%d  passes=%d  passRate=%.1f%%%n"
                        + "  misses: %d DOWN (too cautious), %d UP (too generous)%n"
                        + "  ratings: %s",
                fixtureName, band.label(),
                runs(), passes(), passRate() * PERCENT,
                belowMisses, aboveMisses,
                ratings);
    }
}
