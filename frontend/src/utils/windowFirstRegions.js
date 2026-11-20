/**
 * The one region comparator the Map's panel and jump menu share.
 *
 * <p>Every star a region row carries is SERVED, never a client-side maximum: {@code BriefingRegion.meanRating} and
 * {@code bestRating} arrive on the briefing payload and are copied through untouched, because {@code bestRating}'s
 * canopy fallback is a backend rule the client cannot reconstruct. Region names are joined byte-identically; nothing
 * here trims or normalises {@code regionName}, which is the heat field's focus key.
 */

/**
 * Rank two region rows: verdict-eligible first, then the served mean best first, with the NAME as
 * the tiebreak.
 *
 * <p><b>Eligible-first is the projector's order, not a preference.</b> {@code PlanWindowProjector}
 * ranks {@code verdictEligible()} regions ahead of every ineligible one before it compares means,
 * and the card's top region ({@code pickTopEligibleRegion}) follows it. A rail that ranked on the
 * mean alone listed a thin 4.4 above the sound 3.5 the card in the same window named (review F2).
 * A row counts as eligible only when it also carries a finite mean, because the card's argmax skips
 * a mean-less region outright whereas this ranks it last — an unrated "eligible" row must not jump
 * the rated ones.
 *
 * <p>The tiebreak makes the order TOTAL — without it two regions on the same mean can swap places
 * between renders, which reads as the rail flickering for no reason. A region with no mean ranks
 * <b>last</b> rather than as a zero: "not scored" and "scored badly" are different statements, and
 * it is the same rule {@code windowFirstCards.js} applies to windows.
 *
 * <p>The Map tab's window panel and its Regions jump menu rank their region rows with THIS comparator over a
 * scope-narrowed set. The sorts share it and its eligibility test ({@code verdictEligible}, one export of
 * {@code windowFirstCards.js}); the card's argmax carries the same three-step rule in
 * {@code outranks} and differs only in skipping a mean-less region rather than ranking it last, and
 * in reading {@code regionName} where a row reads {@code name}.
 *
 * @param {{meanRating: ?number, name: string, verdictEligible: ?boolean}} a
 * @param {{meanRating: ?number, name: string, verdictEligible: ?boolean}} b
 * @returns {number}
 */
export function byMeanThenName(a, b) {
  const ma = a.meanRating ?? -Infinity;
  const mb = b.meanRating ?? -Infinity;
  const ea = a.verdictEligible === true && a.meanRating != null;
  const eb = b.verdictEligible === true && b.meanRating != null;
  if (ea !== eb) return ea ? -1 : 1;
  return mb === ma ? a.name.localeCompare(b.name) : mb - ma;
}
