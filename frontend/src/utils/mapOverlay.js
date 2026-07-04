// Builds the map-overlay descriptor for a Plan-tab recommendation trigger.
//
// The overlay reuses MapView through the existing handoff seam (fly-to + popup for a single
// location, fit-to-pins for several) and preserves the region's Claude gloss as a footer band —
// all derived from data already on the client (locations, forecasts, briefing scores). No new data.

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
 * Builds the overlay descriptor for a trigger.
 *
 * @param {Object} trigger  normalised trigger:
 *   { kind: 'region'|'event'|'location'|'topic', region?, locationName?, filterAction?, label?,
 *     date, eventType }
 * @param {Object} ctx  { locations, briefingScores, todayStr, tomorrowStr, nonce }
 * @returns {Object} { title, subLine, narrative, narrativeHead, narrativeTone, caption, focus, handoff }
 */
export function buildMapOverlay(trigger, ctx) {
  const { locations = [], briefingScores = new Map(), todayStr, tomorrowStr, nonce = 0 } = ctx;
  const { date, eventType } = trigger;
  const enabled = locations.filter((l) => l.enabled !== false && l.lat != null && l.lon != null);
  const dl = date ? dayLabel(date, todayStr, tomorrowStr) : '';

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
      focus: points.length > 0 ? { points, nonce } : null,
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

  // Hot-topic region with several qualifying spots → fit to them all with a caption.
  if (qualifying && pool.length > 1) {
    const points = pool.map((r) => [r.loc.lat, r.loc.lon]);
    return {
      title: titleRegion || trigger.label || 'On the map',
      subLine: [trigger.label, dl].filter(Boolean).join(' · ') || null,
      narrative: MULTI_PROMPT,
      narrativeHead: null,
      narrativeTone: 'standdown',
      caption: `◍ ${pool.length} spots — tap a pin to open it`,
      focus: { points, nonce },
      handoff: { eventType, date },
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
    focus: null,
    // A top location flies + opens its popup; without one, fall back to fitting the region's pins.
    // (Not both — they'd race the map camera.)
    handoff: top ? { eventType, locationName: top.loc.name, date } : { eventType, region: titleRegion, date },
  };
}
