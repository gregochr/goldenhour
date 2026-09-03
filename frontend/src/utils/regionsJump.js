import { GLANCE_MINUTES, regionDriveMinutes } from './planningArea.js';
import { bestOfNight } from './mapEvents.js';

/**
 * The Regions jump list — pure logic only (map-tab-v2-plan.md §3 P11,
 * `docs/design/map-tab-v2/README.md` §2 "Regions jump list — search, without a text field").
 *
 * <p>"Search, without a text field": one row per region, sorted by the nearest measured drive from
 * whichever origin the reader is currently planning from, with that window's best served score
 * beside it. Selecting a row is the whole interaction — there is no query to type.
 *
 * <h2>Home reach and the region-base matrix are never mixed</h2>
 *
 * <p>{@link buildJumpRows}'s {@code driveMap} is a single map, on purpose — the caller picks EITHER
 * the per-user home reach ({@code GET /api/user/settings/reach}) OR the shared region-base matrix
 * (`utils/planOrigin.js`'s {@code originReachMap}, over {@code GET /api/regions/drive-times}), never
 * both, mirroring {@code MapView.jsx}'s own {@code driveMinutesFor}: an away origin's map OVERWRITES
 * the home figure rather than falling back to it, because a location absent from the away map has an
 * unmeasured drive from THAT origin, not a stale one from home. Both maps carry the identical
 * {@code {driveMinutes, distanceMiles}} shape, so {@link regionDriveMinutes} (which reads only
 * {@code driveMinutes}) is origin-agnostic and needs no second implementation here — the "never mix"
 * rule lives entirely in which single map the caller passes in, not in this module.
 *
 * <h2>Rows are built over the WHOLE catalogue, not the current scope</h2>
 *
 * <p>The design bundle's own {@code buildJump} computes {@code near} over {@code SPOTS} (every
 * region), never {@code basePool()} (the scoped subset) — a jump list scoped to where you already
 * are could never answer "where else could I go", which is the entire point of the control. Callers
 * must pass {@code heat.spots} (the full catalogue), not {@code heat.areaSpots}.
 *
 * <h2>Best score is the served {@code BriefingRegion.bestRating}, name-keyed — for a SOLAR window</h2>
 *
 * <p>{@link buildRegionBestIndex} joins on region NAME, the same `heatSpots.js`/
 * `windowFirstRegions.js` idiom the rest of this arm already uses (region id on the briefing rollup
 * is owner item O-8) — byte-identical, never normalised, for the reason `heatSpots.js` states at
 * length. It is keyed correctly on {@code region.regionName}: a sibling join
 * ({@code mapCallout.buildRegionGlossIndex}) once read {@code region.name} instead, which the served
 * {@code BriefingRegion} record never carries (its own field is {@code regionName}) — that mistake is
 * not repeated here.
 *
 * <h2>A night window's best is the SAME licensed client max, grouped more finely</h2>
 *
 * <p>Adjudicated ruling (map-tab-v2-plan.md §3 P11, recorded for P13's licensed-member ledger):
 * {@link buildNightRegionBest} reduces to {@code mapEvents.bestOfNight} — the ALREADY-licensed client
 * max the window dropdown's own "N★ best" column takes over a night's served per-location stars,
 * because no server-owned per-region figure exists for a night at all. Grouping those same served
 * rows by region before taking the max is a finer KEY on an operation already licensed, not a new
 * re-derivation — so a night row is no longer a bare em dash the way an unscored solar row is; it
 * carries the grouped max, and only a region with no served night rows at all keeps the honest dash.
 */

/**
 * Every region's served {@code bestRating} for every rendered window, keyed
 * {@code date|targetType|regionName} — the same composite key `mapCallout.buildRegionGlossIndex`
 * uses for the identical reason (one join, one key shape, across this arm's per-window region
 * lookups).
 *
 * @param {Array} days {@code briefing.days}
 * @returns {Map<string, number>} the composite key to the region's best rating for that window
 */
export function buildRegionBestIndex(days) {
  const index = new Map();
  for (const day of Array.isArray(days) ? days : []) {
    if (!day?.date) continue;
    for (const summary of day.eventSummaries ?? []) {
      if (!summary?.targetType) continue;
      for (const region of summary.regions ?? []) {
        if (!region?.regionName) continue;
        const rating = region.bestRating;
        if (typeof rating !== 'number' || !Number.isFinite(rating)) continue;
        const key = `${day.date}|${summary.targetType}|${region.regionName}`;
        if (!index.has(key)) index.set(key, rating);
      }
    }
  }
  return index;
}

/**
 * One region's served best for one window, or null when nothing was served (no index, no entry, or
 * a window this join was never asked about — a night event, which carries no per-region rollup at
 * all: see {@link buildJumpRows}'s own doc for why night windows pass a null {@code bestRatingFor}
 * rather than a client-side re-derivation).
 *
 * @param {?Map} index from {@link buildRegionBestIndex}
 * @param {?string} date
 * @param {?string} targetType SUNRISE or SUNSET
 * @param {?string} regionName
 * @returns {?number}
 */
export function regionBestRatingFor(index, date, targetType, regionName) {
  if (!index || !date || !targetType || !regionName) return null;
  return index.get(`${date}|${targetType}|${regionName}`) ?? null;
}

/**
 * A night window's best score, grouped by region — the licensed client max
 * ({@code mapEvents.bestOfNight}) at a finer key, over the SAME served rows the window dropdown
 * already reduces with it (see the module doc's "A night window's best" section for the full
 * adjudicated ruling). No server-owned per-region figure exists for a night, so grouping the served
 * rows by region before taking the max is the same licensed operation, not a new re-derivation.
 *
 * <p>The region key comes from {@code heat.spots}' own location-name → region-name pairing — the
 * SAME catalogue {@link buildJumpRows} is called with — so a served row naming a location absent
 * from the catalogue (should not happen; both come from one roster) is silently excluded rather than
 * crashing or inventing a region for it.
 *
 * @param {Array<{locationName: ?string, stars: ?number}>} rows the night's served results (astro
 *        conditions or aurora forecast results, whichever window is active) — `nightRow`'s own shape
 * @param {Array<{name: ?string, regionName: ?string}>} spots the catalogue (`heat.spots`)
 * @returns {Map<string, ?number>} region name → that night's best served star among its locations,
 *          or null when the region had served rows but none carried a usable star
 */
export function buildNightRegionBest(rows, spots) {
  const nameToRegion = new Map();
  for (const spot of Array.isArray(spots) ? spots : []) {
    if (spot?.name && spot?.regionName) nameToRegion.set(spot.name, spot.regionName);
  }
  const byRegion = new Map();
  for (const row of Array.isArray(rows) ? rows : []) {
    const region = nameToRegion.get(row?.locationName);
    if (!region) continue;
    const list = byRegion.get(region);
    if (list) list.push(row);
    else byRegion.set(region, [row]);
  }
  const result = new Map();
  for (const [region, regionRows] of byRegion) {
    result.set(region, bestOfNight(regionRows));
  }
  return result;
}

/**
 * The jump list's rows: one per region in the catalogue, nearest measured drive first.
 *
 * <p><b>Unmeasured rows sort last and carry no duration</b> (the {@code reachMeasured} discipline,
 * map-tab-v2-plan.md §1.12) — a region with no measured location is not evidence of distance, so it
 * neither claims a duration nor claims to be "beyond your area"; it simply cannot be ranked against
 * the ones that are, and sorts after all of them. Name is the tiebreak on equal (or equally unknown)
 * drives, so the order is total — without it two regions can swap places between renders for no
 * reason a reader could see, `windowFirstRegions.buildRegionRows`'s own sort applies the identical
 * tiebreak for the identical reason.
 *
 * <p><b>The "beyond your area" suffix</b> is the shared {@link GLANCE_MINUTES} threshold
 * (`utils/planningArea.js`) — one source for the "3h" every surface on this arm states, rather than a
 * second constant that could drift from it.
 *
 * <p><b>The score comes from whatever {@code bestRatingFor} the caller built.</b> For a SOLAR window
 * that is the served {@code BriefingRegion.bestRating} ({@link regionBestRatingFor}); for a night
 * window it is {@link buildNightRegionBest}'s grouped {@code mapEvents.bestOfNight} — both are
 * resolved by the CALLER (`MapView.jsx`'s `jumpBestRatingFor`), which knows which kind of event is
 * active, and this function stays agnostic to that distinction. A region with nothing to show
 * (`bestRatingFor` returns null, or is omitted) renders with no swatch, exactly like an unscored
 * solar row beyond the briefing horizon does elsewhere on this tab ({@code WindowControl}'s own
 * em-dash) — the only case that still reaches this is a region with no served rows at all, on
 * either axis.
 *
 * @param {object} args
 * @param {Array<{regionName: ?string}>} args.spots the WHOLE catalogue (`heat.spots`, never
 *        `heat.areaSpots` — see the module doc)
 * @param {?Map<*, {driveMinutes: ?number}>} args.driveMap EITHER the per-user home reach OR the
 *        away region-base matrix, never both at once (see the module doc)
 * @param {(regionName: string) => ?number} [args.bestRatingFor] resolves a region's best score for
 *        the active window (solar: served; night: {@link buildNightRegionBest}'s grouped max);
 *        omitted or returning null renders that row with no score
 * @returns {Array<{name: string, driveMinutes: ?number, beyondArea: boolean, bestRating: ?number}>}
 *          rows, nearest measured drive first
 */
export function buildJumpRows({ spots, driveMap, bestRatingFor }) {
  const list = Array.isArray(spots) ? spots : [];
  const names = [...new Set(list.map((s) => s?.regionName).filter(Boolean))];
  const minutes = regionDriveMinutes(list, driveMap);
  const rows = names.map((name) => {
    const driveMinutes = minutes.has(name) ? minutes.get(name) : null;
    return {
      name,
      driveMinutes,
      beyondArea: driveMinutes != null && driveMinutes > GLANCE_MINUTES,
      bestRating: typeof bestRatingFor === 'function' ? (bestRatingFor(name) ?? null) : null,
    };
  });
  return rows.sort((a, b) => {
    const da = a.driveMinutes ?? Infinity;
    const db = b.driveMinutes ?? Infinity;
    return da === db ? a.name.localeCompare(b.name) : da - db;
  });
}
