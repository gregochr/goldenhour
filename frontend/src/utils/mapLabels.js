/**
 * The Map tab's label pass — pure logic only (map-tab-v2-plan.md §3 P8,
 * `docs/design/map-tab-v2/README.md` §6 "Labels — placement and density").
 *
 * <p>Everything here is projection-agnostic: a caller (`components/map/MapLabels.jsx`) does the
 * Leaflet projection (`map.latLngToContainerPoint`) and the DOM measurement (a label's real
 * `offsetWidth`/`offsetHeight`, which depends on the loaded font and the reader's text), then
 * hands this module already-projected, already-measured candidates. That split is what makes the
 * density ramp, the budget formula, the region "hottest" pick and the priority-order placement
 * pass testable without a browser — the same reason `labelPlacement.js` itself is a pure module
 * the DOM host merely calls into.
 *
 * <p>The four label kinds, in the priority order the README specifies (§6): home marker, ring
 * labels, region names, location chips. Each has its own candidate builder below and each
 * self-gates on zoom, so a caller can build the full priority-ordered item list by concatenating
 * all four builders' output unconditionally — a builder above its own zoom threshold simply
 * returns an empty array, contributing nothing.
 */

import { centroid } from './heatField.js';
import { clamp } from './heatGeometry.js';
import {
  MAP_NUDGES, mapDxOffsets, placeWithNudges,
} from './labelPlacement.js';

// ── Zoom thresholds (README "Zoom thresholds") ──────────────────────────────────────────────────

/** The home marker is placed below this zoom — README §6 priority order item 1. */
export const HOME_LABEL_MAX_ZOOM = 13;

/** Ring labels are placed below this zoom — the SAME gate `MapHeatLayer` draws the rings at. */
export const RING_LABEL_MAX_ZOOM = 10.6;

/** Region names are placed below this zoom — README §6 priority order item 3. */
export const REGION_LABEL_MAX_ZOOM = 11.2;

/**
 * Below this frame width a region uses its short/tiny form (README §6: "Short names below 430px
 * width"). Curated short names are decision D-11 (`docs/engineering/map-tab-v2-plan.md` §5) — this
 * phase only decides WHEN to shorten, via CSS truncation of the served name, not what to shorten
 * it TO.
 */
export const REGION_TINY_FRAME_WIDTH = 430;

// ── Verdict word (README "Verdict thresholds") ──────────────────────────────────────────────────

/** @see verdictWord */
export const WORTH_IT_THRESHOLD = 3.7;
/** @see verdictWord */
export const MAYBE_THRESHOLD = 2.8;

/**
 * A bare per-location star has no served verdict enum to read (map-tab-v2-plan.md §3 P9's own
 * callout-contents paragraph: "verdict words come from served enums where the surface has one;
 * the ≥3.7/≥2.8 client thresholds are only for surfaces with no served verdict, and the map's
 * per-location star has none — record the choice in-code"), so the hover tooltip built HERE (P8)
 * falls back to the design bundle's own client thresholds: {@code ≥3.7 Worth it},
 * {@code ≥2.8 Maybe}, else {@code Poor}. This IS that recorded in-code decision — a location star
 * is always a whole number in this catalogue, so the fractional thresholds collapse to the
 * familiar {@code ≥4}/{@code ≥3}/{@code else} bands without needing to say so twice.
 *
 * <p>⚠️ P9's callout answers the exact same question for the exact same per-location star (the
 * plan paragraph above is P9's, not P8's) and MUST import {@link WORTH_IT_THRESHOLD}/
 * {@link MAYBE_THRESHOLD}/this function from here rather than re-deriving its own copy — two
 * client thresholds for one ungoverned quantity is exactly the kind of drift a shared constant
 * exists to prevent.
 *
 * @param {?number} rating 1–5, or null/non-finite for "not scored"
 * @returns {?string} {@code 'Worth it'|'Maybe'|'Poor'}, or null when there is no rating to judge
 */
export function verdictWord(rating) {
  if (rating == null || !Number.isFinite(rating)) return null;
  if (rating >= WORTH_IT_THRESHOLD) return 'Worth it';
  if (rating >= MAYBE_THRESHOLD) return 'Maybe';
  return 'Poor';
}

// ── Chip density (README "Density ramps with zoom") ─────────────────────────────────────────────

const BUDGET_MIN = 6;
const BUDGET_MAX = 60;
const BUDGET_ZOOM_BASE = 8.6;
const BUDGET_PER_ZOOM = 11;

/**
 * {@code clamp(6 + (zoom - 8.6) * 11, 6, 60)}, rounded — the README's own formula, verbatim. The
 * measured "hole" the README warns against (13 named spots in view at county scale, only 2
 * labelled) was a STEP function from "one per region" straight to "all of them"; this is the
 * continuous ramp that replaced it, and {@code mapLabels.test.js}'s density suite exists
 * specifically to catch that step from ever coming back.
 *
 * @param {number} zoom the map's current zoom (fractional — `zoomSnap: 0` on the tab)
 * @returns {number} how many IN-VIEW chips to offer the placer, before the best-per-region
 *          candidates that are ALWAYS added on top (see {@link chipCandidates})
 */
export function chipBudget(zoom) {
  return Math.round(clamp(BUDGET_MIN + (zoom - BUDGET_ZOOM_BASE) * BUDGET_PER_ZOOM, BUDGET_MIN, BUDGET_MAX));
}

// ── Region names ─────────────────────────────────────────────────────────────────────────────────

/** The mean of only the FINITE values in {@code values}; 0 when there are none (matches d3.mean's
 * own "ignore non-numeric, then `|| 0`" idiom the bundle used — an unrated region reads as the
 * coldest one, never as hottest by default). */
function meanFinite(values) {
  const finite = values.filter((v) => Number.isFinite(v));
  return finite.length ? finite.reduce((a, b) => a + b, 0) / finite.length : 0;
}

/**
 * The region with the highest mean rating over {@code spots} — README §6: "the highest-average
 * region gets `#F9F1E2`". Ties resolve to whichever region {@code Set} iteration meets first
 * (insertion order), matching the bundle's own {@code reduce} — not specified further because two
 * regions averaging EXACTLY the same score is not a case the design distinguishes.
 *
 * @param {Array<{rid: string, rating: ?number}>} spots
 * @returns {?string} the hottest region's name, or null for an empty catalogue
 */
export function hottestRegion(spots) {
  const rids = [...new Set(spots.map((s) => s.rid))];
  if (rids.length === 0) return null;
  return rids.reduce((best, rid) => {
    const avg = meanFinite(spots.filter((s) => s.rid === rid).map((s) => s.rating));
    const bestAvg = meanFinite(spots.filter((s) => s.rid === best).map((s) => s.rating));
    return avg > bestAvg ? rid : best;
  }, rids[0]);
}

/**
 * Region-name label candidates — one per distinct region in {@code spots}, at that region's
 * projected centroid (README §6: "Placed at the pixel centroid of that region's visible
 * locations" — over the FILTERED pool, i.e. {@code spots} as handed in, never bounds-filtered:
 * the bundle's own `pool()`, not a second "in view" test).
 *
 * <p>Self-gates on {@code zoom} — returns {@code []} at/above {@link REGION_LABEL_MAX_ZOOM}, so a
 * caller may call this unconditionally when assembling the priority-ordered item list.
 *
 * @param {Array<{rid: string, rating: ?number}>} spots the filtered pool
 * @param {Function} project {@code (spot) => [x, y]} — a projected point in frame px
 * @param {number} zoom the map's current zoom
 * @returns {Array<{key: string, rid: string, x: number, y: number, hot: boolean}>}
 */
export function regionLabelItems(spots, project, zoom) {
  if (zoom >= REGION_LABEL_MAX_ZOOM) return [];
  const hot = hottestRegion(spots);
  const rids = [...new Set(spots.map((s) => s.rid))];
  const items = [];
  for (const rid of rids) {
    const at = centroid(spots, rid, project);
    if (!at || !Number.isFinite(at[0]) || !Number.isFinite(at[1])) continue;
    items.push({
      key: `region:${rid}`, rid, x: at[0], y: at[1], hot: rid === hot,
    });
  }
  return items;
}

// ── Home marker ──────────────────────────────────────────────────────────────────────────────────

/**
 * The home-marker label candidate — README §6 priority order item 1: "every drive time and
 * leave-by on this screen is measured from it." Self-gates on {@code zoom}.
 *
 * @param {{x: number, y: number}} homePoint the home coordinate, already projected to frame px
 * @param {number} zoom the map's current zoom
 * @returns {Array<{key: 'home', x: number, y: number}>} zero or one item — an array so it
 *          concatenates directly with the other three candidate lists
 */
export function homeLabelItems(homePoint, zoom) {
  if (zoom >= HOME_LABEL_MAX_ZOOM) return [];
  return [{ key: 'home', x: homePoint.x, y: homePoint.y }];
}

// ── Ring labels ──────────────────────────────────────────────────────────────────────────────────

/** "25 mi" — deliberately a tiny LOCAL formatter, the same call `WindowRowFieldMap.jsx` makes for
 * the identical reason (its own comment): there is no shared distance-formatting vocabulary to
 * reuse, and a plain distance is answerable from the authored mile constant alone. */
function formatMiles(mi) {
  return `${mi} mi`;
}

/**
 * Ring-label candidates — README §6 priority order item 2: {@code "45 min"}/{@code "1h 30"}, one
 * per ring tier, positioned {@code x = home.x + 26} with a {@code y > 10 && y < frameHeight - 10}
 * keep-in (the bundle's own pre-filter, so a ring whose circle has scrolled almost off the top or
 * bottom does not plant a label off-screen for the placer to reject anyway).
 *
 * <p>Text is a DISTANCE by default and a DURATION only when {@code reachMeasured} — the same rule
 * {@code WindowRowFieldMap}'s own ring labels apply, using the app's real
 * {@code formatDriveDuration} (not the bundle's bare {@code "1h 30"} string) so the two surfaces'
 * duration text can never drift apart.
 *
 * <p>Self-gates on {@code zoom} — the SAME {@link RING_LABEL_MAX_ZOOM} {@code MapHeatLayer} draws
 * the rings below, so a label can never survive its own ring going dark.
 *
 * @param {object} args
 * @param {{x: number, y: number}} args.homePoint home, already projected to frame px
 * @param {number} args.zoom the map's current zoom
 * @param {Array<{mi: number, minutes: number, r: number}>} args.ringsWithRadius
 *        {@code utils/reachRings.RING_TIERS} plus each tier's own projected radius in px — the
 *        SAME radius {@code MapHeatLayer} drew the circle at, so a label always sits on its ring
 * @param {number} args.frameHeight
 * @param {boolean} args.reachMeasured whether a real drive time gated this screen's reach lens
 * @param {Function} [args.formatDuration] {@code (minutes) => string}. Defaults to
 *        {@code String(minutes)} so this module carries no hard dependency on
 *        {@code utils/briefingDisplay.js}; the real host always passes
 *        {@code formatDriveDuration}.
 * @returns {Array<{key: string, x: number, y: number, text: string}>}
 */
export function ringLabelItems({
  homePoint, zoom, ringsWithRadius, frameHeight, reachMeasured, formatDuration = String,
}) {
  if (zoom >= RING_LABEL_MAX_ZOOM) return [];
  const items = [];
  for (const ring of ringsWithRadius) {
    const y = homePoint.y - ring.r;
    if (y <= 10 || y >= frameHeight - 10) continue;
    items.push({
      key: `ring:${ring.mi}`,
      x: homePoint.x + 26,
      y,
      text: reachMeasured ? formatDuration(ring.minutes) : formatMiles(ring.mi),
    });
  }
  return items;
}

// ── Location chips ───────────────────────────────────────────────────────────────────────────────

/**
 * The ordered, density-ramped chip SELECTION — which spots even get offered to the placer, and in
 * what order, before the placer's own collision pass does the final arbitration (README §6
 * "Density ramps with zoom"; the selected location's own guarantee is folded in here too, per the
 * priority-order paragraph: "the selected location always gets its chip").
 *
 * <p>Sorted best score first, then TIDE ALIGNMENT, then nearest (missing values sort last in every
 * tier — README: "so when space runs out it is always the weakest names that go"). Among equal
 * stars, the location whose tide lands on the light for THIS window is the one worth the drive, so
 * it is also the one that keeps its label when space runs out (bundle rev 2's tide-chip tweak,
 * mirroring {@code map-tab-v2.js}'s own {@code tideFit(b,e)?1:0)-(tideFit(a,e)?1:0)} rule). The
 * best-in-region candidate for EVERY region is always included, from the FULL {@code spots} list,
 * not the in-view subset — "a named region always contains a named destination." The in-view
 * subset is then capped at {@link chipBudget}. Identity is the location NAME (this catalogue's
 * stable join key — {@code utils/heatSpots.js}'s own convention), so a duplicate spot object can
 * never appear twice.
 *
 * @param {object} args
 * @param {Array<object>} args.spots the filtered pool (every field {@code chipCandidates} reads:
 *        {@code name, rid, rating, onTheLight, driveMinutes})
 * @param {?Set<string>} [args.inViewNames] names currently inside the map's bounds. Null/undefined
 *        treats every spot as in view (a caller with no bounds yet, e.g. before the first
 *        {@code moveend}) — the safe direction is to offer more candidates, never fewer, since the
 *        placer's own collision pass still does the real arbitration.
 * @param {number} args.zoom the map's current zoom, for {@link chipBudget}
 * @param {?string} [args.selectedName] the selected location's name — always kept, and always
 *        moved to the FRONT of the returned order, so a caller can rely on
 *        {@code result[0] === selected} whenever a selection exists and survived the filters.
 * @returns {Array<object>} the ordered subset of {@code spots} to offer the placer
 */
export function chipCandidates({
  spots, inViewNames = null, zoom, selectedName = null,
}) {
  const sorted = [...spots].sort((a, b) => {
    const ra = Number.isFinite(a.rating) ? a.rating : -Infinity;
    const rb = Number.isFinite(b.rating) ? b.rating : -Infinity;
    if (rb !== ra) return rb - ra;
    const ta = a.onTheLight ? 1 : 0;
    const tb = b.onTheLight ? 1 : 0;
    if (tb !== ta) return tb - ta;
    const da = Number.isFinite(a.driveMinutes) ? a.driveMinutes : Infinity;
    const db = Number.isFinite(b.driveMinutes) ? b.driveMinutes : Infinity;
    return da - db;
  });

  const inView = inViewNames ? sorted.filter((s) => inViewNames.has(s.name)) : sorted;
  const budget = chipBudget(zoom);

  const bestPerRegion = new Map();
  for (const spot of sorted) {
    if (!bestPerRegion.has(spot.rid)) bestPerRegion.set(spot.rid, spot);
  }

  const seen = new Set();
  const shown = [];
  const addUnique = (spot) => {
    if (!spot || seen.has(spot.name)) return;
    seen.add(spot.name);
    shown.push(spot);
  };
  for (const spot of bestPerRegion.values()) addUnique(spot);
  for (const spot of inView.slice(0, budget)) addUnique(spot);

  if (selectedName != null) {
    const idx = shown.findIndex((s) => s.name === selectedName);
    if (idx >= 0) shown.splice(idx, 1);
    const selectedSpot = sorted.find((s) => s.name === selectedName);
    if (selectedSpot) shown.unshift(selectedSpot);
  }

  return shown;
}

// ── The placement pass ───────────────────────────────────────────────────────────────────────────

/**
 * The one greedy pass, in priority order (README §6). Every {@code item} must already carry a
 * measured {@code {w, h}} alongside its anchor {@code {x, y}} — see the module doc comment for why
 * measurement stays the DOM host's job. Uses the Map tab's own ladder ({@link MAP_NUDGES} ×
 * {@link mapDxOffsets}), never the Plan callers' — see {@code labelPlacement.js} for why the two
 * stay independent.
 *
 * @param {Array<{key: string, x: number, y: number, w: number, h: number}>} items in PRIORITY
 *        ORDER — an earlier item that fits keeps its space regardless of what a later one would
 *        have preferred
 * @param {number} frameWidth
 * @param {number} frameHeight
 * @param {Array<{x: number, y: number, w: number, h: number}>} [obstacles] pre-seeded boxes (the
 *        live chrome rects, {@code utils/labelPlacement.js}'s {@code seedObstacles}) — never
 *        mutated
 * @returns {Map<string, {x: number, y: number, w: number, h: number}>} placed boxes, keyed by each
 *          item's own {@code key}. An item with no entry here was dropped — never stacked, never
 *          shrunk (README: "an unreadable name is worse than a missing one").
 */
export function placeLabelPass(items, frameWidth, frameHeight, obstacles = []) {
  let boxes = obstacles;
  const placed = new Map();
  for (const item of items) {
    const box = placeWithNudges(
      { x: item.x, y: item.y },
      { w: item.w, h: item.h },
      boxes,
      frameWidth,
      frameHeight,
      { dy: MAP_NUDGES, dx: mapDxOffsets },
    );
    if (box) {
      boxes = [...boxes, box];
      placed.set(item.key, box);
    }
  }
  return placed;
}
