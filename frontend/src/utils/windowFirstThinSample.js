import { hasSpreadSample } from './windowFirstSpread.js';

/**
 * The Plan card's thin-sample count — the verdict line's `· N rated` (thin-sample plan F3, §1 #4).
 *
 * <p>⚠️ <b>The card's REACH pool, and the spread row's own object.</b> {@code spread} is the very
 * {@code facts.spread} the Spread row draws from, so the row's `6/83 rated` and this `6 rated` are
 * one figure and the two can never disagree; the gate is {@link hasSpreadSample}, imported, not
 * copied. The Map pill's twin ({@code mapThinSample.js}) counts the SCOPE pool instead — each
 * surface names its own population.
 *
 * <p>Shown only when ALL hold: the spread gate FAILS, the verdict is a rated grade (the allow-list
 * below — never {@code UNRATED}, whose copy already says "Too few rated", nor a stand-down, an
 * awaiting window, an away cell or an unscored window) and at least one pool place is rated (no
 * "0 rated" beside a verdict resting on places further away). A read over served counts: the
 * already-licensed filter/map/select class, no rating derived.
 */

/** The rated verdict grades that may wear the count. An allow-list, so a new word shows nothing. */
const RATED_GRADES = new Set(['WORTH_IT', 'MAYBE']);

/**
 * One card's count, or null when the verdict line says nothing about it.
 *
 * @param {object} args
 * @param {?string} args.verdict the card's served {@code DisplayVerdict}
 * @param {boolean} [args.away] an away cell (no card behind it)
 * @param {boolean} [args.notScored] the window carries no rating anywhere
 * @param {?{rated: number, total: number}} args.spread the card's spread ({@code buildSpread})
 * @returns {?number} rated places in the reach pool, or null
 */
export function thinVerdictCount({
  verdict, away = false, notScored = false, spread,
}) {
  if (away || notScored || !RATED_GRADES.has(verdict)) return null;
  const rated = spread?.rated ?? 0;
  if (rated < 1 || hasSpreadSample(spread)) return null;
  return rated;
}

/**
 * The count in words for the card's spoken sentence ("6 places rated", "1 place rated").
 *
 * @param {number} count a count from {@link thinVerdictCount}
 * @returns {string} the spoken clause
 */
export function thinVerdictSpoken(count) {
  return `${count} ${count === 1 ? 'place' : 'places'} rated`;
}
