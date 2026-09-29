package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;

import java.util.List;

/**
 * Gates a region's rated {@code displayVerdict}, pick eligibility and ranking on whether its
 * Claude ratings are a large enough sample to mean anything — over the region's <b>voting</b>
 * roster ({@link BriefingSlot#votingSlots}, non-canopy with the existing all-canopy fallback).
 *
 * <p><b>Why this exists.</b> Before this gate, {@code BriefingRatingStats.resolveRegionDisplayVerdict}
 * fell back to the triage verdict only when NOTHING was rated — one rated location, however it got
 * that rating, was enough to set a fifty-location region's verdict, crown it a BEST BET, and outrank
 * a region every one of whose locations was actually scored. Production evidence, 2026-09-29: for
 * Fri 2 Oct sunrise the whole 253-location catalogue held six ratings, all 4★, all force-evaluated
 * far-horizon headline candidates ({@code ForceEvalHeadlineSelector}) — and that window read
 * "Worth it" and took ALSO GOOD. For Thu 1 Oct sunrise it held 12 of 253. The owner's words: a
 * single good rating "isn't really in the spirit" of the app — a good sunrise needs a statistically
 * significant number of 3/4/5★ forecasts, not a blip.
 *
 * <p><b>The two thresholds, and why they are named constants rather than derived.</b>
 * <ul>
 *   <li>{@link #MIN_RATED} (5) — below this a handful of force-evaluated headline candidates could
 *       crown a fifty-location region on their own, which is exactly what happened above.</li>
 *   <li>{@link #MIN_EXAMINED_COVERAGE} (0.5, "half the voting roster examined") — deliberately
 *       mirrors {@code ConfidenceDeriver.THIN_COVERAGE_RATIO}'s thin-coverage half and the Plan
 *       card's client-side spread gate ({@code frontend/src/utils/windowFirstSpread.js}'s
 *       {@code SPREAD_MIN_RATED_COUNT}/{@code SPREAD_MIN_RATED_COVERAGE}, on branch
 *       {@code feature/spread-minimum-sample}, not in this tree). <b>The three are separate rules
 *       over three different populations, and they agree on "half" by decision, not by shared
 *       code:</b> this gate over the region's <em>voting</em> roster, {@code ConfidenceDeriver}
 *       over the region's <em>scoreable</em> roster, the frontend gate over the reader's own
 *       <em>reach pool</em>. Do not factor "half" into one shared constant — the day one of the
 *       three is retuned, sharing would retune the other two by accident.</li>
 * </ul>
 *
 * <p><b>{@link #examinedCount}, not the rated count alone, is the coverage half's numerator.</b> A
 * voting slot the pipeline triaged (a resolved weather stand-down — {@code Verdict.STANDDOWN}, the
 * pre-Claude weather triage that skips the Claude call entirely to save cost, distinct from
 * {@code BriefingGatingPolicy}'s now-empty hard-constraint gate) is evidence the sky there is poor;
 * an unrated, un-triaged slot — beyond Gate 4's horizon, or a stability-gated far cell nobody forced
 * — is no evidence at all. On a poor-weather near window most of a region is triaged and only the
 * viable remainder is rated: that region HAS been looked at, and its ratings keep deciding its
 * verdict exactly as before this gate existed. The gate bites only where most of the region was
 * never examined — the far-horizon, stability-gated case {@code ForceEvalHeadlineSelector} was
 * built to rescue six cells at a time.
 *
 * <p><b>The force-evaluation exemption is not this class's concern.</b> A region with at least one
 * currently force-evaluated rated voting slot bypasses this gate outright — see
 * {@code BriefingRegion#forcedSample} and {@code BriefingRegionEvaluationRollup}, which combine
 * this gate's raw answer with that separate signal. This class answers only the raw sample-size
 * question.
 */
public final class VerdictSampleGate {

    /**
     * Minimum rated voting slots a region needs before its ratings may set its own verdict.
     *
     * <p>Chosen against the production evidence in the class javadoc: six force-evaluated ratings
     * in a 253-location catalogue crowned a window, so the floor has to exceed the number of
     * headline candidates {@code ForceEvalHeadlineSelector}'s {@code forceEvalCap} can produce in
     * one cycle for it to matter on its own (the force-evaluation exemption handles that case
     * directly instead — see the class javadoc).
     */
    public static final int MIN_RATED = 5;

    /**
     * Fraction of the voting roster that must be <em>examined</em> (rated or triaged — see
     * {@link #examinedCount}) before a region's ratings may set its own verdict.
     */
    public static final double MIN_EXAMINED_COVERAGE = 0.5;

    private VerdictSampleGate() {
    }

    /**
     * Whether a region's rated voting sample is large enough to set its own {@code displayVerdict}
     * and qualify as a pick, ignoring the separate force-evaluation exemption.
     *
     * @param rated        count of voting slots carrying a usable Claude rating — the same count
     *                     {@code BriefingRatingStats.Stats#count()} reports over the voting entries
     * @param examined     count of voting slots that are rated OR carry a resolved weather
     *                     stand-down — see {@link #examinedCount}
     * @param votingRoster size of the region's voting roster — {@code
     *                     ConfidenceDeriver.RegionRoster#voting()}
     * @return {@code true} when both {@link #MIN_RATED} and {@link #MIN_EXAMINED_COVERAGE} clear
     */
    public static boolean isSufficient(int rated, int examined, int votingRoster) {
        return rated >= MIN_RATED && examined >= votingRoster * MIN_EXAMINED_COVERAGE;
    }

    /**
     * Counts how many of the given voting slots the pipeline has actually <b>examined</b> — rated
     * by Claude, or triaged (a resolved weather stand-down, {@code Verdict.STANDDOWN} with no
     * rating). An unrated, un-triaged slot has never been looked at for this cycle's horizon and
     * contributes nothing.
     *
     * <p>A triaged and a rated outcome are mutually exclusive for a voting (non-canopy) slot in the
     * nightly batch: {@code ForecastTaskCollector} skips a triaged candidate before it ever reaches
     * Claude or the Gate 4 eligibility check, so a slot cannot be both. The two counts are therefore
     * summed rather than unioned, which is equivalent here but cheaper to read.
     *
     * @param votingSlots the region's voting slots ({@link BriefingSlot#votingSlots})
     * @param ratedCount  how many of them carry a usable rating — passed in rather than
     *                    recomputed, so this method agrees by construction with whatever count the
     *                    caller's own {@code BriefingRatingStats.Stats} already validated
     * @return the examined count, never greater than {@code votingSlots.size()}
     */
    public static int examinedCount(List<BriefingSlot> votingSlots, int ratedCount) {
        if (votingSlots == null || votingSlots.isEmpty()) {
            return ratedCount;
        }
        long triaged = votingSlots.stream()
                .filter(s -> s.claudeRating() == null && s.verdict() == Verdict.STANDDOWN)
                .count();
        return ratedCount + (int) triaged;
    }
}
