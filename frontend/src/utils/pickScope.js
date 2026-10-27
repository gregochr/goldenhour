import { heatSpotKey } from './heatSpots.js';

/**
 * The reader's sub-region picks, as the Map and the heat field see them (sub-regions-plan D6, S5a).
 *
 * <p>Two served facts and nothing else: the briefing's {@code allowedLocationIds} (an array only on a
 * SHAPED response, absent on every other), and each place's {@code gated} state, set from the score
 * row's own {@code gated: true} marker ({@code heatSpots.windowState}). <b>Nothing here decides
 * membership.</b> It never reads the role, and never tests an id against the allowed list — a place is
 * outside the picks only because the server said so.
 */

/** The heat state a place wears when the server marked its row {@code gated: true}. */
export const GATED_STATE = 'gated';

/**
 * Whether a briefing body was shaped by the reader's picks. An empty array is shaped (a reader with no
 * picks yet); {@code null}, {@code undefined} and anything that is not an array are not.
 *
 * @param {*} allowedLocationIds the briefing's served {@code allowedLocationIds}
 * @returns {boolean}
 */
export function isShaped(allowedLocationIds) {
  return Array.isArray(allowedLocationIds);
}

/**
 * Whether the server's served {@code allowedLocationIds} leaves a place outside the reader's sub-regions: a shaped
 * body whose list does not name it. A selection by a list the server decided, never a rule of the client's own. A
 * place with no id cannot be matched, so it is never claimed to be outside.
 *
 * @param {*} allowedLocationIds the briefing's served {@code allowedLocationIds}
 * @param {*} id the place's id
 * @returns {boolean}
 */
export function isOutsidePicks(allowedLocationIds, id) {
  return isShaped(allowedLocationIds) && id != null && !allowedLocationIds.includes(id);
}

/**
 * Whether a place's state in a window is {@code gated}.
 *
 * @param {?Map<string, string>} states a window's place states by {@code heatSpotKey}
 * @param {{id: *, name: ?string}} spot a spot, point or location
 * @returns {boolean}
 */
export function isGatedIn(states, spot) {
  return states instanceof Map && states.get(heatSpotKey(spot)) === GATED_STATE;
}

/**
 * The field's two grounds for one window: the places outside the picks (the veil's subject) and every
 * other place, rated or not (what the veil yields to and the outline traces).
 *
 * <p>Returns {@code null} when the reader is not shaped, or when the window holds no place states at all
 * (scores pending, failed or not applicable) — absence of evidence is not a claim that every place is
 * allowed, so nothing is drawn. A pool of only gated places yields an empty {@code allowed}: the
 * zero-picks reader, whose veil covers the land and who gets no outline.
 *
 * @param {object} args
 * @param {boolean} args.shaped whether the body is shaped ({@link isShaped})
 * @param {Array<{id: *, name: ?string, lat: number, lng: number, rid: string}>} args.spots the pool
 * @param {?Map<string, string>} args.states the window's place states
 * @returns {?{outside: Array<object>, allowed: Array<object>}}
 */
export function splitGround({ shaped, spots, states }) {
  if (!shaped || !(states instanceof Map) || states.size === 0) return null;
  const outside = [];
  const allowed = [];
  for (const spot of Array.isArray(spots) ? spots : []) {
    if (!spot || !states.has(heatSpotKey(spot))) continue;
    const point = {
      id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid,
    };
    (states.get(heatSpotKey(spot)) === GATED_STATE ? outside : allowed).push(point);
  }
  return { outside, allowed };
}

/**
 * Whether any place in a window's states is {@code rated}. The shaped body's stand-in for the window's
 * served {@code bestRating}, which the server strips from a Lite response (so it reads null whether or
 * not the reader's own places are rated): "is there colour to draw" is asked of the states instead.
 *
 * @param {?Map<string, string>} states a window's place states by {@code heatSpotKey}
 * @returns {boolean}
 */
export function hasRatedState(states) {
  if (!(states instanceof Map)) return false;
  for (const state of states.values()) {
    if (state === 'rated') return true;
  }
  return false;
}
