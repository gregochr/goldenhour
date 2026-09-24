package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;

/**
 * The one place a lunar eclipse's umbral magnitude becomes words, shared by
 * {@link LunarEclipseHotTopicStrategy} and {@link LunarEclipseAlmanacSource} so the two surfaces
 * cannot independently mis-render it.
 *
 * <h2>The bug this class exists to make impossible</h2>
 *
 * <p>Codex review of PR #913 found two real defects, both from treating {@code umbralMagnitude()}
 * as a bounded percentage. First: a total eclipse's magnitude is the fraction of the Moon's
 * <em>diameter</em> inside the umbra, which runs past {@code 1.0} once the Moon is fully swallowed
 * (2028-12-31 is 1.24785, 2029-06-26 is 1.8452) — an un-capped {@code round(magnitude * 100)}
 * therefore printed impossible copy like "125% in shadow" and "185% in shadow" across the Plan
 * detail line, the fact chip and the Coming-up card, since {@code LunarEclipseAlmanacSource}
 * independently re-ran the same arithmetic and propagated the same impossible figure into the
 * 90-day feed. Second: {@link LunarEclipseAlmanacSource}'s Coming-up "why" paragraph hard-coded the
 * design's deep-partial copy ("Earth's shadow covers all but a sliver…") for every entry, which is
 * false for the 2028-01-12 eclipse — magnitude 0.0679, roughly 7% of the Moon's diameter — where
 * "all but a sliver" claims the opposite of what actually happens.
 *
 * <p>Both were the same root cause: two classes independently deriving copy from one number, with
 * no shared place that could enforce "never print an impossible figure" or "never apply deep-partial
 * wording to a shallow eclipse". This class is that shared place — both callers route every
 * magnitude-derived word through it, so a future divergence would have to be introduced in a single
 * function both surfaces already call, not reintroduced independently in each.
 */
final class LunarEclipseWording {

    /** How deep an eclipse reads, coarsened into the four bands the copy branches on. */
    enum Depth {
        TOTAL,
        DEEP,
        PARTIAL,
        SLIGHT
    }

    /** Magnitude at or above which an eclipse is TOTAL — the whole disc is inside the umbra. */
    private static final double TOTAL_MAGNITUDE = 1.0;

    /** Magnitude at or above which a partial eclipse reads DEEP rather than PARTIAL. */
    private static final double DEEP_MAGNITUDE = 0.80;

    /** Magnitude at or above which a partial eclipse reads PARTIAL rather than SLIGHT. */
    private static final double PARTIAL_MAGNITUDE = 0.40;

    private LunarEclipseWording() {
    }

    /**
     * The depth band this eclipse's umbral magnitude falls into.
     *
     * @param eclipse the catalogued eclipse
     * @return TOTAL, DEEP, PARTIAL or SLIGHT
     */
    static Depth depthOf(LunarEclipse eclipse) {
        double magnitude = eclipse.umbralMagnitude();
        if (magnitude >= TOTAL_MAGNITUDE) {
            return Depth.TOTAL;
        }
        if (magnitude >= DEEP_MAGNITUDE) {
            return Depth.DEEP;
        }
        if (magnitude >= PARTIAL_MAGNITUDE) {
            return Depth.PARTIAL;
        }
        return Depth.SLIGHT;
    }

    /**
     * The umbral magnitude as a whole-number percentage, capped at 100 — a total eclipse's own
     * magnitude runs past {@code 1.0} (see the class javadoc), so an un-capped figure would print
     * an impossible "125% in shadow". Every caller in this codebase reads {@code "total"} instead
     * of a percentage for a TOTAL eclipse ({@link #coverageWord}, {@link #shadowClause}), so this
     * cap is a defensive backstop against a future caller that forgets to branch on
     * {@link #depthOf} first, not a value any current template actually prints.
     *
     * @param eclipse the catalogued eclipse
     * @return the coverage percentage, 0–100
     */
    static int coveragePct(LunarEclipse eclipse) {
        return Math.min(100, (int) Math.round(eclipse.umbralMagnitude() * 100));
    }

    /**
     * The short headline word for a fact chip or a Coming-up metric: {@code "total"} for a TOTAL
     * eclipse (never a percentage — there is nothing left uncovered to measure), otherwise
     * {@code "N%"} with the capped figure.
     *
     * @param eclipse the catalogued eclipse
     * @return {@code "total"} or {@code "N%"}
     */
    static String coverageWord(LunarEclipse eclipse) {
        return depthOf(eclipse) == Depth.TOTAL ? "total" : coveragePct(eclipse) + "%";
    }

    /**
     * The band-specific opening sentence shared by the topic's (i) tooltip and the Coming-up "why"
     * paragraph — the one place both surfaces state how much of the Moon is in shadow, so neither
     * can independently apply deep-partial copy ("all but a sliver") to a barely-visible eclipse,
     * or total-eclipse copy to a partial one.
     *
     * @param eclipse the catalogued eclipse
     * @return one complete sentence, band-specific, with the real figure substituted where the
     *     band needs one
     */
    static String shadowClause(LunarEclipse eclipse) {
        return switch (depthOf(eclipse)) {
            case TOTAL -> "Earth's shadow covers the whole moon, and the shadowed disc turns copper.";
            case DEEP -> "Earth's shadow covers all but a sliver of the full moon, and the shadowed"
                    + " part turns copper.";
            case PARTIAL -> "Earth's shadow covers about " + coveragePct(eclipse)
                    + "% of the moon's diameter, and the shadowed part turns copper.";
            case SLIGHT -> "Earth's shadow clips " + coveragePct(eclipse)
                    + "% of the moon's edge — a darkened bite rather than a copper disc.";
        };
    }
}
