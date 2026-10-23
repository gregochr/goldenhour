import { heatSpotKey } from './heatSpots.js';
import { keyOf } from './mapVerdict.js';
import { TIDE_STAND_DOWN_REASON, standDownCauseWord } from './standDown.js';

/**
 * The Map tab's stand-down ground (big-seas plan F3): which stood places are hatched, which
 * regions earn a "stood down" label, and the footer's count. Filters, joins and a word lookup over
 * served rows: nothing is rated or averaged here, and the label can never contradict a served
 * rating (it is refused whenever the window has a rated place or a finite served mean).
 *
 * <p>The three licensed members are (g) the footer count, (h) the region qualification and (i) the
 * cause word, in {@code docs/engineering/reference/architecture-decisions.md}, "Backend-heavy".
 */

/**
 * The stood places the hatch is drawn around: the window's stand-downs minus the tide ones
 * ({@code TIDE_MISALIGNED} is not "ruled out by the weather", owner Q2).
 *
 * @param {?Array<{reason: ?string}>} stood the window's {@code stood} list ({@code stoodPointsFor})
 * @returns {Array<object>} the weather stand-downs
 */
export function weatherStoodOf(stood) {
  return (Array.isArray(stood) ? stood : []).filter((p) => p.reason !== TIDE_STAND_DOWN_REASON);
}

/**
 * Whether a window holds any evidence at all: at least one {@code rated} or {@code stood} place
 * (the {@code unratedPointsFor} test). The pane's own guard against a pending or failed scores
 * response is withholding a served window's states until {@code scoresLoaded} (then this sees no
 * entry at all); this is the second line, for a response that loaded but named no place either way.
 *
 * <p>An entry whose states are all unrated or not applicable yields nothing, so a stood list can
 * never draw on a window that is not evidenced.
 *
 * @param {?Map<string, string>} states the window's place states
 * @returns {boolean} true when some place is rated or stood
 */
export function windowHasEvidence(states) {
  if (!(states instanceof Map)) return false;
  for (const state of states.values()) {
    if (state === 'rated' || state === 'stood') return true;
  }
  return false;
}

/**
 * The regions that earn a "stood down · cause" label. A region qualifies when, over the window's
 * places (already narrowed only by the dark-sky pool and the door lens), ALL hold:
 * <ul>
 *   <li>it has at least one weather-stood place;</li>
 *   <li>it has no rated place;</li>
 *   <li>for a served window, the served region carries no finite {@code meanRating}. The index
 *   holds eligible regions only, so an absent entry counts as no mean.</li>
 * </ul>
 *
 * @param {object} args
 * @param {Array<{reason: ?string, rid: string, lat: number, lng: number}>} args.stood the
 *        weather-stood places, narrowed
 * @param {Array<{id: *, name: ?string, rid: string}>} args.spots the window's narrowed spot pool
 * @param {?Map<string, string>} args.states the window's place states
 * @param {boolean} args.served true for a served window, false for a past one (no served mean)
 * @param {?Map<string, object>} args.regionVerdictIndex the served per-region records
 * @param {string} args.date the window's date
 * @param {string} args.targetType the window's event
 * @returns {Array<{rid: string, cause: string, points: Array<{lat: number, lng: number, rid: string}>}>}
 */
export function standDownRegions({
  stood, spots, states, served, regionVerdictIndex, date, targetType,
}) {
  const byRegion = new Map();
  for (const p of Array.isArray(stood) ? stood : []) {
    if (!p.rid) continue;
    if (!byRegion.has(p.rid)) byRegion.set(p.rid, []);
    byRegion.get(p.rid).push(p);
  }
  if (byRegion.size === 0) return [];

  const ratedRegions = new Set();
  for (const spot of Array.isArray(spots) ? spots : []) {
    if (states instanceof Map && states.get(heatSpotKey(spot)) === 'rated') ratedRegions.add(spot.rid);
  }

  const out = [];
  for (const [rid, points] of byRegion) {
    if (ratedRegions.has(rid)) continue;
    if (served) {
      const mean = regionVerdictIndex?.get(keyOf(date, targetType, rid))?.meanRating;
      if (typeof mean === 'number' && Number.isFinite(mean)) continue;
    }
    out.push({ rid, cause: standDownCauseWord(points.map((p) => p.reason)), points });
  }
  return out;
}

/**
 * The footer's stood-down count: the scope pool's places the window stood down for the weather,
 * joined by {@link heatSpotKey}. The footer's own population, with no Heat/Pins split.
 *
 * @param {Array<{id: *, name: ?string}>} scopePool {@code scopeBasePool}
 * @param {Array<{key: string, reason: ?string}>} stood the window's full {@code stood} list
 * @returns {number} how many of the pool stood down for the weather
 */
export function standDownFooterCount(scopePool, stood) {
  const keys = new Set(weatherStoodOf(stood).map((p) => p.key));
  if (keys.size === 0) return 0;
  let n = 0;
  for (const spot of Array.isArray(scopePool) ? scopePool : []) {
    if (keys.has(heatSpotKey(spot))) n += 1;
  }
  return n;
}
