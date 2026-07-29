/**
 * Which score source decides whether a slot reads as a stand-down.
 *
 * <p>Lives here rather than inside `MapView` because the marker and its own popup must answer the
 * question identically. They previously did not: the marker ORed the two sources while
 * `MarkerPopupContent` consulted only the forecast row, so a slot could render a 4★ medallion
 * whose popup said "Stand-down".
 */

/**
 * True when the *winning* score source says this slot was triaged.
 *
 * <p>The two sources genuinely disagree. `/api/briefing/evaluate/scores` prefers a cached
 * evaluation (`EvaluationViewService.mergeToView` step 1) while `/api/forecast` returns the latest
 * run per slot, so a location can hold a live cached rating alongside a superseded triaged
 * forecast row. Reading triage from both at once let the stale row veto the live rating — and on
 * the map that veto is checked before the star threshold, so no filter setting could recover the
 * marker.
 *
 * <p>The rule is symmetric: <b>a rating from either source beats a triageReason from the other</b>.
 * That makes it exactly the complement of the rating lookup — a slot is a stand-down precisely
 * when `briefingScore?.rating ?? forecast?.rating` would come back null and something triaged it.
 * The invariant is what keeps the marker, its popup and the map's quality filter from disagreeing;
 * an asymmetric rule would fix one direction of disagreement and leave the mirror image, which is
 * how the popup came to say "Stand-down" over a scored medallion.
 *
 * @param {object|null|undefined} briefingScore - briefing evaluation score for the slot.
 * @param {object|null|undefined} forecast - forecast row for the slot.
 * @returns {boolean} true when the slot should read as a stand-down.
 */
export function resolveStandDown(briefingScore, forecast) {
  if (briefingScore?.rating != null || forecast?.rating != null) return false;
  return briefingScore?.triageReason != null || forecast?.triageReason != null;
}
