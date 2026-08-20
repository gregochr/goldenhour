import { areaSpots } from './planningArea.js';

/**
 * The origin — the frame of reference the whole Plan tab is written from (plan §4.8, D5).
 *
 * <p>Home is the default and was the only one through P6, which is why every phase before this
 * was written origin-agnostic. Moving the origin to a region is the design's headline state: it
 * decides <b>the pool</b> (which spots the page is about), <b>the frame</b> (what all six
 * thumbnails and the row maps are fitted to), <b>the drive figures</b> (and therefore the reach
 * gate, the far mark and the leave-by line), and <b>the lens label</b>. The prototype states it in
 * one line — "a region is Plan with the origin moved" — and this module is where that sentence is
 * made mechanical, so no surface can implement half of it.
 *
 * <h2>⚠️ The shared matrix and the per-user reach map must never mix</h2>
 *
 * <p>Two different questions produce two different numbers here, and they arrive over two
 * endpoints that are deliberately on opposite sides of the caching seam: {@code
 * GET /api/user/settings/reach} answers "how far is this from your house" and is never
 * ETag-revalidated (a revalidated body persists to a browser HTTP cache JavaScript cannot evict on
 * logout); {@code GET /api/regions/drive-times} answers "how far is this from Keswick", which is
 * the same for every reader, and rides the shared cacheable payload.
 *
 * <p>So {@link originReachMap} builds its map from the shared matrix <b>alone</b>. In particular it
 * does not borrow {@code distanceMiles} from the per-user map to fill the gap: those miles are
 * measured from the reader's home, and printing them beside an away drive time would put two
 * different journeys in one line — the single most damaging thing this feature could do, because
 * the leave-by line is derived from exactly these figures and a reader acts on a departure time
 * without checking it. Absent is the honest answer, and the spot card already renders a drive with
 * no distance.
 *
 * <h2>What the origin may filter, and what it may not</h2>
 *
 * <p>Plan §3's rule stands unchanged: the reach <em>lens</em> never filters the field. The origin
 * is a different thing and is allowed to choose the scope — that is what §4.8 specifies it for,
 * and it is the same licence {@code planningArea} already holds for framing. The distinction is
 * whose property the filter is about: how far you would drive belongs to the reader and may not
 * touch the field; <em>where you are planning from</em> is the question the reader just asked.
 */

/**
 * The reach tier an origin move selects — the design's "reach auto-drops to 1h 30".
 *
 * <p>An away base is somewhere you are staying, so the drives are short and the honest default is
 * tighter than the one derived for a day trip from home. It is a tier <em>id</em> rather than a
 * threshold because that is what {@code useReachLens.selectTier} takes, and the label is derived
 * from the threshold there — the one place it is allowed to be written down.
 */
export const AWAY_TIER_ID = '90';

/** That tier's threshold, for the away arm's far mark. Kept as the number the id names. */
export const AWAY_LIMIT_MINUTES = 90;

/**
 * Whether a region can be an origin at all.
 *
 * <p>A base is admin-entered and nullable: a region's centroid is frequently offshore, so every
 * drive time computed from it would measure a journey nobody can make, and the backend rejects a
 * partial base for the same reason. A baseless region is still <em>found</em> by search — it is
 * only "Plan from here" that is disabled, with a title saying why, because hiding it would make
 * the search look broken for a region the reader can plainly see on the map.
 *
 * @param {?object} region a region record from {@code GET /api/regions}
 * @returns {boolean} true when the region carries a complete base
 */
export function canBeOrigin(region) {
  return Boolean(region)
    && typeof region.baseName === 'string' && region.baseName.trim() !== ''
    && typeof region.baseLat === 'number' && Number.isFinite(region.baseLat)
    && typeof region.baseLon === 'number' && Number.isFinite(region.baseLon);
}

/**
 * The origin descriptor for a region, or null when it cannot be one.
 *
 * <p>Three fields and no more: the id keys the drive-time matrix, the name keys the spot pool
 * (regions join by name across the two payloads — see {@code heatSpots.js}), and the base name is
 * what the chip and the lens label print. Nothing here carries a rating, a count or a verdict:
 * those are all the forecast's answers and are read off the payload each render, so an origin can
 * never go stale against the forecast it frames.
 *
 * @param {?object} region a region record from {@code GET /api/regions}
 * @returns {?{id: *, name: string, baseName: string}} the origin, or null
 */
export function toOrigin(region) {
  if (!canBeOrigin(region)) return null;
  return { id: region.id, name: region.name, baseName: region.baseName.trim() };
}

/**
 * The reach map an away origin plans from — <b>built from the shared matrix alone</b>.
 *
 * <p>It is deliberately the same SHAPE as the per-user reach map ({@code Map<locationId,
 * {driveMinutes, distanceMiles}>}), so it can be handed to {@code buildWindowCards} in that map's
 * place and every downstream consumer — the drive chip, the reach gate, the far mark, the leave-by
 * line and the planning-area arithmetic — switches together. P5 recorded this as a requirement
 * rather than an option: {@code spot.driveMinutes} has exactly one producer, so the away figure
 * must <b>overwrite</b> it, not sit beside it, or the reach line and the leave line would describe
 * two different journeys with no test able to fail.
 *
 * <p>{@code distanceMiles} is always null — see the module comment for why it is not borrowed.
 *
 * <p>Null (not an empty map) when there is no origin, so the caller's "am I away" test is the
 * origin itself rather than the emptiness of a map that is legitimately empty before the first
 * nightly sweep.
 *
 * @param {?object} origin the origin descriptor, or null for home
 * @param {?object} matrix {@code {regionId: {locationId: minutes}}} from the shared endpoint
 * @returns {?Map} location id to {driveMinutes, distanceMiles}, or null at home
 */
export function originReachMap(origin, matrix) {
  if (!origin) return null;
  const rows = matrix?.[origin.id] ?? matrix?.[String(origin.id)] ?? null;
  const map = new Map();
  if (!rows) return map;
  for (const [locationId, minutes] of Object.entries(rows)) {
    if (typeof minutes !== 'number' || !Number.isFinite(minutes)) continue;
    // The keys arrive as JSON object keys, i.e. strings, and every consumer looks a spot up by its
    // numeric `locationId`. Coerced here, once, rather than at each `get`.
    const id = Number(locationId);
    if (!Number.isFinite(id)) continue;
    map.set(id, { driveMinutes: minutes, distanceMiles: null });
  }
  return map;
}

/**
 * The spots the page is about — the planning area at home, one region when away.
 *
 * <p>One module for both, so the strip, the row maps and the Map tab's opening bounds cannot
 * disagree about what is in shot. At home this is exactly {@code areaSpots} and nothing changes;
 * away it is the origin's own region, which is what makes all six thumbnails re-frame on the move.
 *
 * <p>Matched on region NAME, because that is the only key the locations payload and the briefing
 * payload share (see {@code heatSpots.js}) — and byte-identically, never normalised, for the
 * reason recorded there.
 *
 * @param {Array}   spots     the whole heat catalogue
 * @param {?Map}    reachById per-user reach, for the home arm's planning area
 * @param {?object} origin    the origin descriptor, or null for home
 * @returns {Array} the scoped subset, in input order
 */
export function scopeSpots(spots, reachById, origin) {
  if (!origin) return areaSpots(spots, reachById);
  return (Array.isArray(spots) ? spots : []).filter((s) => s && s.regionName === origin.name);
}

/**
 * The spots one window is about — every spot at home, the origin's own region when away.
 *
 * <p>Deliberately not {@code gateSpotsByRegion} from {@code windowFirstRegions.js}, which is the
 * same predicate: that module imports {@code windowFirstCards.js}, and {@code windowFirstCards.js}
 * is this function's caller, so reusing it would close an import cycle. The predicate is one
 * comparison; the cycle would be a permanent hazard.
 *
 * <p>It runs BEFORE both lens gates, where the region <em>selection</em> runs after them. That is
 * the difference between a scope and a filter: the origin decides what the page is about, so the
 * counts the card prints are counts of the scope; the rail's selection narrows what the lens
 * already chose, so its counts stay counts of the lens.
 *
 * @param {Array}   spots  a window's spots
 * @param {?object} origin the origin descriptor, or null for home
 * @returns {Array} the spots in scope, in the order they arrived
 */
export function gateSpotsByOrigin(spots, origin) {
  if (!origin) return spots;
  return (Array.isArray(spots) ? spots : []).filter((spot) => spot?.regionName === origin.name);
}
