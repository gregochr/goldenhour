// Builds the map-overlay descriptor for a Plan-tab recommendation trigger.
//
// The overlay reuses MapView through the existing handoff seam (fly-to + popup for a single
// location, fit-to-pins for several) and preserves the region's Claude gloss as a footer band —
// all derived from data already on the client (locations, forecasts, briefing scores). No new data.

/**
 * Turns whatever `onShowOnMap` was called with into a normalised trigger object for
 * {@link buildMapOverlay}. Extracted from `App.jsx`'s `handleShowOnMap` (found untested by
 * adversarial review, P3b) so the branch ORDER — the part that actually matters — is a unit,
 * not inline glue nobody can reach with a fixture.
 *
 * <h2>Order is the whole point</h2>
 *
 * <p>Every `else if` here is checked against the shape of `dateOrHandoff`, and two branches
 * overlap on the SAME field: a `kind:'coming-up'` object (D8's map channel) and the generic
 * `filterAction`-bearing object that becomes `kind:'topic'` both carry a `filterAction`. The
 * `coming-up` check MUST run first, or a coastal/dark-sky action silently becomes a `kind:'topic'`
 * trigger instead — which happens to render an identical-looking overlay today (both branches share
 * the same shape), so the regression would be invisible until P6 deletes `kind:'topic'` and its
 * only caller, `HotTopicStrip`, along with it (plan D8, D7).
 *
 * @param {*}        dateOrHandoff either a plain date string, or a handoff object naming its own
 *                                 `kind`/`filterAction`/`region`
 * @param {?string}  eventType    SUNRISE/SUNSET, for the plain-date-string call shapes
 * @param {?string}  locationName a specific location, for the location-drilldown call shape
 * @returns {object} a trigger for {@link buildMapOverlay}
 */
export function normalizeMapTrigger(dateOrHandoff, eventType, locationName = null) {
  // First, because it is the one caller that names its own kind. Without this branch the object
  // falls past `filterAction` and `region` into the final `else` and becomes an `event` trigger
  // whose `date` is the whole object — an overlay for a night that does not exist.
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.kind === 'aurora') {
    return { kind: 'aurora', date: dateOrHandoff.date };
  }
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.kind === 'coming-up') {
    // The Coming up chronology's own map channel (D8) — named explicitly, checked before the
    // generic `filterAction` branch below, so it can never fall into `kind:'topic'` (the branch
    // P6 deletes) just because both carry a `filterAction`.
    return {
      kind: 'coming-up',
      filterAction: dateOrHandoff.filterAction ?? null,
      darkSky: !!dateOrHandoff.darkSky,
      label: dateOrHandoff.label ?? null,
      date: dateOrHandoff.date,
    };
  }
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.filterAction) {
    return {
      kind: 'topic', filterAction: dateOrHandoff.filterAction, label: dateOrHandoff.label, date: dateOrHandoff.date,
    };
  }
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.region) {
    // A region trigger, optionally carrying a hot topic's qualifying locations + label so the
    // overlay opens to just those pins (elevated inversion spots, coastal tide spots, …).
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

/** Star rating for a location on a date+event: briefing score wins, else the forecast rating. */
function ratingFor(loc, date, eventType, briefingScores) {
  const bs = briefingScoreFor(briefingScores, loc, date, eventType);
  if (bs?.rating != null) return bs.rating;
  const day = loc.forecastsByDate?.get?.(date);
  const forecast = eventType === 'SUNRISE' ? day?.sunrise : day?.sunset;
  return forecast?.rating ?? null;
}

function solarTimeFor(loc, date, eventType) {
  const day = loc.forecastsByDate?.get?.(date);
  const forecast = eventType === 'SUNRISE' ? day?.sunrise : day?.sunset;
  return forecast?.solarEventTime ?? null;
}

/** Verdict tone + peak label from a star rating (mirrors the grid's GO/MARGINAL/STANDDOWN bands). */
function toneFromRating(rating) {
  if (rating != null && rating >= 4) return { tone: 'go', label: '◎ Worth it' };
  if (rating != null && rating >= 3) return { tone: 'marginal', label: 'Maybe' };
  return { tone: 'standdown', label: 'Stand down' };
}

const MULTI_PROMPT = "Tap a pin to read PhotoCast's take on that region.";

/**
 * The Bortle-class cutoff for "dark sky". The single source of truth — `MapView.jsx` imports this
 * rather than keeping its own copy, since a second literal `4` with only a comment linking the two
 * can drift silently (found by review: a duplicate with no agreement test). This direction only —
 * `mapOverlay.js` is a light, dependency-free pure util, so `MapView.jsx` (already Leaflet-heavy)
 * importing FROM it costs nothing; the reverse would pull an unrelated map component into a file
 * that currently needs none of its dependencies.
 */
export const DARK_SKY_THRESHOLD = 4;

/**
 * Builds the overlay descriptor for a trigger.
 *
 * @param {Object} trigger  normalised trigger:
 *   { kind: 'region'|'event'|'location'|'topic'|'coming-up', region?, locationName?, filterAction?,
 *     darkSky?, label?, date, eventType }
 * @param {Object} ctx  { locations, briefingScores, todayStr, tomorrowStr, nonce }
 * @returns {Object} { title, subLine, narrative, narrativeHead, narrativeTone, caption, focus, handoff }
 */
export function buildMapOverlay(trigger, ctx) {
  const { locations = [], briefingScores = new Map(), todayStr, tomorrowStr, nonce = 0 } = ctx;
  const { date, eventType } = trigger;
  const enabled = locations.filter((l) => l.enabled !== false && l.lat != null && l.lon != null);
  const dl = date ? dayLabel(date, todayStr, tomorrowStr) : '';

  // ── Aurora — the alert banner's destination ──
  //
  // A leading early-return so no existing trigger's control flow moves; everything below is
  // untouched.
  //
  // ⚠️ It claims LESS than every other trigger here, deliberately. `ratingFor` and `solarTimeFor`
  // both resolve a non-SUNRISE event to the SUNSET forecast, so asking them about an AURORA trigger
  // would return that evening's sunset rating and sunset clock time dressed as aurora facts. There
  // is no star rating for an aurora and no solar time for one; the map itself carries the viewline
  // and the per-location aurora detail, which is the thing worth opening. So: no rating-derived
  // tone, no time, and no caption counting the roster (§6 — a count of our own locations is not a
  // fact about tonight).
  if (trigger.kind === 'aurora') {
    return {
      title: 'Aurora tonight',
      subLine: null,
      // The same neutral prompt and tone the multi-pin topic overlay uses. Not `go`: an alert says
      // the geomagnetic conditions are worth a look, not that the sky above any given pin is clear.
      narrative: MULTI_PROMPT,
      narrativeHead: null,
      narrativeTone: 'standdown',
      caption: null,
      // No fit-bounds: the pins that matter are wherever it is dark and clear, which this function
      // cannot know. The map opens on its own default view with aurora mode on.
      focus: null,
      handoff: { eventType: 'AURORA', date },
    };
  }

  // ── Coming up (D8) — a chronology card's action: filter the map and fit to the matching pins ──
  //
  // Modelled on the `topic` branch immediately below — the one branch that deliberately claims
  // nothing about ratings — but its OWN kind, never `kind:'topic'` itself: `HotTopicStrip` is the
  // only producer of that trigger today and P6 deletes its branch outright, so a new caller of it
  // here would break the moment P6 lands (plan D8).
  //
  // Two mutually exclusive filters, matching the card actions D8 names: `filterAction` (a
  // `locationType`, e.g. `SEASCAPE` for coastal spots) or `darkSky` (the Bortle-class toggle,
  // which has no `locationType` of its own — MapView's own manual toggle filters the same way).
  // The `date` IS carried into `selectedDate`/`MapView`, deliberately — unlike `location`/`region`/
  // `event`, this branch never calls `ratingFor`/`solarTimeFor` for it (matching `topic`, right
  // below), so a Coming-up date past Plan's four-day horizon cannot dress "no data" as "stand
  // down": there is no rating-derived claim here to get wrong. Recorded in the P3b phase log.
  if (trigger.kind === 'coming-up') {
    const matches = trigger.darkSky
      ? enabled.filter((l) => l.bortleClass != null && l.bortleClass <= DARK_SKY_THRESHOLD)
      : enabled.filter((l) => (l.locationType || []).includes(trigger.filterAction));
    const points = matches.map((l) => [l.lat, l.lon]);
    const regions = new Set(matches.map((l) => l.regionName).filter(Boolean));
    return {
      title: trigger.label || (trigger.darkSky ? 'Dark-sky spots' : trigger.filterAction) || 'Coming up',
      subLine: regions.size > 0 ? `${regions.size} ${regions.size === 1 ? 'region' : 'regions'}` : null,
      narrative: MULTI_PROMPT,
      narrativeHead: null,
      narrativeTone: 'standdown',
      caption: matches.length > 0
        ? `◍ ${matches.length} ${matches.length === 1 ? 'location' : 'locations'} — tap a pin to open it`
        : null,
      focus: points.length > 0 ? { points, names: matches.map((l) => l.name), nonce } : null,
      handoff: { filterAction: trigger.darkSky ? null : trigger.filterAction, darkSky: !!trigger.darkSky, date },
    };
  }

  // ── Topic (hot topic) — filter the map and fit to the matching pins ──
  if (trigger.kind === 'topic') {
    const matches = enabled.filter((l) => (l.locationType || []).includes(trigger.filterAction));
    const points = matches.map((l) => [l.lat, l.lon]);
    const regions = new Set(matches.map((l) => l.regionName).filter(Boolean));
    return {
      title: trigger.label || trigger.filterAction || 'Hot topic',
      subLine: regions.size > 0 ? `${regions.size} ${regions.size === 1 ? 'region' : 'regions'}` : null,
      narrative: MULTI_PROMPT,
      narrativeHead: null,
      narrativeTone: 'standdown',
      caption: matches.length > 0
        ? `◍ ${matches.length} ${matches.length === 1 ? 'location' : 'locations'} — tap a pin to open it`
        : null,
      focus: points.length > 0 ? { points, names: matches.map((l) => l.name), nonce } : null,
      handoff: { filterAction: trigger.filterAction, date },
    };
  }

  // ── Location — a specific spot: fly to it and open its popup ──
  if (trigger.kind === 'location') {
    const loc = enabled.find((l) => l.name === trigger.locationName);
    const time = formatClock(loc ? solarTimeFor(loc, date, eventType) : null);
    const bs = loc ? briefingScoreFor(briefingScores, loc, date, eventType) : null;
    const { tone, label } = toneFromRating(loc ? ratingFor(loc, date, eventType, briefingScores) : null);
    return {
      title: trigger.locationName,
      subLine: [dl, eventWord(eventType), time && `· ${time}`].filter(Boolean).join(' '),
      narrative: bs?.summary ?? null,
      narrativeHead: bs?.summary ? `${label} ${eventWord(eventType)} · ${trigger.locationName}` : null,
      narrativeTone: tone,
      caption: null,
      focus: null,
      handoff: { eventType, locationName: trigger.locationName, date },
    };
  }

  // ── Region / Event — gather the pins, then decide single vs multi ──
  let candidates;
  let titleRegion = trigger.region || null;
  if (trigger.kind === 'region' && trigger.region) {
    candidates = enabled.filter((l) => l.regionName === trigger.region);
  } else {
    candidates = enabled; // event trigger: every region
  }

  // A hot-topic click carries the exact qualifying spots (elevated / coastal / dark-sky …) —
  // restrict to those pins when present, so the overlay shows only what made the topic fire.
  const qualifying = trigger.locationNames && trigger.locationNames.length
    ? new Set(trigger.locationNames)
    : null;
  if (qualifying) {
    const restricted = candidates.filter((l) => qualifying.has(l.name));
    if (restricted.length > 0) candidates = restricted;
  }

  const rated = candidates
    .map((l) => ({ loc: l, rating: ratingFor(l, date, eventType, briefingScores) }))
    .filter((r) => r.rating != null);
  const pool = rated.length > 0 ? rated : candidates.map((l) => ({ loc: l, rating: null }));
  const regionsInvolved = new Set(pool.map((r) => r.loc.regionName).filter(Boolean));
  const time = formatClock(pool.length > 0 ? solarTimeFor(pool[0].loc, date, eventType) : null);

  const filterAction = trigger.filterAction ?? null;
  // For a hot-topic drilldown, the qualifying names let the overlay's MapView render ONLY those
  // spots (uniform across every topic — coastal, dark-sky, elevated, …), not just fit to them.
  const qualifyingNames = qualifying ? pool.map((r) => r.loc.name) : null;

  // Hot-topic region with several qualifying spots → show just those, fit to bounds, with a caption.
  if (qualifying && pool.length > 1) {
    const points = pool.map((r) => [r.loc.lat, r.loc.lon]);
    return {
      title: titleRegion || trigger.label || 'On the map',
      subLine: [trigger.label, dl].filter(Boolean).join(' · ') || null,
      narrative: MULTI_PROMPT,
      narrativeHead: null,
      narrativeTone: 'standdown',
      caption: `◍ ${pool.length} spots — tap a pin to open it`,
      focus: { points, names: qualifyingNames, nonce },
      handoff: { eventType, date, filterAction },
    };
  }

  // Multi-region → fit to all rated pins, no auto-open; the user taps a pin.
  if (!titleRegion && regionsInvolved.size > 1) {
    const points = pool.map((r) => [r.loc.lat, r.loc.lon]);
    return {
      title: `${dl} ${eventWord(eventType)}`.trim(),
      subLine: [`${regionsInvolved.size} regions`, time && `· ${time}`].filter(Boolean).join(' '),
      narrative: MULTI_PROMPT,
      narrativeHead: null,
      narrativeTone: 'standdown',
      caption: `◍ ${regionsInvolved.size} regions — tap a pin to open its locations`,
      focus: { points, nonce },
      handoff: { eventType, date },
    };
  }

  // Single region → focus it and auto-open the top-rated location's popup.
  const top = pool.reduce((best, r) => (best == null || (r.rating ?? -1) > (best.rating ?? -1) ? r : best), null);
  if (!titleRegion) titleRegion = top?.loc.regionName ?? '';
  const bs = top ? briefingScoreFor(briefingScores, top.loc, date, eventType) : null;
  const { tone, label } = toneFromRating(top?.rating ?? null);
  return {
    title: titleRegion || (top?.loc.name ?? 'On the map'),
    subLine: trigger.label
      ? [trigger.label, dl].filter(Boolean).join(' · ')
      : [dl, eventWord(eventType), time && `· ${time}`].filter(Boolean).join(' '),
    narrative: bs?.summary ?? null,
    narrativeHead: bs?.summary ? `${label} ${eventWord(eventType)} · ${titleRegion}`.trim() : null,
    narrativeTone: tone,
    caption: null,
    // A single qualifying spot still restricts the map's markers to just it (no points → the
    // location handoff below does the fly + popup); non-topic single-region drilldowns don't restrict.
    focus: qualifyingNames ? { names: qualifyingNames, nonce } : null,
    // A top location flies + opens its popup; without one, fall back to fitting the region's pins.
    // (Not both — they'd race the map camera.)
    handoff: top
      ? { eventType, locationName: top.loc.name, date, filterAction }
      : { eventType, region: titleRegion, date, filterAction },
  };
}
