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
import { windowKey } from './windowKeys.js';

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

/** A score row's weather stand-down: a non-blank {@code triageReason}, served as a string or an object. */
function hasTriageReason(row) {
  const reason = row.triageReason;
  if (reason == null) return false;
  return typeof reason === 'string' ? reason.trim() !== '' : true;
}

/**
 * The stand-down reason a row carries, normalised the way {@link hasTriageReason} reads it: a
 * string trimmed, or an object's {@code name} / {@code reason} field. A reason that is present but
 * unreadable (an object with neither) is {@code GENERIC}, the served enum's own catch-all, so a
 * stood place never loses its reason to a shape. Null when the row is not a stand-down at all.
 *
 * @param {object} row a score row
 * @returns {?string} the reason (e.g. {@code HIGH_CLOUD}), or null
 */
function triageReasonOf(row) {
  if (!hasTriageReason(row)) return null;
  const raw = row.triageReason;
  if (typeof raw === 'string') return raw.trim();
  const named = raw.name ?? raw.reason;
  return typeof named === 'string' && named.trim() !== '' ? named.trim() : 'GENERIC';
}

/**
 * What a window knows about one place — the layer's five states, beside its score.
 *
 * <ul>
 *   <li>{@code rated} — the score row carries a valid rating;</li>
 *   <li>{@code stood} — a row exists, no rating, and a {@code triageReason}: the pipeline looked
 *   and stood it down, which is an answer, not a gap;</li>
 *   <li>{@code unrated} — a sky place with neither a rating nor a triage reason, a row with
 *   neither included and no row at all included: nobody has looked;</li>
 *   <li>{@code na} — not a sky subject (a wood, a wildlife hide): never rated by design, so never
 *   "not rated yet";</li>
 *   <li>{@code gated} — the server marked the place's row {@code gated: true}: it is outside the
 *   reader's sub-regions (sub-regions-plan D6). It is <b>its own state, never "unrated"</b>: it is in
 *   neither the colour pool, the hatch, the thin-sample count nor any "k of m". The state comes from
 *   the row's own marker and nothing else — never from the role, never from a membership test here
 *   (a place whose row carries no marker is simply not gated).</li>
 * </ul>
 *
 * @param {boolean} skySubject whether a sky verdict applies to the place at all
 * @param {?{rating: ?number, stood: boolean, gated: boolean}} entry the window's score row, or null
 *        for none
 * @returns {'rated'|'stood'|'unrated'|'na'|'gated'} the state
 */
function windowState(skySubject, entry) {
  // Before the sky-subject test: a gated wood is still outside the picks, and the marker drawn for it
  // must not depend on whether a sky verdict would have applied.
  if (entry && entry.gated) return 'gated';
  if (!skySubject) return 'na';
  if (entry && entry.rating != null) return 'rated';
  if (entry && entry.stood) return 'stood';
  return 'unrated';
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
 *   rating: ?number, triageReason: ?*}>} scoreRows raw {@code LocationEvaluationView} rows, id
 *   included; {@code triageReason} is what tells a stood-down place from an unrated one
 * @param {Array<{date: string, targetType: string}>} renderedEvents the windows, in render order
 * @param {?Set<*>} [allowedIds] the served {@code allowedLocationIds} as a Set, on a shaped body only: a
 *        location not in it is {@code gated} in every window, whatever its rows say (or whether it has any)
 * @returns {Array<{id: *, name: string, lat: number, lng: number, regionName: string, rid: string,
 *   skySubject: boolean, bortleClass: ?number, scores: Array<?number>,
 *   states: Array<'rated'|'stood'|'unrated'|'na'|'gated'>}>} one spot per joinable location, with each
 *   window's state beside its score
 */
export function buildHeatSpots(locations, scoreRows, renderedEvents, allowedIds = null) {
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
    // The rating plus whether the row is a weather stand-down (a triage reason) — one entry, so
    // the score and its state are read off the SAME row and cannot come from different indexes.
    const entry = {
      rating: validRating(row.rating),
      stood: hasTriageReason(row),
      reason: triageReasonOf(row),
      // The server's own marker (`gated: true` on a shaped Lite response); absent everywhere else.
      gated: row.gated === true,
    };
    if (row.locationId != null) putFirst(byId, `${row.locationId}|${tail}`, entry);
    if (row.locationName) putFirst(byName, `${row.locationName}|${tail}`, entry);
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
    const lookup = ({ date, targetType }) => {
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
    };
    // A non-sky place is looked up too, but only for its gated marker: its scores stay withheld.
    const rows = events.map(lookup);
    const found = rows.map((entry) => (skySubject ? entry : (entry && entry.gated ? entry : null)));
    // On a SHAPED body the server's served `allowedLocationIds` decides who is outside the picks, not the
    // rows: the shaper marks only rows that exist, and a wood, a hide or a place with no row in this window
    // would otherwise read allowed. A selection by a list the server decided, never a client rule.
    const outsidePicks = allowedIds instanceof Set && (location.id == null || !allowedIds.has(location.id));
    const scores = found.map((entry) => (!outsidePicks && entry && !entry.gated ? entry.rating : null));
    const states = found.map((entry) => (outsidePicks ? 'gated' : windowState(skySubject, entry)));
    // The stand-down reason beside each window's state (big-seas plan F3): null unless the window
    // state is `stood`, so a rated place can never carry one however its rows collided.
    const reasons = found.map((entry, i) => (states[i] === 'stood' ? entry.reason : null));
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
      // The served sub-region (S5a-2), for the picks chrome's counts and the region panel's outside rows.
      subRegionId: location.subRegionId ?? null,
      subRegionName: location.subRegionName ?? null,
      scores,
      // Beside each score, what the window knows about the place — see windowState.
      states,
      reasons,
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
 * The kernel-ready points for one window's UNRATED places — the ground the "not rated yet" hatch
 * is drawn around. The same shape as {@link heatPointsFor} (a one-element {@code r}, never read by
 * the mask), for a spot whose state in that window is exactly {@code unrated}: a stood-down place
 * was looked at, a wood or hide takes no part, a rated place is the field's.
 *
 * <p>⚠️ <b>A window for which NO spot is {@code rated} or {@code stood} yields nothing.</b> Without a
 * single piece of evidence for the window (an empty or failed scores response under a briefing that
 * still rates it) every sky place reads unrated, and the hatch would cover the whole map beneath a
 * card claiming a rating. We cannot say which places are unrated when we hold no evidence either
 * way. Every producer of unrated points goes through here, so the Plan and Map hosts agree.
 *
 * @param {Array<object>} spots spots from {@link buildHeatSpots}
 * @param {number} windowIndex which of the rendered windows to build points for
 * @returns {Array<{id: *, name: string, lat: number, lng: number, rid: string, r: number[]}>}
 *   the unrated places, for {@code drawGeo}/{@code drawTiles}' {@code unrated} option
 */
export function unratedPointsFor(spots, windowIndex) {
  const points = [];
  const list = Array.isArray(spots) ? spots : [];
  const hasEvidence = list.some((spot) => {
    const state = spot?.states?.[windowIndex];
    return state === 'rated' || state === 'stood';
  });
  if (!hasEvidence) return points;
  for (const spot of list) {
    if (spot?.states?.[windowIndex] !== 'unrated') continue;
    points.push({
      id: spot.id,
      name: spot.name,
      lat: spot.lat,
      lng: spot.lng,
      rid: spot.rid,
      r: [0],
    });
  }
  return points;
}

/**
 * The places a window stood down, with why — the ground the stand-down hatch is drawn around
 * (big-seas plan F3). A spot whose state in that window is exactly {@code stood}; every reason is
 * kept, {@code TIDE_MISALIGNED} included (the Map decides which of them it draws).
 *
 * <p>Unlike {@link unratedPointsFor} this carries no evidence gate: a stood place IS evidence.
 *
 * @param {Array<object>} spots spots from {@link buildHeatSpots}
 * @param {number} windowIndex which of the rendered windows to build the list for
 * @returns {Array<{id: *, name: string, lat: number, lng: number, reason: ?string, rid: string, key: string}>}
 */
export function stoodPointsFor(spots, windowIndex) {
  const list = [];
  for (const spot of Array.isArray(spots) ? spots : []) {
    if (spot?.states?.[windowIndex] !== 'stood') continue;
    list.push({
      id: spot.id,
      name: spot.name,
      lat: spot.lat,
      lng: spot.lng,
      reason: spot.reasons?.[windowIndex] ?? null,
      rid: spot.rid,
      key: heatSpotKey(spot),
    });
  }
  return list;
}

/**
 * The point arrays for every rendered window, keyed {@code date:targetType}.
 *
 * <p><b>Keyed, not positional, and that is a correctness choice rather than a convenience.</b>
 * The shell already addresses a window by exactly this key ({@code card.key},
 * {@code openWindow(key)}), and the two index spaces genuinely diverge:
 * {@code buildWindowCards} drops travel days before building its list, {@code buildPaneItems}
 * folds away-day runs into single items, and {@code selectUpcomingEvents} re-indexes the whole
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

/**
 * The unrated places for every rendered window, keyed {@code date:targetType} — the twin of
 * {@link buildHeatPointSets} for the "not rated yet" hatch, built over the same windows by the
 * same index so a window's hatch and its field can never come from different rows.
 *
 * @param {Array<object>} spots spots from {@link buildHeatSpots}
 * @param {Array<{date: string, targetType: string}>} renderedEvents the windows, in render order
 * @returns {Map<string, Array<object>>} window key → its unrated points
 */
export function buildUnratedPointSets(spots, renderedEvents) {
  const sets = new Map();
  const events = Array.isArray(renderedEvents) ? renderedEvents : [];
  events.forEach((event, index) => {
    if (!event || !event.date || !event.targetType) return;
    sets.set(windowKey(event.date, event.targetType), unratedPointsFor(spots, index));
  });
  return sets;
}

/**
 * The key a spot is joined on across the Map tab's own lists: the id when there is one, the name
 * otherwise, with a sigil so an id 5 and a name "5" never meet.
 *
 * @param {{id: *, name: ?string}} spot a spot or a point
 * @returns {string} the join key
 */
export function heatSpotKey(spot) {
  return spot.id != null ? `#${spot.id}` : `@${spot.name}`;
}

/**
 * What each window knows about every place, keyed {@code date:targetType}: the unrated points the
 * "not rated yet" hatch is drawn around, and each place's state by {@link heatSpotKey}. Built beside
 * {@link buildHeatPointSets} (same keys, same events) for the Map tab (thin-sample plan F4): the
 * hatch reads {@code unrated}, the pill's rated count reads {@code states} over the reader's scope
 * pool. A filter/map over the states {@link buildHeatSpots} already set; nothing is rated here.
 *
 * @param {Array<object>} spots spots from {@link buildHeatSpots}
 * @param {Array<{date: string, targetType: string}>} renderedEvents the windows, in render order
 * @returns {Map<string, {unrated: Array<object>, stood: Array<object>, states: Map<string, string>}>}
 *   window key to entry; {@code stood} is {@link stoodPointsFor}'s list (big-seas plan F3)
 */
export function buildHeatWindowStates(spots, renderedEvents) {
  const out = new Map();
  const events = Array.isArray(renderedEvents) ? renderedEvents : [];
  events.forEach((event, index) => {
    if (!event || !event.date || !event.targetType) return;
    const states = new Map();
    for (const spot of Array.isArray(spots) ? spots : []) {
      const state = spot?.states?.[index];
      if (state) states.set(heatSpotKey(spot), state);
    }
    out.set(windowKey(event.date, event.targetType), {
      unrated: unratedPointsFor(spots, index),
      stood: stoodPointsFor(spots, index),
      states,
    });
  });
  return out;
}

/**
 * Score rows for a set of windows, drawn from the locations' own forecast rows
 * ({@code forecastsByDate}) and from nothing else — the shape {@link buildHeatSpots} takes, built
 * for the Map tab's PAST windows (outcome-rating-plan.md §2 #3, O3c).
 *
 * <p>The scores endpoint's rows are the LATEST result for a slot, which for a passed slot may be one
 * that landed after the event; the forecast rows for a passed slot are served as of it (once the
 * as-of-event resolver, outcome-rating-plan.md §2 #4, lands on the forecast path). The Map's chips
 * and callout already read the forecast row alone on a past window, so the field must read the same
 * row or the two would disagree on one place's star. A location with no row for a window
 * contributes none; a row with no rating contributes one with a null rating (an answer — "looked,
 * not scored" — as {@link buildHeatSpots} reads it).
 *
 * <p>A map/filter over served rows: the already-licensed class, no rating derived.
 *
 * @param {Array<{id: *, name: string, forecastsByDate: ?Map<string, object>}>} locations
 * @param {Array<{date: string, targetType: string}>} windows
 * @returns {Array<{locationId: *, locationName: string, date: string, targetType: string,
 *   rating: ?number}>}
 */
export function forecastScoreRows(locations, windows) {
  const rows = [];
  const wanted = Array.isArray(windows) ? windows : [];
  for (const loc of Array.isArray(locations) ? locations : []) {
    const days = loc?.forecastsByDate;
    if (!(days instanceof Map)) continue;
    for (const { date, targetType } of wanted) {
      const slot = targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
      const row = days.get(date)?.[slot];
      if (!row) continue;
      rows.push({
        locationId: loc.id ?? null,
        locationName: loc.name ?? '',
        date,
        targetType,
        rating: row.rating ?? null,
        // Only when there is one, so a row that never carried a reason is shaped as it always was.
        ...(row.triageReason ? { triageReason: row.triageReason } : {}),
        // The server's gated marker rides through unchanged (a shaped Lite forecast row), so a past
        // window's gated place is its own state here too; absent on every other row.
        ...(row.gated === true ? { gated: true } : {}),
      });
    }
  }
  return rows;
}
