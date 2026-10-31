/**
 * Which score source decides whether a slot reads as a stand-down.
 *
 * <p>Lives here rather than inside `MapView` because the marker and its own popup must answer the
 * question identically. They previously did not: the marker ORed the two sources while
 * The since-deleted `MarkerPopupContent` consulted only the forecast row, so a slot could render a 4★ marker
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
 * how the popup came to say "Stand-down" over a scored marker.
 *
 * @param {object|null|undefined} briefingScore - briefing evaluation score for the slot.
 * @param {object|null|undefined} forecast - forecast row for the slot.
 * @returns {boolean} true when the slot should read as a stand-down.
 */
export function resolveStandDown(briefingScore, forecast) {
  if (briefingScore?.rating != null || forecast?.rating != null) return false;
  return briefingScore?.triageReason != null || forecast?.triageReason != null;
}

/**
 * The one word the Map names a stand-down's cause by (big-seas plan F3, owner Q2). Keyed by the
 * served `triageReason`; `TIDE_MISALIGNED` is deliberately absent, because a tide stand-down is not
 * "ruled out by the weather" and is never hatched.
 */
export const STAND_DOWN_CAUSE_WORD = Object.freeze({
  HIGH_CLOUD: 'cloud',
  PRECIPITATION: 'rain',
  LOW_VISIBILITY: 'visibility',
  GENERIC: 'weather',
});

/** The word for any mixed, unknown or missing cause. */
export const STAND_DOWN_MIXED_WORD = 'weather';

/** The one served reason the Map does not hatch: the tide, not the weather, ruled the place out. */
export const TIDE_STAND_DOWN_REASON = 'TIDE_MISALIGNED';

/**
 * The cause word for a set of stood places: their single shared reason's word, or {@code weather}
 * for any mix of two or more distinct reasons (owner, Q2: no majority vote). An unknown reason
 * also reads {@code weather}.
 *
 * <p>Licensed client derivation: member (i) of the Map scope class in
 * {@code docs/engineering/reference/architecture-decisions.md}.
 *
 * @param {Array<?string>} reasons the stood places' normalised reasons
 * @returns {string} {@code cloud}, {@code rain}, {@code visibility} or {@code weather}
 */
export function standDownCauseWord(reasons) {
  const distinct = new Set((Array.isArray(reasons) ? reasons : []).map((r) => r ?? 'GENERIC'));
  if (distinct.size !== 1) return STAND_DOWN_MIXED_WORD;
  const [only] = distinct;
  return STAND_DOWN_CAUSE_WORD[only] ?? STAND_DOWN_MIXED_WORD;
}

/**
 * The cause word for ONE stood place: its own reason's word, or null for {@code GENERIC}, a missing
 * or an unknown reason. Unlike {@link standDownCauseWord} there is no "weather" fallback: a Lite
 * reader's tide stand-down is served as {@code GENERIC} and must not be called weather.
 *
 * <p>Licensed client derivation: the stood place labels member of the Map scope class in
 * {@code docs/engineering/reference/architecture-decisions.md}; a lexical map over a served reason.
 *
 * @param {?string} reason the place's served {@code triageReason}
 * @returns {?string} {@code cloud}, {@code rain}, {@code visibility}, or null for no cause to name
 */
export function stoodPlaceCauseWord(reason) {
  if (reason == null || reason === 'GENERIC') return null;
  return STAND_DOWN_CAUSE_WORD[reason] ?? null;
}

/**
 * The accessible name of a stood place's map label: {@code "Tynemouth, stood down: poor visibility"},
 * {@code "…: cloud"}, {@code "…: rain"}, and just {@code "Tynemouth, stood down"} when the cause is
 * generic or unknown.
 *
 * @param {string} name the place's name
 * @param {?string} reason the place's served {@code triageReason}
 * @returns {string} the label's aria-label
 */
export function stoodPlaceAriaLabel(name, reason) {
  const word = stoodPlaceCauseWord(reason);
  if (word == null) return `${name}, stood down`;
  return `${name}, stood down: ${word === 'visibility' ? 'poor visibility' : word}`;
}
