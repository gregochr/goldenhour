import { isEventPast, isPoorSlot, formatTime, getEventTime } from './briefingDisplay.js';

/**
 * "Close to home" derivations — the Plan tab's low-risk *local* decision, sitting between the
 * headline picks ("what should I do") and the hot topics ("why").
 *
 * <p>Two rules carry the whole feature and are worth not re-deriving:
 *
 * <p><b>Proximity gates, region never does.</b> Candidates are gated on straight-line distance
 * from the user's saved home postcode; a location's region is *displayed* but never *filtered on*.
 * An earlier design pinned the user's home region instead, and the boundary bug is not theoretical:
 * St Mary's Lighthouse — one of the strongest spots in the North East — sits in southern
 * Northumberland, so a Tyne & Wear user would have had their best nearby location filtered out.
 * This also means no "home region" setting is needed, and none should be added.
 *
 * <p><b>Distance gates, rating sorts.</b> Distance is shown on every card, so it carries no weight
 * in the ordering and no tiebreak — the nearest spot routinely ranks third. Ties between equal
 * integer ratings break on *soonest*, then name, both of which are neutral with respect to
 * distance.
 */

/** Gate radius from the home postcode, in miles. A constant today; could become a settings slider. */
export const RADIUS_MILES = 22;

/** Cards shown in part 2. Rating-ordered, so this is a "top N", not a sample. */
export const MAX_NEARBY_CARDS = 4;

/** Forecast horizon part 2 draws from, in distinct forecast days. */
export const HORIZON_DAYS = 3;

/** Mean Earth radius in miles. Units are miles throughout this module — never mix in km. */
const EARTH_RADIUS_MILES = 3958.8;

/**
 * Great-circle distance between two points, in miles.
 *
 * @param {number} lat1 first latitude
 * @param {number} lon1 first longitude
 * @param {number} lat2 second latitude
 * @param {number} lon2 second longitude
 * @returns {number|null} distance in miles, or null when any coordinate is missing
 */
export function distanceMiles(lat1, lon1, lat2, lon2) {
  const coords = [lat1, lon1, lat2, lon2];
  if (coords.some((v) => v == null || Number.isNaN(Number(v)))) return null;
  const toRad = (deg) => (deg * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2
    + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_MILES * Math.asin(Math.min(1, Math.sqrt(a)));
}

/**
 * The tide fact worth a chip on a single location's card, most notable first.
 *
 * @param {Object} slot briefing slot (tide fields are unwrapped onto it)
 * @returns {string|null} a short lowercase label, or null when there's nothing to say
 */
export function slotTideLabel(slot) {
  if (!slot) return null;
  if (slot.isKingTide) return 'king tide';
  if (slot.isSpringTide) return 'spring tide';
  if (slot.tideAligned && slot.tideState) return `${String(slot.tideState).toLowerCase()} tide`;
  return null;
}

/** Sunrise ↑ / sunset ↓ arrow used on the day chip and the next-window line. */
function eventArrow(targetType) {
  return targetType === 'SUNRISE' ? '↑' : '↓';
}

/** "sunrise" / "sunset". */
function eventWord(targetType) {
  return targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
}

/** "Today" / "Tomorrow" / "Tue 28". */
function shortDayLabel(date, todayStr, tomorrowStr) {
  if (date === todayStr) return 'Today';
  if (date === tomorrowStr) return 'Tomorrow';
  const d = new Date(`${date}T12:00:00Z`);
  if (Number.isNaN(d.getTime())) return date;
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', timeZone: 'UTC' });
}

/**
 * The phrase the breadcrumb's reason ends on ("…worth the trip tonight").
 * Deliberately vague past tomorrow — "that day" beats naming a weekday twice in one row.
 */
function whenWord(date, targetType, todayStr, tomorrowStr) {
  if (date === todayStr) return targetType === 'SUNRISE' ? 'this morning' : 'tonight';
  if (date === tomorrowStr) return 'tomorrow';
  return 'that day';
}

/** "This morning's sunrise" / "Tonight's sunset" / "Tomorrow's sunrise" / "Tuesday's sunset". */
function eventHeadline(date, targetType, todayStr, tomorrowStr) {
  if (date === todayStr) {
    return targetType === 'SUNRISE' ? "This morning's sunrise" : "Tonight's sunset";
  }
  if (date === tomorrowStr) return `Tomorrow's ${eventWord(targetType)}`;
  const d = new Date(`${date}T12:00:00Z`);
  const day = Number.isNaN(d.getTime())
    ? date
    : d.toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
  return `${day}'s ${eventWord(targetType)}`;
}

/**
 * Chronological sort key for a candidate — keyed on the **solar event**, never on the slot's own
 * `solarEventTime`.
 *
 * <p>That distinction is load-bearing. `solarEventTime` is computed per location from its own
 * lat/lon, so two spots at the *same* sunrise differ by a few minutes and never compare equal.
 * Keying on it therefore made every "same event" tiebreak unreachable, and the block would name
 * whichever nearby location the sun happened to reach first — pointing at a 3★ spot directly
 * above a lead card showing a 5★ one, three minutes apart. Three minutes is not a different
 * window; the event is the unit.
 *
 * <p>`targetType` sorts SUNRISE before SUNSET lexicographically, which is the order they occur in,
 * so a plain string compare orders the whole horizon correctly.
 */
function eventKey(candidate) {
  return `${candidate.date}|${candidate.targetType}`;
}

/**
 * The star rating for a slot: the SSE/batch evaluation cache wins (fresher), then the rating
 * already baked into the briefing payload. Mirrors {@code HeatmapGrid}'s {@code mergedScore}.
 */
function resolveRating(slot, regionName, date, targetType, evaluationScores) {
  if (regionName && evaluationScores?.get) {
    const sse = evaluationScores.get(`${regionName}|${date}|${targetType}|${slot.locationName}`);
    if (sse?.rating != null) return sse.rating;
  }
  return slot.claudeRating ?? null;
}

/**
 * Every briefed slot within the radius across the horizon, flattened — both the qualifying ones
 * (part 2's candidates) and the poor ones (which part 1 needs to explain a "stay in").
 *
 * @param {Object} opts see {@link buildCloseToHome}
 * @returns {Array<Object>} flat candidate records, briefing order preserved
 */
export function collectNearbySlots({
  briefingDays = [],
  locations = [],
  homeCoords = null,
  evaluationScores = null,
  radiusMiles = RADIUS_MILES,
  horizonDays = HORIZON_DAYS,
} = {}) {
  if (homeCoords?.lat == null || homeCoords?.lon == null) return [];

  const byName = new Map();
  for (const loc of locations) {
    if (loc?.name && loc.lat != null && loc.lon != null) byName.set(loc.name, loc);
  }
  if (byName.size === 0) return [];

  const out = [];
  const dates = new Set();

  for (const day of briefingDays) {
    const events = (day.eventSummaries || []).filter((es) => !isEventPast(es));
    if (events.length === 0) continue;
    if (!dates.has(day.date)) {
      if (dates.size >= horizonDays) break;
      dates.add(day.date);
    }
    for (const es of events) {
      // Unregioned slots count too: a location without a region is still within reach. It carries
      // no briefing region name, so it can't be looked up in the evaluation cache — its rating
      // falls back to the payload's own.
      const groups = [
        ...(es.regions || []).map((r) => ({ regionName: r.regionName, slots: r.slots || [] })),
        { regionName: null, slots: es.unregioned || [] },
      ];
      for (const { regionName, slots } of groups) {
        for (const slot of slots) {
          const loc = byName.get(slot.locationName);
          if (!loc) continue;
          const miles = distanceMiles(homeCoords.lat, homeCoords.lon, loc.lat, loc.lon);
          if (miles == null || miles > radiusMiles) continue;
          out.push({
            locationName: slot.locationName,
            // The location's *actual* region — the "St Mary's Lighthouse / Northumberland"
            // honesty label. Shown, never filtered on.
            regionName: loc.regionName ?? regionName ?? null,
            date: day.date,
            targetType: es.targetType,
            eventTime: slot.solarEventTime ?? null,
            distanceMiles: miles,
            rating: resolveRating(slot, regionName, day.date, es.targetType, evaluationScores),
            poor: isPoorSlot(slot),
            standdownReason: slot.standdownReason ?? null,
            headline: slot.claudeHeadline ?? null,
            summary: slot.claudeSummary ?? null,
            tideLabel: slotTideLabel(slot),
          });
        }
      }
    }
  }
  return out;
}

/** A candidate belongs on a card only if it was rated and is not a stand-down. */
function qualifies(candidate) {
  return candidate.rating != null && !candidate.poor;
}

/**
 * Ranks the qualifying candidates: one row per location (its best slot), rating DESC, then
 * soonest, then name. See the module note on why distance is absent from this comparator.
 */
function rankCandidates(candidates, maxCards) {
  const best = new Map();
  for (const c of candidates) {
    if (!qualifies(c)) continue;
    const prev = best.get(c.locationName);
    const better = !prev
      || c.rating > prev.rating
      || (c.rating === prev.rating && eventKey(c) < eventKey(prev));
    if (better) best.set(c.locationName, c);
  }
  return [...best.values()]
    .sort((a, b) => (b.rating - a.rating)
      || eventKey(a).localeCompare(eventKey(b))
      || a.locationName.localeCompare(b.locationName))
    .slice(0, maxCards);
}

/** The very next solar event still ahead of us, or null. */
function findNextEvent(briefingDays) {
  for (const day of briefingDays || []) {
    for (const es of day.eventSummaries || []) {
      if (!isEventPast(es)) return { date: day.date, targetType: es.targetType, es };
    }
  }
  return null;
}

/** The most frequent stand-down reason among a set of candidates, or null. */
function dominantReason(candidates) {
  const counts = new Map();
  for (const c of candidates) {
    if (!c.standdownReason) continue;
    counts.set(c.standdownReason, (counts.get(c.standdownReason) ?? 0) + 1);
  }
  let top = null;
  for (const [reason, n] of counts) {
    if (!top || n > top.n) top = { reason, n };
  }
  return top?.reason ?? null;
}

/**
 * Builds the whole "Close to home" view model, or null when the block should not render at all
 * (no saved home postcode, or nothing at all within the radius — a permanently empty block is
 * noise, not honesty).
 *
 * <p>The block is <b>not</b> tied to the day selected in the briefing summary: part 1 always
 * answers "should I bother at the very next event?", and part 2 always spans the horizon.
 *
 * @param {Object}   opts
 * @param {Array}    opts.briefingDays      briefing.days
 * @param {Array}    opts.locations         enabled locations (need name, lat, lon, regionName)
 * @param {?Object}  opts.homeCoords        {lat, lon} resolved from the saved postcode, or null
 * @param {?Map}     opts.evaluationScores  "region|date|targetType|location" → {rating, …}
 * @param {?Map}     opts.driveMap          location name → drive minutes (per user)
 * @param {string}   opts.todayStr          today's ISO date
 * @param {string}   opts.tomorrowStr       tomorrow's ISO date
 * @param {number}   [opts.radiusMiles]     gate radius
 * @param {number}   [opts.maxCards]        cards in part 2
 * @param {number}   [opts.horizonDays]     forecast days part 2 spans
 * @returns {?Object} {radiusMiles, horizonDays, cards, multiDay, breadcrumb} or null
 */
export function buildCloseToHome({
  briefingDays = [],
  locations = [],
  homeCoords = null,
  evaluationScores = null,
  driveMap = null,
  todayStr,
  tomorrowStr,
  radiusMiles = RADIUS_MILES,
  maxCards = MAX_NEARBY_CARDS,
  horizonDays = HORIZON_DAYS,
} = {}) {
  const nearby = collectNearbySlots({
    briefingDays, locations, homeCoords, evaluationScores, radiusMiles, horizonDays,
  });
  if (nearby.length === 0) return null;

  const driveOf = (name) => driveMap?.get?.(name) ?? null;
  const ranked = rankCandidates(nearby, maxCards);
  const cards = ranked.map((c, i) => ({
    key: `${c.locationName}|${c.date}|${c.targetType}`,
    locationName: c.locationName,
    regionName: c.regionName,
    rating: c.rating,
    date: c.date,
    targetType: c.targetType,
    eventTime: formatTime(c.eventTime),
    dayChip: `${eventArrow(c.targetType)} ${shortDayLabel(c.date, todayStr, tomorrowStr)} ${eventWord(c.targetType)}`,
    distanceMiles: Math.round(c.distanceMiles),
    driveMinutes: driveOf(c.locationName),
    tideLabel: c.tideLabel,
    lead: i === 0,
  }));
  // The day chip is pure repetition when every card lands on the same day — suppress it there.
  const multiDay = new Set(cards.map((c) => c.date)).size > 1;

  // ── Part 1: the next-event breadcrumb ──
  const next = findNextEvent(briefingDays);
  const forNextEvent = next
    ? nearby.filter((c) => c.date === next.date && c.targetType === next.targetType)
    : [];
  const nextQualifying = forNextEvent.filter(qualifies);
  const worthIt = nextQualifying.length > 0;
  const topLocal = nextQualifying
    .reduce((best, c) => (best == null || c.rating > best.rating ? c : best), null);

  let reason;
  if (!next) {
    reason = 'No upcoming sunrise or sunset to assess.';
  } else if (worthIt) {
    reason = topLocal.headline || topLocal.summary
      || `${topLocal.locationName} leads the local options at ${topLocal.rating}★.`;
  } else {
    const when = whenWord(next.date, next.targetType, todayStr, tomorrowStr);
    const cause = dominantReason(forNextEvent);
    reason = cause
      ? `${cause} nearby — nothing within reach is worth the trip ${when}.`
      : `Nothing within reach is worth the trip ${when}.`;
  }

  // The soonest local window worth leaving the house for — the hope that keeps the user coming
  // back on a stay-in night. Chronological, best-rated within the same event. Deliberately does
  // NOT name a poor spot: an earlier iteration surfaced the best nearby location at 1.8★ on a bad
  // night, which undercut the stay-in verdict.
  // Soonest *event* first, then the best spot at that event, then name for determinism. The
  // rating term is only reachable because the primary key is the event, not the per-location
  // solar time — see eventKey.
  const window = nearby
    .filter(qualifies)
    .sort((a, b) => eventKey(a).localeCompare(eventKey(b))
      || (b.rating - a.rating)
      || a.locationName.localeCompare(b.locationName))[0] ?? null;

  return {
    radiusMiles,
    horizonDays,
    cards,
    multiDay,
    breadcrumb: {
      worthIt,
      eventLabel: next ? eventHeadline(next.date, next.targetType, todayStr, tomorrowStr) : null,
      // A nearby location's own solar time is the right one to show — it is the clock the user
      // will actually set. But the next event can have no in-radius slot at all (every nearby
      // spot configured sunset-only against a sunrise, or the honesty filter having cleared a
      // zero-coverage region), which would leave the row reading "Tonight's sunset" with no
      // time. Fall back to the event summary's own time rather than blank; it may belong to a
      // distant location and be off by minutes, which is plainly better than absent.
      eventTime: next ? formatTime(forNextEvent[0]?.eventTime ?? getEventTime(next.es)) : '',
      reason,
      nextWindow: window
        ? {
          locationName: window.locationName,
          rating: window.rating,
          date: window.date,
          targetType: window.targetType,
          detail: [
            `${shortDayLabel(window.date, todayStr, tomorrowStr)} ${eventWord(window.targetType)}`,
            formatTime(window.eventTime),
          ].filter(Boolean).join(' '),
          driveMinutes: driveOf(window.locationName),
        }
        : null,
    },
  };
}
