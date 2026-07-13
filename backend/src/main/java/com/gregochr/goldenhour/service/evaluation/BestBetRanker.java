package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.CandidateCoverage;
import com.gregochr.goldenhour.model.DiffersBy;
import com.gregochr.goldenhour.model.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies the coverage-aware ranking rules to validated best-bet picks: the headline coverage
 * floor, the zero-colour-evidence drop, and the relationship/differsBy recomputation that keeps
 * trailing picks coherent after a reorder.
 *
 * <p>Stateless — all methods are pure functions of their arguments. Extracted from
 * {@code BriefingBestBetAdvisor} so the ranking policy can be reasoned about and tested in
 * isolation. Logs under the {@link BriefingBestBetAdvisor} category so gate diagnostics stay
 * grouped with the advisor's own output.
 */
public final class BestBetRanker {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingBestBetAdvisor.class);

    /**
     * Minimum number of Claude-evaluated locations a region must have to hold the
     * headline (rank 1) best bet when a better-covered alternative is available.
     *
     * <p>This is the coverage floor that prevents the "crowned on cheap thresholds"
     * inversion: a region with many triage GO verdicts but only a couple of actual
     * Claude evaluations cannot outrank a nearer, better-evaluated region purely on
     * GO count + peak rating. Calibrated against the observed Northumberland case
     * (2 Claude-rated, demote) versus the North Yorkshire Coast alternative
     * (5 Claude-rated, keep): the floor sits between those two so the well-evidenced
     * pick wins the headline.
     *
     * <p>The gate is comparative — it only demotes a thin-coverage headline when a
     * pick meeting this floor exists to crown instead. When no covered alternative
     * is available the picks are left as Claude ranked them (thin coverage is then
     * the best evidence we have; the {@code targeted force-evaluation} path in
     * {@code ForecastTaskCollector} is what guarantees headline contenders actually
     * reach this floor).
     */
    public static final int MIN_HEADLINE_CLAUDE_COVERAGE = 3;

    private BestBetRanker() {
    }

    /**
     * Builds the {@code event|region} key used to look up {@link CandidateCoverage}.
     *
     * @param event  event identifier (e.g. {@code "2026-03-30_sunset"})
     * @param region region name
     * @return the composite lookup key
     */
    public static String coverageKey(String event, String region) {
        return event + "|" + region;
    }

    /**
     * Enforces the headline coverage floor: a region cannot hold rank 1 on cheap
     * GO-count merit when only a couple of its locations were actually
     * Claude-evaluated and a better-covered alternative pick is available.
     *
     * <p>Extends the principle behind {@code BriefingHonestyFilter} (which rewrites
     * regions with <em>zero</em> Claude scores on the read path) to the
     * <em>insufficient</em>-coverage case at the crowning decision: the headline
     * must clear {@link #MIN_HEADLINE_CLAUDE_COVERAGE} when a pick that does clear
     * it exists. The gate is deliberately comparative — it demotes a thin headline
     * only by promoting a genuinely better-evidenced pick. When no pick clears the
     * floor the order is left untouched (thin coverage is then the best evidence
     * available; the targeted force-evaluation path is what raises headline
     * contenders above the floor in the first place).
     *
     * <p>Stay-home picks and aurora picks are exempt — a stay-home pick crowns
     * nothing and aurora has its own clear-sky gate in the prompt.
     *
     * <p>When a promotion happens the new headline's relationship/differsBy are
     * cleared (rank 1 carries neither) and the trailing picks' relationship fields
     * are recomputed relative to the new headline so they stay coherent.
     *
     * @param picks    validated picks in Claude's ranked order
     * @param coverage per-{@code event|region} Claude coverage from the rollup
     * @return the picks, possibly reordered so a covered pick holds the headline
     */
    public static List<BestBet> applyCoverageAwareRanking(List<BestBet> picks,
            Map<String, CandidateCoverage> coverage) {
        if (picks.size() < 2) {
            return picks;
        }
        BestBet head = picks.get(0);
        if (isHeadlineEligible(head, coverage)) {
            return picks;
        }
        // Only a genuinely better-EVIDENCED pick (clears the coverage floor) may
        // demote a thin headline. An exempt aurora/stay-home pick is allowed to
        // hold the headline if Claude crowned it, but is never used to displace
        // another pick — it carries no per-region Claude coverage of its own.
        BestBet replacement = null;
        for (BestBet p : picks) {
            if (p != head && ratedCount(p, coverage) >= MIN_HEADLINE_CLAUDE_COVERAGE) {
                replacement = p;
                break;
            }
        }
        if (replacement == null) {
            return picks;
        }
        LOG.info("Best-bet coverage gate: demoting thin-coverage headline '{}' (rated={}) "
                        + "in favour of better-evaluated '{}' (rated={})",
                head.region(), ratedCount(head, coverage),
                replacement.region(), ratedCount(replacement, coverage));
        List<BestBet> reordered = new ArrayList<>();
        reordered.add(replacement);
        for (BestBet p : picks) {
            if (p != replacement) {
                reordered.add(p);
            }
        }
        return rerankWithRecomputedRelationships(reordered);
    }

    /**
     * Returns {@code true} if a pick may hold the headline: stay-home and aurora
     * picks are exempt; every other pick must clear {@link #MIN_HEADLINE_CLAUDE_COVERAGE}.
     */
    private static boolean isHeadlineEligible(BestBet pick, Map<String, CandidateCoverage> coverage) {
        return isColourExempt(pick) || ratedCount(pick, coverage) >= MIN_HEADLINE_CLAUDE_COVERAGE;
    }

    /**
     * Whether a pick is exempt from the colour-evidence requirement. Stay-home picks crown nothing,
     * and aurora picks claim aurora visibility (with their own clear-sky gate in the prompt), not
     * sky colour — so neither needs a Claude colour rating behind it. Every other pick does.
     */
    private static boolean isColourExempt(BestBet pick) {
        if (pick.event() == null && pick.region() == null) {
            return true;
        }
        return pick.event() != null && pick.event().endsWith("_aurora");
    }

    /**
     * Drops picks with zero Claude colour coverage. A best bet's entire premise is Claude's colour
     * evaluation; a region/event with no colour rating at all — only a weather GO count — is not
     * evidence of a good sky, so recommending it (even hedged) is dishonest. Stay-home and aurora
     * picks are {@link #isColourExempt exempt}. Survivors are renumbered so the highest remaining
     * pick holds rank 1; an empty result signals "no colour-backed recommendation available", which
     * the caller maps to {@code SUCCESS_NO_PICKS} (an honest decline), never {@code FAILED}.
     *
     * @param picks    validated picks in ranked order
     * @param coverage per-{@code event|region} Claude coverage from the rollup
     * @return the picks that carry colour evidence, renumbered; possibly empty
     */
    public static List<BestBet> dropUnevaluatedPicks(List<BestBet> picks,
            Map<String, CandidateCoverage> coverage) {
        List<BestBet> kept = new ArrayList<>();
        for (BestBet p : picks) {
            if (isColourExempt(p) || ratedCount(p, coverage) > 0) {
                kept.add(p);
            } else {
                LOG.info("Best-bet: dropped zero-coverage pick region='{}' event='{}' — no colour "
                        + "evaluation behind it", p.region(), p.event());
            }
        }
        if (kept.isEmpty() || kept.size() == picks.size()) {
            return kept;
        }
        return rerankWithRecomputedRelationships(kept);
    }

    private static int ratedCount(BestBet pick, Map<String, CandidateCoverage> coverage) {
        if (pick.event() == null || pick.region() == null) {
            return 0;
        }
        CandidateCoverage c = coverage.get(coverageKey(pick.event(), pick.region()));
        return c == null ? 0 : c.claudeRatedCount();
    }

    /**
     * Re-ranks an already-ordered pick list, assigning rank 1..n. The head pick has
     * its relationship/differsBy cleared; trailing picks have those recomputed
     * relative to the head so a promotion does not leave stale relationships behind.
     */
    private static List<BestBet> rerankWithRecomputedRelationships(List<BestBet> ordered) {
        List<BestBet> result = new ArrayList<>();
        BestBet head = ordered.get(0);
        result.add(new BestBet(1, head.headline(), head.detail(), head.event(),
                head.region(), head.confidence(), head.nearestDriveMinutes(),
                head.dayName(), head.eventType(), head.eventTime(), null, List.of()));
        for (int i = 1; i < ordered.size(); i++) {
            BestBet p = ordered.get(i);
            Relationship rel = deriveRelationship(head, p);
            List<DiffersBy> diffs = rel == Relationship.DIFFERENT_SLOT
                    ? deriveDiffersBy(head, p) : List.of();
            result.add(new BestBet(i + 1, p.headline(), p.detail(), p.event(), p.region(),
                    p.confidence(), p.nearestDriveMinutes(), p.dayName(), p.eventType(),
                    p.eventTime(), rel, diffs));
        }
        return List.copyOf(result);
    }

    /**
     * Derives the relationship of a trailing pick to the headline: SAME_SLOT when
     * the date and event type match, DIFFERENT_SLOT otherwise (the default for
     * aurora/stay-home or unparseable events).
     */
    private static Relationship deriveRelationship(BestBet head, BestBet other) {
        String[] h = splitEvent(head.event());
        String[] o = splitEvent(other.event());
        if (h == null || o == null) {
            return Relationship.DIFFERENT_SLOT;
        }
        return h[0].equals(o[0]) && h[1].equals(o[1])
                ? Relationship.SAME_SLOT : Relationship.DIFFERENT_SLOT;
    }

    /**
     * Derives which dimensions a DIFFERENT_SLOT pick differs from the headline by.
     */
    private static List<DiffersBy> deriveDiffersBy(BestBet head, BestBet other) {
        List<DiffersBy> diffs = new ArrayList<>();
        String[] h = splitEvent(head.event());
        String[] o = splitEvent(other.event());
        if (h != null && o != null) {
            if (!h[0].equals(o[0])) {
                diffs.add(DiffersBy.DATE);
            }
            if (!h[1].equals(o[1])) {
                diffs.add(DiffersBy.EVENT);
            }
        }
        if (head.region() != null && !head.region().equals(other.region())) {
            diffs.add(DiffersBy.REGION);
        }
        return List.copyOf(diffs);
    }

    /**
     * Splits an event id {@code "YYYY-MM-DD_type"} into {@code [date, type]}, or
     * {@code null} when the id is null or not in that form (e.g. aurora/stay-home).
     */
    private static String[] splitEvent(String event) {
        if (event == null) {
            return null;
        }
        int idx = event.lastIndexOf('_');
        if (idx <= 0 || idx == event.length() - 1) {
            return null;
        }
        return new String[]{event.substring(0, idx), event.substring(idx + 1)};
    }
}
