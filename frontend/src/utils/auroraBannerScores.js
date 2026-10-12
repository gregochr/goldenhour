/**
 * The aurora banner's score arithmetic — pure, and kept apart from the component and the hook so each
 * rule about which site is drawn and named can be tested on its own (docs/engineering/
 * aurora-banner-plan.md B2). It imports no heat kernel (`heatGeometry.js` is d3-free by design), but
 * that is not what keeps the kernel out of the entry chunk: the `React.lazy` import in
 * `AuroraBanner.jsx` is.
 *
 * <p>Everything here is filter/map/select over rows the aurora endpoints already serve; nothing
 * re-derives a rating. The one per-user input is the HOME drive, which enters as a tie-break only.
 */
import { bbox } from './heatGeometry.js';
import { scopeSpots } from './planOrigin.js';

/**
 * Alert levels that raise the banner and that earn per-location scores: MODERATE or STRONG. The
 * banner and the score hook share this one; `MapView.jsx` keeps its own `ALERT_WORTHY_LEVELS` with
 * the same members, mirrored by hand because the plan leaves MapView untouched (§6) — a level added
 * here must be added there.
 */
export const ALERT_WORTHY = new Set(['MODERATE', 'STRONG']);

/**
 * Whether a status is a REAL alert worth per-location scores (and, in B3, a viewline): present,
 * alert-worthy and not simulated. A simulated alert is an admin preview that must cost nothing and
 * show nothing real (plan §4 D9). One predicate so the hook and the banner's later reads agree.
 *
 * @param {?object} status the shared aurora status
 * @returns {boolean} true for a live or forecast MODERATE/STRONG alert
 */
export function isRealAlert(status) {
  return Boolean(status) && status.simulated !== true && ALERT_WORTHY.has(status.level);
}

/** Padding of the thumbnail's frame, in degrees of latitude — the spec's `HeatField.bbox(areaSpots, 0.25)`. */
export const FRAME_PAD_DEG = 0.25;

/** A rated star: a finite integer 1–5. A null, a fraction or a 0 is not a rating. */
function isStar(stars) {
  return Number.isInteger(stars) && stars >= 1 && stars <= 5;
}

function isCoordinate(v) {
  return typeof v === 'number' && Number.isFinite(v);
}

/**
 * One rated site in the thumbnail's own shape, or null when the row cannot be drawn or named.
 * `rid` is the name: the thumbnail uses no `focus` and draws no region labels, and the kernel only
 * needs it truthy.
 */
function toSpot(id, name, lat, lon, stars) {
  if (!isStar(stars) || !isCoordinate(lat) || !isCoordinate(lon)) return null;
  if (typeof name !== 'string' || name.trim() === '') return null;
  return { id: id ?? null, name, lat, lng: lon, rid: name, stars };
}

/**
 * The rated sites in a LIVE answer (`GET /api/aurora/locations`: rows `{location: {id, name, lat,
 * lon}, stars}`).
 *
 * @param {*} rows the response body
 * @returns {?Array<{id: ?number, name: string, lat: number, lng: number, rid: string, stars: number}>}
 *   null when the body is not a list at all (a captive portal's 200) — that is a failed read, not an
 *   answer of "no rated site"
 */
export function ratedFromLive(rows) {
  if (!Array.isArray(rows)) return null;
  return rows
    .map((r) => (r && r.location ? toSpot(r.location.id, r.location.name, r.location.lat, r.location.lon, r.stars) : null))
    .filter(Boolean);
}

/**
 * The rated sites in a STORED answer (`GET /api/aurora/forecast/results`: rows `{locationId,
 * locationName, lat, lon, stars, triaged}`). A triaged row carries no Claude rating whatever its
 * `stars` says, so it is not a site.
 *
 * @param {*} rows the response body
 * @returns {?Array<{id: ?number, name: string, lat: number, lng: number, rid: string, stars: number}>}
 *   null when the body is not a list (see {@link ratedFromLive})
 */
export function ratedFromStored(rows) {
  if (!Array.isArray(rows)) return null;
  return rows
    .map((r) => (r && r.triaged !== true ? toSpot(r.locationId, r.locationName, r.lat, r.lon, r.stars) : null))
    .filter(Boolean);
}

/**
 * The rated sites inside the planning area, joined by location id with the name as the fallback for a
 * site that has no id (docs/engineering/aurora-banner-plan.md §4 D8).
 */
function restrictToArea(rated, scoped) {
  const ids = new Set();
  const idlessNames = new Set();
  for (const s of scoped) {
    if (s.id != null) ids.add(s.id);
    else if (s.name) idlessNames.add(s.name);
  }
  return rated.filter((r) => (r.id != null && ids.has(r.id)) || (r.id == null && idlessNames.has(r.name)));
}

/** A drive in minutes that can be compared; anything else sorts after every measured one. */
function homeDrive(reachById, id) {
  const minutes = id != null ? reachById?.get?.(id)?.driveMinutes : undefined;
  return typeof minutes === 'number' && Number.isFinite(minutes) ? minutes : Infinity;
}

/**
 * The best rated site: the most stars; ties to the shorter HOME drive, an unknown drive last; then
 * name A→Z (plan §4 D3). The drive is `reachById`'s — never the Plan origin's — so an away origin
 * cannot turn "best" into a statement about somewhere the reader is not.
 *
 * @param {Array<{id: ?number, name: string, stars: number}>} rated
 * @param {?Map} reachById HOME drive minutes by location id
 * @returns {?{id: ?number, name: string, stars: number}} null for an empty list
 */
export function bestOf(rated, reachById) {
  let best = null;
  for (const r of rated) {
    if (best === null) { best = r; continue; }
    if (r.stars !== best.stars) {
      if (r.stars > best.stars) best = r;
      continue;
    }
    const dr = homeDrive(reachById, r.id);
    const db = homeDrive(reachById, best.id);
    if (dr !== db) {
      if (dr < db) best = r;
      continue;
    }
    if (r.name.localeCompare(best.name) < 0) best = r;
  }
  return best;
}

/**
 * What the thumbnail draws and the best line names, from tonight's rated sites and the reader's
 * planning area (plan §4 D8).
 *
 * <p>The points AND the best candidate are the rated sites inside the planning area
 * (`scopeSpots(heatSpots, reachById, origin)`), and the frame is the area. Only when the scoped set is
 * EMPTY — the briefing has not loaded, or the roster is unknown — do all rated sites count and frame
 * themselves; a rated site outside a KNOWN area is neither drawn nor named, and an alert whose only
 * rated sites are outside it has no field (null), staying on the pending slot.
 *
 * @param {Array} rated the rated sites ({@link ratedFromLive} / {@link ratedFromStored})
 * @param {Array} heatSpots the provider's catalogue
 * @param {?Map} reachById HOME drive minutes by location id
 * @param {?object} origin the Plan origin, or null for home
 * @returns {?{spots: Array, fit: object, best: object}} null when nothing rated is in the area
 */
export function areaField(rated, heatSpots, reachById, origin) {
  if (!Array.isArray(rated) || rated.length === 0) return null;
  const scoped = scopeSpots(heatSpots, reachById, origin);
  const spots = scoped.length > 0 ? restrictToArea(rated, scoped) : rated;
  if (spots.length === 0) return null;
  const fit = bbox(scoped.length > 0 ? scoped : rated, FRAME_PAD_DEG);
  return { spots, fit, best: bestOf(spots, reachById) };
}
