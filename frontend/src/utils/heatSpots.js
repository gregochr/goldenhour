/**
 * The heat field's catalogue join — locations × batch scores × the six rendered windows.
 *
 * <p>Two contracts meet here and neither carries the other's data. {@code GET /api/locations}
 * has the geography (and is the ONLY payload that does — plan §3: the briefing and scores
 * payloads carry no lat/lng, and that stays true); {@code GET /api/briefing/evaluate/scores} has
 * a flat row per (location, date, event) with the rating. One spot per location, one score per
 * window, joined by <b>locationId first and location name second</b> — the {@code BriefingSlot}
 * doctrine, because a name is a display string a user can edit and an id is not.
 *
 * <p><b>Every filter in here is load-bearing, and none of it is belt-and-braces.</b> The kernel
 * (`heatField.js`, ported close to as-is from the design bundle) reads a null score as
 * <b>zero</b> — it does not skip the point, it drags the local blend toward the ramp's bottom and
 * still spends the point's weight on the coverage clamp. So an unscored location left in a
 * window's point set paints a cold patch that looks like a forecast. A non-finite
 * {@code lat}/{@code lng} is worse than a misplaced dot: {@code centroid()} means over its
 * region's projected points, so one NaN takes the whole region's label with it.
 *
 * <p><b>The region key is the region NAME, and that is a trade rather than an oversight.</b> An
 * id would be the stable key, and {@code LocationEvaluationView} even carries {@code regionId} —
 * but {@code BriefingRegion} does not, and the briefing payload is where P3's rail, ranking and
 * focus target all get their region from. A name is therefore the only key the two payloads
 * share. Two consequences to know rather than rediscover: names are joined <b>byte-identically,
 * never normalised</b> (trimming here would make {@code " North East"} on the rail miss
 * {@code "North East"} on every point, and the kernel answers a focus matching nothing by
 * multiplying <em>every</em> weight by 1e-4 — the whole canvas goes transparent on click, with
 * nothing in the console); and a region renamed between the two payloads' cache lifetimes will
 * not join until both refresh. Putting {@code regionId} on {@code BriefingRegion} is the real
 * fix and belongs with P7's region bases.
 */

import { isSkyPromptCandidate } from './locationTypes.js';

/** Ratings are 1–5; the backend's {@code RatingValidator} bounds them and so does this. */
const MIN_RATING = 1;
const MAX_RATING = 5;

/**
 * The index into a point's {@code r} at which {@link heatPointsFor} writes its score.
 *
 * <p>Points carry a one-element {@code r}, so a kernel host is always called as
 * {@code drawGeo(cv, w, h, pts, POINT_SCORE_INDEX, opts)}. What this buys is narrow and worth
 * stating exactly: a mismatched index can no longer show <b>another window's data</b>. It does
 * not make a wrong index safe — reading past a one-element array gives {@code undefined}, which
 * goes to NaN, and P0's deliberate "a non-finite score resolves to the bottom of the ramp" rule
 * then paints a full-strength dark-red field with the coverage clamp untouched. That is why
 * {@link buildHeatPointSets} is keyed rather than positional: the integer that could be passed
 * here does not exist at the call site.
 */
export const POINT_SCORE_INDEX = 0;

/** The key both this module and the shell address a window by ({@code card.key}'s format). */
export function windowKey(date, targetType) {
  return `${date}:${targetType}`;
}

/** A rating this join will paint: an integer in [1, 5]. Anything else is "not scored". */
function validRating(rating) {
  if (typeof rating !== 'number' || !Number.isInteger(rating)) return null;
  return rating >= MIN_RATING && rating <= MAX_RATING ? rating : null;
}

/**
 * A coordinate the projection can consume. Strictly a finite number — never a coercion.
 * {@code Number(null)} is 0 and {@code Number('')} is 0, so a coercing parse would place a
 * location with a missing coordinate at (0, 0): clipped away invisibly on the geo host, and
 * painted in the Gulf of Guinea on the Leaflet one.
 */
function coord(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/**
 * First-inserted wins on a repeated key — the same collision rule
 * {@code buildBriefingScoreIndex} applies. (Its input, the provider's name-keyed map, is built
 * with an unconditional {@code set} and so is last-wins for an exactly duplicated
 * region|date|event|location key; the two can only differ on a duplicate row, which
 * {@code EvaluationViewService} emits one per (location, date, event) to prevent.)
 */
function putFirst(index, key, value) {
  if (!index.has(key)) index.set(key, value);
}

/**
 * Builds the per-location heat spots for one set of rendered windows.
 *
 * <p>A location is dropped when it has no region name (the region is the field's focus key and
 * P3's rail unit — an unregioned spot could be painted but never selected, named or counted)
 * or no usable coordinates. A location with no scores at all is <b>kept</b>: it is a real place
 * that simply has not been evaluated for these windows, and {@link heatPointsFor} withdraws it
 * from each window it is unscored in.
 *
 * <p><b>A rating that does not mean sky colour never reaches the field.</b> Woodland and bluebell
 * sites are scored by their own prompts on inverted polarity — a canopy GO means heavy cloud and
 * mist — so a wood rated 5 on a flat, misty dawn would bloom gold over sky locations rated 1,
 * on precisely the morning the sky is at its worst. Every other surface that reduces ratings to
 * one visual signal already excludes them: the map's cluster ramp
 * ({@code MapView.excludeFromSkyCluster}), the v2 spot strip ({@code windowFirstSpots}), and the
 * backend half of this very phase ({@code BriefingRegion.bestRating}, over
 * {@code BriefingSlot.votingSlots}). The predicate is {@link isSkyPromptCandidate}, the
 * frontend's existing mirror of the backend's own sky-prompt gate — so the field's population is
 * the one the {@code best N★} printed beside it was computed from, rather than a third answer to
 * one question. The spot is kept (it is a real place in a region you can drive to); only its
 * scores are withheld, and {@code skySubject} records why.
 *
 * @param {Array<{id: *, name: string, lat: number, lon: number, regionName: ?string,
 *   locationType: ?string[], bortleClass: ?number}>} locations the enabled roster, as
 *   {@code useForecasts} shapes it
 * @param {Array<{locationId: *, locationName: string, date: string, targetType: string,
 *   rating: ?number}>} scoreRows raw {@code LocationEvaluationView} rows, id included
 * @param {Array<{date: string, targetType: string}>} renderedEvents the windows, in render order
 * @returns {Array<{id: *, name: string, lat: number, lng: number, regionName: string, rid: string,
 *   skySubject: boolean, bortleClass: ?number, scores: Array<?number>}>} one spot per joinable
 *   location
 */
export function buildHeatSpots(locations, scoreRows, renderedEvents) {
  const events = Array.isArray(renderedEvents) ? renderedEvents : [];
  // Only the rendered windows can ever match, so only their rows are worth indexing. The
  // response spans today-2..today+5 (both events, every location) while at most six pairs are on
  // screen, and the whole response is walked again whenever the window list changes. Measured on
  // a 204-location model: indexing every row costs 2.6ms per rebuild against 1.3ms filtered.
  const wanted = new Set(events.map((e) => windowKey(e.date, e.targetType)));
  const byId = new Map();
  const byName = new Map();
  for (const row of Array.isArray(scoreRows) ? scoreRows : []) {
    // A row with no date or event cannot name a window. Without this it would key on the string
    // "undefined:undefined" and collide with an equally malformed rendered event.
    if (!row || !row.date || !row.targetType) continue;
    const tail = windowKey(row.date, row.targetType);
    if (!wanted.has(tail)) continue;
    const rating = validRating(row.rating);
    if (row.locationId != null) putFirst(byId, `${row.locationId}|${tail}`, rating);
    if (row.locationName) putFirst(byName, `${row.locationName}|${tail}`, rating);
  }

  const spots = [];
  for (const location of Array.isArray(locations) ? locations : []) {
    if (!location) continue;
    const regionName = typeof location.regionName === 'string' ? location.regionName : '';
    if (!regionName.trim()) continue;
    const lat = coord(location.lat);
    const lng = coord(location.lon);
    if (lat === null || lng === null) continue;
    const skySubject = isSkyPromptCandidate(location.locationType);
    const scores = events.map(({ date, targetType }) => {
      if (!skySubject) return null;
      const tail = windowKey(date, targetType);
      // `has`, not a truthiness check on the value: a row that exists and carries no usable
      // rating is an ANSWER — "we looked, it is not scored" — and falling through to the
      // name-keyed index there would let a stale or same-named row override the authoritative
      // id-keyed one. Id-first only means anything if an id hit ends the lookup.
      const idKey = location.id == null ? null : `${location.id}|${tail}`;
      if (idKey !== null && byId.has(idKey)) return byId.get(idKey);
      const nameKey = location.name ? `${location.name}|${tail}` : null;
      if (nameKey !== null && byName.has(nameKey)) return byName.get(nameKey);
      return null;
    });
    spots.push({
      id: location.id ?? null,
      name: location.name ?? '',
      lat,
      lng,
      regionName,
      // The kernel's name for the same string, set once from one value. `centroid(spots, rid,
      // project)` filters on `s.rid`, so without it P3 could only position a region label from a
      // window's SCORED subset — and the labels would then move between the six thumbnails as
      // coverage changed.
      rid: regionName,
      skySubject,
      bortleClass: location.bortleClass ?? null,
      scores,
    });
  }
  return spots;
}

/**
 * The kernel-ready points for one window: the spots scored in it, and only those.
 *
 * <p>{@code rid} is the region name, which {@link buildHeatSpots} has already guaranteed is a
 * non-blank string. That guarantee is the point: the kernel tests {@code opts.focus} for
 * <b>truthiness</b>, so a region key of {@code ''} (or {@code 0}) would silently paint the
 * unfocused field when a region is selected — the failure looks like "focus does nothing", with
 * nothing in the console.
 *
 * @param {Array<object>} spots spots from {@link buildHeatSpots}
 * @param {number} windowIndex which of the rendered windows to build points for
 * @returns {Array<{id: *, name: string, lat: number, lng: number, rid: string, r: number[]}>}
 *   points for {@code drawGeo}/{@code drawTiles}, to be read at {@link POINT_SCORE_INDEX}
 */
export function heatPointsFor(spots, windowIndex) {
  const points = [];
  for (const spot of Array.isArray(spots) ? spots : []) {
    const score = spot?.scores?.[windowIndex];
    if (typeof score !== 'number' || !Number.isFinite(score)) continue;
    points.push({
      id: spot.id,
      name: spot.name,
      lat: spot.lat,
      lng: spot.lng,
      rid: spot.rid,
      r: [score],
    });
  }
  return points;
}

/**
 * The point arrays for every rendered window, keyed {@code date:targetType}.
 *
 * <p><b>Keyed, not positional, and that is a correctness choice rather than a convenience.</b>
 * The shell already addresses a window by exactly this key ({@code card.key},
 * {@code revealWindow(key)}), and the two index spaces genuinely diverge:
 * {@code buildWindowCards} drops travel days before building its list, {@code buildPaneItems}
 * folds away-day runs into single rows, and {@code selectUpcomingEvents} re-indexes the whole
 * array the moment a window passes mid-session. A positional array would also hand every caller
 * an integer that looks exactly like the {@code win} argument the kernel hosts take — see
 * {@link POINT_SCORE_INDEX} for what passing it paints.
 *
 * <p>Separate from the catalogue because the two are different shapes for different consumers,
 * not because they change on different beats — they recompute together. P3's region labels and
 * P4's dark-sky filter want the whole catalogue; only the kernel wants points.
 *
 * @param {Array<object>} spots spots from {@link buildHeatSpots}
 * @param {Array<{date: string, targetType: string}>} renderedEvents the windows, in render order
 * @returns {Map<string, Array<object>>} window key → its points
 */
export function buildHeatPointSets(spots, renderedEvents) {
  const sets = new Map();
  const events = Array.isArray(renderedEvents) ? renderedEvents : [];
  events.forEach((event, index) => {
    if (!event || !event.date || !event.targetType) return;
    sets.set(windowKey(event.date, event.targetType), heatPointsFor(spots, index));
  });
  return sets;
}
