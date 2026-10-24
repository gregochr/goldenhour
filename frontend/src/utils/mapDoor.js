// The Plan->Map door's producer side (overlay convergence, `docs/engineering/overlay-convergence-plan.md`
// section 3 C1): turns whatever a Plan-tab producer hands `onShowOnMap` into ONE normalised trigger,
// and a normalised trigger into the structured `source:'plan'` door `App.openMapTabFromPlan` sends -
// carrying every field the overlay's seven triggers carried. Pure; no React.
//
// Overlay convergence C5 retired the overlay's renderer; its pure RESOLUTION (which spot a door
// opens, which spot set it narrows to, what it is called) lives here as `resolveDoor`.
import { DARK_SKY_THRESHOLD } from './darkSky.js';
import { NEIGHBOUR_NAMED_FLOOR } from './windowFirstCards.js';

/** Formats an ISO datetime's clock time as "HH:MM" (local London), or '' when absent. */
function formatClock(iso) {
  if (!iso) return '';
  const d = new Date(iso.endsWith('Z') ? iso : `${iso}Z`);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleTimeString('en-GB', {
    hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Europe/London',
  });
}

/** "Today" / "Tomorrow" / "Sat" for a card lead. */
function dayLabel(dateStr, todayStr, tomorrowStr) {
  if (dateStr === todayStr) return 'Today';
  if (dateStr === tomorrowStr) return 'Tomorrow';
  return new Date(`${dateStr}T12:00:00Z`).toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
}

/** The lower-case event word for a headline ("sunrise", "sunset"). */
function eventWord(eventType) {
  return eventType === 'SUNRISE' ? 'sunrise' : eventType === 'SUNSET' ? 'sunset' : (eventType || '').toLowerCase();
}

/** The briefing (batch) score for a location on a date+event, or null. Keys end with |date|event|name. */
function briefingScoreFor(briefingScores, loc, date, eventType) {
  if (!briefingScores || briefingScores.size === 0) return null;
  const suffix = `|${date}|${eventType}|${loc.name}`;
  for (const [key, val] of briefingScores) {
    if (key.endsWith(suffix)) return val;
  }
  return null;
}

/** The key {@link outlierKeysOf} and {@link resolveDoor} share: one slot, by name, date and event. */
function outlierKey(locationName, date, eventType) {
  return `${locationName}|${date}|${eventType}`;
}

/**
 * The served briefing's neighbour-outlier marks, as a Set of `name|date|targetType` keys, for
 * {@link resolveDoor}'s `ctx.outlierKeys`. The flag exists only on the served briefing's slots
 * (`BriefingSlot.neighbourOutlier`, `TRUE` or absent, set at serve time by the neighbour guard);
 * `GET /api/briefing/evaluate/scores`, which `briefingScores` comes from, carries no such flag.
 * Keyed by location NAME because the door resolves spots by name. A filter over a served flag.
 *
 * @param {?{days: ?Array}} briefing the served briefing (`days -> eventSummaries -> regions -> slots`)
 * @returns {Set<string>} possibly empty; never null
 */
export function outlierKeysOf(briefing) {
  const keys = new Set();
  for (const day of briefing?.days || []) {
    for (const es of day?.eventSummaries || []) {
      for (const region of es?.regions || []) {
        for (const slot of region?.slots || []) {
          if (slot?.neighbourOutlier === true && slot.locationName) {
            keys.add(outlierKey(slot.locationName, day.date, es.targetType));
          }
        }
      }
    }
  }
  return keys;
}

/** Star rating for a location on a date+event: briefing score wins, else the forecast rating. */
function ratingFor(loc, date, eventType, briefingScores) {
  // The daylight High-water row is never a door's window and carries no light score; without this the
  // `: sunset` fallback below would hand it the sunset's.
  if (eventType === 'HIGH_WATER') return null;
  const bs = briefingScoreFor(briefingScores, loc, date, eventType);
  if (bs?.rating != null) return bs.rating;
  const day = loc.forecastsByDate?.get?.(date);
  const forecast = eventType === 'SUNRISE' ? day?.sunrise : day?.sunset;
  return forecast?.rating ?? null;
}

/** The solar event instant a location serves for a date+event, or null. */
function solarTimeFor(loc, date, eventType) {
  if (eventType === 'HIGH_WATER') return null;
  const day = loc.forecastsByDate?.get?.(date);
  const forecast = eventType === 'SUNRISE' ? day?.sunrise : day?.sunset;
  return forecast?.solarEventTime ?? null;
}

/**
 * The spots of a roster whose {@code name} is in {@code names} — the one name-to-spot join the
 * Map's doors and the topic chips' rings share (hot-topics-by-day-plan §1.1: M1 extends this
 * joiner rather than writing a second). Names are compared byte-identically, never normalised
 * ({@code heatSpots.js} records why); a name with no spot in the roster (a disabled place) simply
 * does not join.
 *
 * @param {?Array<{name: string}>} spots the roster to join against
 * @param {?Array<string>} names the served {@code locationNames}
 * @returns {Array<object>} the roster's spots named in {@code names}, in roster order; empty when
 *          {@code names} is empty
 */
export function spotsNamed(spots, names) {
  const wanted = new Set(Array.isArray(names) ? names : []);
  if (wanted.size === 0) return [];
  return (Array.isArray(spots) ? spots : []).filter((spot) => wanted.has(spot?.name));
}

/**
 * Resolves a normalised trigger into what the door needs: a headline (`title`, `subLine`), the
 * window and spot it lands on (`handoff`), and the spot set it narrows to (`focus`). Derived from
 * data already on the client (locations, forecasts, briefing scores); no new data.
 *
 * @param {Object} trigger  normalised trigger ({@link normalizeMapTrigger}):
 *   { kind: 'region'|'event'|'location'|'coming-up'|'aurora', region?, locationName?, filterAction?,
 *     darkSky?, label?, date, eventType }
 * @param {Object} ctx  { locations, briefingScores, todayStr, tomorrowStr, outlierKeys? } -
 *   `outlierKeys` ({@link outlierKeysOf}) is absent on a caller with no briefing, which reads as
 *   none marked.
 *
 * <p>⚠️ The single-region branch never NAMES a neighbour outlier, mirroring
 * `PlanWindowProjector.topSlot` (and the client twin `windowFirstCards.bestReachOf`): a marked spot
 * keeps its star but is skipped (the mark alone decides - see the in-body note on why it never depends
 * on the local rating); if one was skipped and the next rated spot is below
 * {@link NEIGHBOUR_NAMED_FLOOR} (or none is rated) NO spot is named and the door lands on the region
 * alone. The mark must come from the served briefing, not `briefingScores`, which carries no flag.
 * @returns {{title: ?string, subLine: ?string, focus: ?{points: number[][], names?: string[]},
 *   handoff: Object}}
 */
export function resolveDoor(trigger, ctx) {
  const {
    locations = [], briefingScores = new Map(), todayStr, tomorrowStr, outlierKeys = null,
  } = ctx;
  const { date, eventType } = trigger;
  const enabled = locations.filter((l) => l.enabled !== false && l.lat != null && l.lon != null);
  const dl = date ? dayLabel(date, todayStr, tomorrowStr) : '';

  // ── Aurora - the alert banner's destination ──
  // ⚠️ It claims LESS than every other trigger here, deliberately. `ratingFor` and `solarTimeFor`
  // both resolve a non-SUNRISE event to the SUNSET forecast, so asking them about an AURORA trigger
  // would return that evening's sunset rating and sunset clock time dressed as aurora facts. There
  // is no star rating for an aurora and no solar time for one, so: no time, and no count of the
  // roster (§6 - a count of our own locations is not a fact about tonight).
  if (trigger.kind === 'aurora') {
    return {
      title: 'Aurora tonight',
      subLine: null,
      // No fit-bounds: the pins that matter are wherever it is dark and clear, which this function
      // cannot know.
      focus: null,
      handoff: { eventType: 'AURORA', date },
    };
  }

  // ── Coming up (D8) - a chronology card's action: filter the map and fit to the matching pins ──
  // Three cases, decided by the card's door kind: `filterAction` (a `locationType`, e.g. `SEASCAPE`
  // for coastal spots), `darkSky` (the Bortle-class toggle, which has no `locationType` of its own),
  // or NEITHER - a plan chip, which filters nothing and shows every visible location (plan §11.26).
  // The first two are mutually exclusive. The first two name their spots (`focus.names`, a
  // temporary named lens on the tab); "every spot" is NOT a selection, so the unfiltered arm passes
  // `focus: { points }` with NO `names`, the shape the `event` all-regions arm uses below.
  // The `date` IS carried, deliberately - this branch never calls `ratingFor`/`solarTimeFor` for it,
  // so a Coming-up date past Plan's four-day horizon cannot dress "no data" as "stand down".
  if (trigger.kind === 'coming-up') {
    const unfiltered = !trigger.darkSky && !trigger.filterAction;
    let matches;
    if (trigger.darkSky) {
      matches = enabled.filter((l) => l.bortleClass != null && l.bortleClass <= DARK_SKY_THRESHOLD);
    } else if (trigger.filterAction) {
      matches = enabled.filter((l) => (l.locationType || []).includes(trigger.filterAction));
    } else {
      matches = enabled;
    }
    const points = matches.map((l) => [l.lat, l.lon]);
    const regions = new Set(matches.map((l) => l.regionName).filter(Boolean));
    return {
      title: trigger.label || (trigger.darkSky ? 'Dark-sky spots' : trigger.filterAction) || 'Coming up',
      subLine: regions.size > 0 ? `${regions.size} ${regions.size === 1 ? 'region' : 'regions'}` : null,
      focus: points.length > 0
        ? { points, ...(unfiltered ? {} : { names: matches.map((l) => l.name) }) }
        : null,
      handoff: { filterAction: trigger.darkSky ? null : trigger.filterAction, darkSky: !!trigger.darkSky, date },
    };
  }

  // ── Location - a specific spot ──
  if (trigger.kind === 'location') {
    const loc = enabled.find((l) => l.name === trigger.locationName);
    const time = formatClock(loc ? solarTimeFor(loc, date, eventType) : null);
    return {
      title: trigger.locationName,
      subLine: [dl, eventWord(eventType), time && `· ${time}`].filter(Boolean).join(' '),
      focus: null,
      handoff: { eventType, locationName: trigger.locationName, date },
    };
  }

  // ── Region / Event - gather the pins, then decide single vs multi ──
  let candidates;
  let titleRegion = trigger.region || null;
  if (trigger.kind === 'region' && trigger.region) {
    candidates = enabled.filter((l) => l.regionName === trigger.region);
  } else {
    candidates = enabled; // event trigger: every region
  }

  // `trigger.locationNames`, when present, restricts to exact qualifying spots (elevated / coastal
  // / dark-sky ...) rather than the whole region/event pool. Producer-less today (the Hot topics
  // door that set it was deleted in the Coming up redesign's P6, `coming-up-plan.md` D7); kept
  // because `kind:'event'` shares this code path below the region/event fork, so a future producer
  // has somewhere to plug in without a schema change.
  const qualifying = trigger.locationNames && trigger.locationNames.length
    ? new Set(trigger.locationNames)
    : null;
  if (qualifying) {
    const restricted = spotsNamed(candidates, trigger.locationNames);
    if (restricted.length > 0) candidates = restricted;
  }

  const rated = candidates
    .map((l) => ({ loc: l, rating: ratingFor(l, date, eventType, briefingScores) }))
    .filter((r) => r.rating != null);
  const pool = rated.length > 0 ? rated : candidates.map((l) => ({ loc: l, rating: null }));
  const regionsInvolved = new Set(pool.map((r) => r.loc.regionName).filter(Boolean));
  const time = formatClock(pool.length > 0 ? solarTimeFor(pool[0].loc, date, eventType) : null);

  const filterAction = trigger.filterAction ?? null;
  const qualifyingNames = qualifying ? pool.map((r) => r.loc.name) : null;

  // A region trigger with several qualifying spots named -> show just those, fit to bounds.
  if (qualifying && pool.length > 1) {
    const points = pool.map((r) => [r.loc.lat, r.loc.lon]);
    return {
      title: titleRegion || trigger.label || 'On the map',
      subLine: [trigger.label, dl].filter(Boolean).join(' · ') || null,
      focus: { points, names: qualifyingNames },
      handoff: { eventType, date, filterAction },
    };
  }

  // Multi-region -> fit to all rated pins, no spot opened.
  if (!titleRegion && regionsInvolved.size > 1) {
    const points = pool.map((r) => [r.loc.lat, r.loc.lon]);
    return {
      title: `${dl} ${eventWord(eventType)}`.trim(),
      subLine: [`${regionsInvolved.size} regions`, time && `· ${time}`].filter(Boolean).join(' '),
      focus: { points },
      handoff: { eventType, date },
    };
  }

  // Single region -> focus it and open the top-rated location, unless it is a neighbour outlier.
  // ⚠️ The served mark is AUTHORITATIVE and is tested on its own key, never on the local rating:
  // `briefingScores` (the scores endpoint) and the served briefing can disagree in time (initial
  // load, a failed or lagging scores fetch), and a marked place with no local rating must still
  // not be named. A marked place the scores endpoint has not rated may also be absent from `pool`
  // (rated spots only), so `skipped` looks at every candidate, not just the pool.
  const marked = (loc) => !!outlierKeys?.has(outlierKey(loc.name, date, eventType));
  const best = pool.reduce((b, r) => (b == null || (r.rating ?? -1) > (b.rating ?? -1) ? r : b), null);
  const bestUnmarked = pool.reduce(
    (b, r) => (marked(r.loc) || (b != null && (r.rating ?? -1) <= (b.rating ?? -1)) ? b : r),
    null,
  );
  // An unrated marked place counts as skipped (its served rating was at least the guard's minimum,
  // so it would have outranked anything the floor rejects); a rated one only when it ties or beats
  // the best unmarked spot.
  const skipped = candidates.some((l) => marked(l) && (
    ratingFor(l, date, eventType, briefingScores) == null
    || ratingFor(l, date, eventType, briefingScores) >= (bestUnmarked?.rating ?? -1)
  ));
  const top = skipped && (bestUnmarked?.rating == null || bestUnmarked.rating < NEIGHBOUR_NAMED_FLOOR)
    ? null
    : bestUnmarked;
  if (!titleRegion) titleRegion = best?.loc.regionName ?? '';
  return {
    title: titleRegion || (top?.loc.name ?? 'On the map'),
    subLine: trigger.label
      ? [trigger.label, dl].filter(Boolean).join(' · ')
      : [dl, eventWord(eventType), time && `· ${time}`].filter(Boolean).join(' '),
    // A single qualifying spot still restricts the map's markers to just it (no points -> the
    // location handoff below selects it); non-topic single-region drilldowns don't restrict.
    focus: qualifyingNames ? { names: qualifyingNames } : null,
    // A top location opens its callout; without one, fall back to fitting the region's pins.
    // (Not both - they'd race the map camera.)
    handoff: top
      ? { eventType, locationName: top.loc.name, date, filterAction }
      : { eventType, region: titleRegion, date, filterAction },
  };
}

/**
 * Turns whatever `onShowOnMap` was called with into a normalised trigger object for
 * {@link resolveDoor}. Extracted from `App.jsx`'s former `handleShowOnMap` (removed in overlay convergence C4;
 * found untested by adversarial review, P3b) so the branch ORDER — the part that actually matters — is a unit,
 * not inline glue nobody can reach with a fixture.
 *
 * <h2>Order is the whole point</h2>
 *
 * <p>Every `else if` here is checked against the shape of `dateOrHandoff`, and it used to matter
 * that the `kind:'coming-up'` check (D8's map channel) ran BEFORE the generic `filterAction`-bearing
 * check that used to become `kind:'topic'` — both objects carried a `filterAction`, and getting the
 * order wrong would have silently turned a coastal/dark-sky action into a `kind:'topic'` trigger
 * instead. P6 (`docs/engineering/coming-up-plan.md` D7) deleted `kind:'topic'` and its only caller,
 * `HotTopicStrip`, along with it, so that hazard is gone rather than merely ordered around — the
 * history is kept here because the next new trigger kind should reason about ordering the same way.
 *
 * @param {*}        dateOrHandoff either a plain date string, or a handoff object naming its own
 *                                 `kind`/`filterAction`/`region`
 * @param {?string}  eventType    SUNRISE/SUNSET, for the plain-date-string call shapes
 * @param {?string}  locationName a specific location, for the location-drilldown call shape
 * @returns {object} a trigger for {@link resolveDoor}
 */
export function normalizeMapTrigger(dateOrHandoff, eventType, locationName = null) {
  // First, because it is the one caller that names its own kind. Without this branch the object
  // falls past `filterAction` and `region` into the final `else` and becomes an `event` trigger
  // whose `date` is the whole object - a door to a night that does not exist.
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.kind === 'aurora') {
    return { kind: 'aurora', date: dateOrHandoff.date };
  }
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.kind === 'coming-up') {
    // The Coming up chronology's own map channel (D8) — named explicitly. It used to be checked
    // ahead of a generic `filterAction` branch that became `kind:'topic'`, since both shapes carried
    // a `filterAction`; P6 deleted that branch and its only caller (`HotTopicStrip`), so `coming-up`
    // is now the sole producer of a bare `filterAction` handoff.
    return {
      kind: 'coming-up',
      filterAction: dateOrHandoff.filterAction ?? null,
      darkSky: !!dateOrHandoff.darkSky,
      label: dateOrHandoff.label ?? null,
      date: dateOrHandoff.date,
    };
  }
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.kind === 'event') {
    // The object form of the event door, for the one producer that needs a field the positional
    // `(date, eventType)` call cannot carry: `topicType`, the Coming up weather chip's hot-topic type,
    // which the Map selects on arrival. ABSENT (not null) when not asked, so the shape is exactly the
    // positional call's.
    return {
      kind: 'event',
      date: dateOrHandoff.date,
      eventType: dateOrHandoff.eventType,
      ...(dateOrHandoff.topicType ? { topicType: dateOrHandoff.topicType } : {}),
    };
  }
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.region) {
    // A region trigger, optionally carrying a hot topic's qualifying locations + label so the
    // door narrows to just those pins (elevated inversion spots, coastal tide spots, …).
    return {
      kind: 'region',
      region: dateOrHandoff.region,
      date: dateOrHandoff.date,
      eventType: dateOrHandoff.eventType,
      locationNames: dateOrHandoff.locationNames ?? null,
      label: dateOrHandoff.label ?? null,
      filterAction: dateOrHandoff.filterAction ?? null,
    };
  }
  if (locationName) {
    return { kind: 'location', locationName, date: dateOrHandoff, eventType };
  }
  return { kind: 'event', date: dateOrHandoff, eventType };
}

/**
 * The breadcrumb's headline for a trigger - a title and sub-line, which the
 * Map tab's `MapBreadcrumb` prints as `title · caption` ("Aurora tonight", "Tomorrow sunrise ·
 * 06:12"). Derived through {@link resolveDoor} so the two surfaces can never name a door
 * differently. (The retired overlay's "tap a pin" prompt is deliberately not carried: it was
 * an instruction to a modal's reader, and the breadcrumb names its lens by clauses instead.)
 *
 * @param {object} trigger a normalised trigger ({@link normalizeMapTrigger})
 * @param {object} ctx     `resolveDoor`'s context ({locations, briefingScores, todayStr, tomorrowStr})
 * @returns {{title: ?string, caption: ?string}}
 */
export function doorHeadline(trigger, ctx) {
  const resolved = resolveDoor(trigger, ctx);
  return { title: resolved.title || null, caption: resolved.subLine || null };
}

/**
 * A normalised trigger as the structured door `App.openMapTabFromPlan` sends. The location,
 * region, event and filter resolution is `resolveDoor`'s own (the top-rated spot of a single
 * region, the exact spot set of a coming-up card), read off its `handoff`/`focus` rather than
 * re-decided here. A night trigger names its night (`night`), a spot-set trigger its names
 * (`focusNames`, a TEMPORARY named lens on the tab) and its points (`focusPoints`, to fit).
 * Fields that do not apply are ABSENT, not null, so a plain door's payload is unchanged. The
 * wrapper-supplied lens fields (`minRating`, `limitMinutes`) and `inPlace` ride through untouched.
 *
 * @param {object} trigger a normalised trigger ({@link normalizeMapTrigger})
 * @param {object} ctx     `resolveDoor`'s context
 * @returns {object} a door for `openMapTabFromPlan`
 */
export function doorFromTrigger(trigger, ctx) {
  const resolved = resolveDoor(trigger, ctx);
  const h = resolved.handoff || {};
  const { title, caption } = doorHeadline(trigger, ctx);
  const door = {
    date: trigger.date,
    targetType: h.eventType ?? null,
    // A REGION door keeps its region even when the resolution found a top spot to
    // open (`handoff` then carries only `locationName`): the Map tab frames the region AND selects
    // the spot, so an away region is not stranded outside scope behind one callout (C4).
    region: h.region ?? (trigger.kind === 'region' ? trigger.region ?? null : null),
    locationName: h.locationName ?? null,
    title,
    caption,
  };
  if (trigger.kind === 'aurora') {
    door.night = { kind: 'aurora', date: trigger.date };
  }
  if (h.filterAction) door.filterAction = h.filterAction;
  // The Coming up weather chip's topic, for the Map to select on arrival (`kind:'event'` only).
  if (trigger.kind === 'event' && trigger.topicType) door.topicType = trigger.topicType;
  if (h.darkSky) door.darkSky = true;
  if (resolved.focus?.names?.length) door.focusNames = resolved.focus.names;
  if (resolved.focus?.points?.length) door.focusPoints = resolved.focus.points;
  // The wrapper-supplied lens fields ride through untouched (`WindowFirstShell.openMapTab` ->
  // `openMapDoor` spreads the Plan's rating floor and reach tier, and `inPlace`, onto whatever it is
  // handed, a trigger included): this function resolves the door's own fields and adds nothing of
  // its own to the lens. Copied when the trigger carries the key at all, otherwise ABSENT.
  // `from` (C4): the tab the door was pressed on, for the breadcrumb's return.
  ['minRating', 'limitMinutes', 'inPlace', 'from'].forEach((key) => {
    if (trigger[key] !== undefined) door[key] = trigger[key];
  });
  return door;
}
