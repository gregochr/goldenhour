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

    /**
     * The rank a pick must carry to BE the headline. Shared with {@code BriefingHonestyFilter},
     * which has no other reason to import this class, so that "which rank is the headline" is
     * answered in exactly one place.
     */
    public static final int RANK_TOP = 1;

    private BestBetRanker() {
    }

    /**
     * The one rule for what a caller must do after removing some picks from an already-ranked
     * list — shared by {@link #dropUnevaluatedPicks}, {@link #dropIneligiblePicks}, {@code
     * BestBetFallbackService}'s current-briefing eligibility re-check, and {@code
     * BriefingHonestyFilter}'s blanked-region withdrawal (round 11 — a Codex review of round 10's
     * fallback re-check, #943-adjacent, found the fallback promoting an orphaned rank 2 to rank 1
     * when rank 1 was the one dropped, and asked whether {@link #dropUnevaluatedPicks} — which
     * already called {@link #rerankWithRecomputedRelationships} the same way — had the identical
     * defect. It does).
     *
     * <p><b>The rule: losing rank 1 withdraws the WHOLE set; losing anything else keeps every
     * survivor's fields untouched (renumbering the {@code rank} field alone, never {@code
     * relationship}/{@code differsBy}, and never {@code headline}/{@code detail}/{@code
     * confidence}).</b> Rank 2's prose and its {@code relationship}/{@code differsBy} fields are
     * both authored — by Claude on the advisor path, or persisted verbatim on the fallback path —
     * describing how it differs from rank 1: the prompt (see {@code BestBetPromptText}'s ALSO GOOD
     * SELECTION RULE) explicitly instructs Claude to "make the temporal distinction obvious" and
     * phrase Tier-2 prose so "the reader knows immediately this is a different opportunity" — text
     * that reads correctly only beside the rank 1 it was written against. Promoting rank 2 into
     * rank 1's place after rank 1 is removed would present that relative prose ("A second strong
     * window later in the week", "Separate opportunity if skies hold") as the block's own headline,
     * with nothing to be second to or separate from — the same orphan defect {@code
     * BriefingHonestyFilter}'s own javadoc already named for the fallback case before round 11
     * generalised it here. There is no cheap fix for the prose itself (rewriting it needs another
     * Claude call), so withdrawal is the only honest outcome — matching the rule {@code
     * BriefingHonestyFilter} already used for exactly this situation.
     *
     * <p>When rank 1 survives, nothing about it changed, so a trailing survivor's {@code
     * relationship}/{@code differsBy} — computed relative to that same, unchanged rank 1 — remain
     * correct without recomputation; only the {@code rank} field is renumbered to close any gap, a
     * pure bookkeeping fix that touches no content a reader sees. In this codebase at most two
     * picks are ever produced (Pick 1 + Pick 2), so a gap can only arise if rank 1 is the one
     * removed — which this method already withdraws entirely — but the renumbering step is kept
     * general rather than assuming that ceiling holds forever.
     *
     * <p>{@link #rerankWithRecomputedRelationships} is deliberately NOT reused here: that method
     * promotes a DIFFERENT, already-present pick into rank 1 purely on ranking merit ({@link
     * #applyCoverageAwareRanking}'s coverage-floor demotion) — every candidate it operates on still
     * exists in the result, none was REMOVED, so recomputing relationship/differsBy for the new
     * arrangement is the right question to ask there. This method answers a different question —
     * what to do when a pick DISAPPEARS — and the honest answer for rank 1 disappearing is
     * withdrawal, never promotion. (The coverage-floor promotion carries a related, narrower risk of
     * its own — the promoted pick's prose was equally written as an "Also Good" relative to the
     * demoted headline — but that mechanism predates this fix, is independently calibrated against
     * an observed production case, and is out of scope for this round; flagged, not addressed, here.)
     *
     * @param original the picks before removal, in rank order
     * @param kept     the picks that survive removal, in the same relative order (built by
     *                 filtering {@code original}, never reordering it); may be empty
     * @return {@code kept}, renumbered, when rank 1 survived (or nothing was removed); an EMPTY
     *         list when rank 1 was among the removed picks, whatever else survived
     */
    public static List<BestBet> afterRemoval(List<BestBet> original, List<BestBet> kept) {
        if (kept.isEmpty() || kept.size() == original.size()) {
            return kept;
        }
        boolean originalHadHeadline = original.stream().anyMatch(b -> b.rank() == RANK_TOP);
        boolean headlineSurvived = kept.stream().anyMatch(b -> b.rank() == RANK_TOP);
        if (originalHadHeadline && !headlineSurvived) {
            return List.of();
        }
        return renumber(kept);
    }

    /**
     * Renumbers {@code rank} 1..n to close any gap left by a removal, touching no other field —
     * never {@code relationship}, {@code differsBy}, or any prose field. See {@link
     * #afterRemoval}'s javadoc for why recomputing those would be wrong here.
     */
    private static List<BestBet> renumber(List<BestBet> kept) {
        List<BestBet> result = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            BestBet p = kept.get(i);
            int newRank = i + 1;
            result.add(p.rank() == newRank ? p : new BestBet(newRank, p.headline(), p.detail(),
                    p.event(), p.region(), p.confidence(), p.nearestDriveMinutes(), p.dayName(),
                    p.eventType(), p.eventTime(), p.relationship(), p.differsBy()));
        }
        return List.copyOf(result);
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
     * picks are {@link #isColourExempt exempt}. Applies the shared {@link #afterRemoval} rule: if
     * rank 1 is the one dropped, the whole set withdraws rather than promoting rank 2's prose (round
     * 11 — see {@link #afterRemoval}'s javadoc); otherwise survivors are renumbered only. An empty
     * result signals "no colour-backed recommendation available", which the caller maps to {@code
     * SUCCESS_NO_PICKS} (an honest decline), never {@code FAILED}.
     *
     * @param picks    validated picks in ranked order
     * @param coverage per-{@code event|region} Claude coverage from the rollup
     * @return the picks that carry colour evidence (renumbered), or empty when rank 1 was dropped
     *         or nothing survived
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
        return afterRemoval(picks, kept);
    }

    /**
     * Drops picks naming a region the verdict-minimum-sample rule does not trust — insufficient
     * rating coverage and no force-evaluation exemption ({@link
     * com.gregochr.goldenhour.model.BriefingRegion#verdictEligible()}). {@link
     * #dropUnevaluatedPicks} already refuses a pick with <em>zero</em> Claude coverage; this closes
     * the gap a Codex review found in round 10 (P1-B) one rating short of zero — a region that
     * cleared {@code ratedCount() &gt; 0} but never reached the sample size (or examined-coverage
     * fraction) the Plan tab requires before it will show a verdict at all could still be named in
     * {@code DailyBriefingResponse.bestBets}, because the advisor never consulted the eligibility
     * flag {@code BriefingRegionEvaluationRollup} had already computed for it.
     *
     * <p>Deliberately a second, independent gate rather than a replacement for {@link
     * #dropUnevaluatedPicks}: the two ask different questions (does a rating exist at all, versus
     * is the sample behind it large enough to trust) and a region can fail either one without the
     * other. Stay-home and aurora picks are {@link #isColourExempt exempt}, matching every other
     * coverage rule in this class — neither carries a region-level sky sample to be insufficient.
     * A pick whose {@code event|region} key is absent from {@code coverage} is dropped: the rollup
     * only omits a key for a region it did not send to Claude at all, so there is no eligibility to
     * have found sufficient.
     *
     * <p>Applies the shared {@link #afterRemoval} rule, the same one {@link #dropUnevaluatedPicks}
     * uses (round 11 — the two must stay consistent, since a region can fail either gate and the
     * "what happens when a pick disappears" question is identical either way): if rank 1 is the one
     * dropped, the whole set withdraws — promoting rank 2 would present its prose, written to
     * describe how it differs from rank 1, as the headline it was never written to be — never merely
     * renumbering a promoted pick's relationship/differsBy as earlier rounds did. When only rank 2
     * is ineligible, rank 1 is kept untouched, still rank 1.
     *
     * @param picks    validated, colour-evidenced picks in ranked order
     * @param coverage per-{@code event|region} Claude coverage from the rollup, including each
     *                 region's {@link CandidateCoverage#verdictEligible()} at build time
     * @return the picks whose region is verdict-eligible (renumbered), or empty when rank 1 was
     *         ineligible or nothing survived
     */
    public static List<BestBet> dropIneligiblePicks(List<BestBet> picks,
            Map<String, CandidateCoverage> coverage) {
        List<BestBet> kept = new ArrayList<>();
        for (BestBet p : picks) {
            if (isColourExempt(p) || isVerdictEligible(p, coverage)) {
                kept.add(p);
            } else {
                LOG.info("Best-bet: dropped ineligible pick region='{}' event='{}' — sample too "
                        + "thin for a trusted verdict and no force-evaluation exemption",
                        p.region(), p.event());
            }
        }
        return afterRemoval(picks, kept);
    }

    private static boolean isVerdictEligible(BestBet pick, Map<String, CandidateCoverage> coverage) {
        if (pick.event() == null || pick.region() == null) {
            return false;
        }
        CandidateCoverage c = coverage.get(coverageKey(pick.event(), pick.region()));
        return c != null && c.verdictEligible();
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
