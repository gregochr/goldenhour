import { heatSpotKey } from './heatSpots.js';
import { hasSpreadSample } from './windowFirstSpread.js';
import { isGatedIn } from './pickScope.js';

/**
 * The Map tab's thin-sample count — the pill's `· N rated` (thin-sample plan F4, §1 #3–#4).
 *
 * <p>⚠️ <b>Its own population, not the Plan card's.</b> The Plan card counts its REACH pool; the Map
 * tab has no reach pool, its verdict follows the SCOPE segment ({@code utils/mapVerdict.js}), so
 * this counts the rated SKY places in that scope pool. The two surfaces may print different numbers
 * for one window; each names its own population. A wood or a wildlife hide ({@code skySubject}
 * false) is never in the pool, rated or not.
 *
 * <p>Shown only when ALL hold: the scope pool fails the Plan spread's own two floors
 * ({@link hasSpreadSample}, imported, not copied), the pill's verdict is a rated word (never
 * {@code UNRATED}, whose copy already says "Too few rated", nor a stand-down or awaiting window),
 * and at least one place in the pool is rated (no "0 rated" beside a verdict resting on places
 * further away). A filter/map/select over the states the heat spots already carry, no rating
 * derived: CLAUDE.md's Backend-heavy bullet, sixth class, member (f).
 *
 * <p>A place the server marked {@code gated} (outside a Lite reader's sub-regions) is in NEITHER half
 * of the count: not rated, and not part of the total either, so a reader holding a few picks is told
 * "2 rated" of their own places and never "2 of 260".
 */

/** Verdict tiers that never wear the count: they are not a rated word. */
const NOT_A_RATED_WORD = new Set(['UNRATED', 'STAND_DOWN', 'AWAITING']);

/**
 * One window's count, or null when the pill says nothing about it.
 *
 * @param {object} args
 * @param {?{tier: string}} args.verdict the pill's verdict for the window
 * @param {?Map<string, string>} args.states the window's place states by {@code heatSpotKey}
 * @param {Array<{id: *, name: ?string, skySubject: boolean}>} args.scopePool the scope pool's spots
 * @returns {?number} rated sky places in scope, or null
 */
export function thinRatedCount({ verdict, states, scopePool }) {
  if (!verdict?.tier || NOT_A_RATED_WORD.has(verdict.tier)) return null;
  if (!(states instanceof Map)) return null;
  let total = 0;
  let rated = 0;
  for (const spot of Array.isArray(scopePool) ? scopePool : []) {
    if (!spot?.skySubject) continue;
    if (isGatedIn(states, spot)) continue;
    total += 1;
    if (states.get(heatSpotKey(spot)) === 'rated') rated += 1;
  }
  if (rated < 1 || hasSpreadSample({ rated, total })) return null;
  return rated;
}

/**
 * The count for every served solar row that has one, keyed by row id.
 *
 * @param {object} args
 * @param {Array<{id: string, kind: string, date: string, eventType: string}>} args.events the EV list
 * @param {Map<string, object>} args.verdicts row id to verdict ({@code buildEvVerdicts})
 * @param {?Map<string, {states: Map<string, string>}>} args.windowStates window key to its states
 * @param {Array<object>} args.scopePool the scope pool's spots
 * @returns {Map<string, number>} row id to rated count, omitting rows that show none
 */
export function buildThinCounts({
  events, verdicts, windowStates, scopePool,
}) {
  const out = new Map();
  if (!(windowStates instanceof Map)) return out;
  for (const row of Array.isArray(events) ? events : []) {
    if (row?.kind !== 'solar' || row.served === false || row.past) continue;
    const count = thinRatedCount({
      verdict: verdicts?.get(row.id) ?? null,
      states: windowStates.get(`${row.date}:${row.eventType}`)?.states,
      scopePool,
    });
    if (count != null) out.set(row.id, count);
  }
  return out;
}
