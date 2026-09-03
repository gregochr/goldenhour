import { clamp } from './heatGeometry.js';
import { leaveByParts } from './leaveBy.js';
import { shortDow } from './locationSheet.js';
import { formatDriveDuration } from './briefingDisplay.js';
import { DAY_SCOPED_TOPIC_TYPES } from './windowFirstTopics.js';
import { DARK_SKY_THRESHOLD } from './mapOverlay.js';

/**
 * The Map tab's selection callout — pure logic only (map-tab-v2-plan.md §3 P9,
 * `docs/design/map-tab-v2/README.md` §7 "Selection — on the map, not in a popup",
 * `docs/design/map-tab-v2/map-tab-v2.js`'s {@code calBand}/{@code anchorCal}).
 *
 * <p>Everything here is DOM-free: the anchoring maths take already-measured pixel rects and a
 * card's own already-measured size, exactly the split {@code utils/mapLabels.js} draws for the same
 * reason — a caller ({@code components/map/MapCallout.jsx}) does the Leaflet projection and the DOM
 * measurement, and hands this module the numbers.
 */

/** The gap between the marker and the card, below the marker (bundle's own constant). */
export const CALLOUT_GAP = 22;

/** How close the card may sit to the frame's own edges, horizontally. */
export const CALLOUT_MARGIN = 8;

/** The band's own floor — never narrower than this (bundle: {@code Math.max(bot, top + 90)}). */
export const CALLOUT_MIN_BAND = 90;

/** How close the card's edges may sit to the top/bottom band boundary before flipping/clamping. */
const BAND_EDGE_PAD = 8;

/**
 * The vertical band the callout is free to occupy — the strip left clear by chrome bars, NOT the
 * whole map box (README §7's own {@code calBand}: clamping to the map box let the card land under a
 * phone's bottom bar). Only a bar spanning at least half the frame's width counts as a floor or
 * ceiling — a narrow chip beside the callout must never squeeze the band meant for actual bars like
 * the window control or the bottom sheet-rail.
 *
 * @param {object} args
 * @param {number} args.frameWidth  the map container's width, px
 * @param {number} args.frameHeight the map container's height, px
 * @param {Array<{top: number, bottom: number, width: number, height: number}>} args.bars live chrome
 *        rects, already relative to the container's own top-left corner
 * @returns {{top: number, bot: number}} the band, in the same container-relative px space
 */
export function calloutBand({ frameWidth, frameHeight, bars }) {
  let top = BAND_EDGE_PAD;
  let bot = frameHeight - BAND_EDGE_PAD;
  for (const bar of Array.isArray(bars) ? bars : []) {
    if (!bar || !(bar.width > 0) || !(bar.height > 0)) continue;
    if (bar.width < frameWidth * 0.5) continue;
    if (bar.bottom < frameHeight * 0.5) {
      top = Math.max(top, bar.bottom + BAND_EDGE_PAD);
    } else {
      bot = Math.min(bot, bar.top - BAND_EDGE_PAD);
    }
  }
  return { top, bot: Math.max(bot, top + CALLOUT_MIN_BAND) };
}

/**
 * Where the card and its tail land, recomputed every paint so the callout travels with its point
 * through pan and zoom (README §7). Prefers below the marker; flips above when it would overflow
 * the band; clamps horizontally to {@link CALLOUT_MARGIN}; clamps the tail to stay within the card.
 *
 * @param {object} args
 * @param {{x: number, y: number}} args.point   the marker's own container-relative pixel position
 * @param {number} args.cardWidth   the card's own measured width, px
 * @param {number} args.cardHeight  the card's own measured height, px
 * @param {number} args.frameWidth  the map container's width, px
 * @param {{top: number, bot: number}} args.band from {@link calloutBand}
 * @param {number} [args.gap]    vertical gap between the marker and the card
 * @param {number} [args.margin] horizontal clamp margin
 * @returns {{left: number, top: number, below: boolean, tailLeft: number}}
 */
export function anchorCallout({
  point, cardWidth, cardHeight, frameWidth, band, gap = CALLOUT_GAP, margin = CALLOUT_MARGIN,
}) {
  let below = point.y + gap + cardHeight <= band.bot;
  let top = below ? point.y + gap : point.y - gap - cardHeight;
  if (!below && top < band.top) {
    below = true;
    top = point.y + gap;
  }
  top = clamp(top, band.top, Math.max(band.top, band.bot - cardHeight));
  const left = clamp(point.x - cardWidth / 2, margin, Math.max(margin, frameWidth - cardWidth - margin));
  const tailLeft = clamp(point.x - left - 5.5, 13, Math.max(13, cardWidth - 24));
  return {
    left, top, below, tailLeft,
  };
}

/**
 * The leave-by fact, with the midnight-crossing guard §3 P9 requires — "a leave-by that crosses
 * midnight is either marked or suppressed" (the prototype's {@code m2hm} silently wraps and is NOT
 * ported, plan §4.12). This MARKS rather than suppresses, the same choice {@code LocationFourDaySheet}
 * already made for the identical rule (plan-matrix superset P2) — a departure the reader is meant to
 * act on should not vanish just because it lands the evening before.
 *
 * @param {?string} eventTimeIso  the window's event instant, UTC as served
 * @param {?number} driveMinutes  this user's drive to the spot; null/unmeasured yields no fact at all
 * @returns {?{time: string, dayWord: ?string}} the clock time, plus a short weekday word ONLY when
 *          the departure falls on a different UK day than the event — null when the drive or the
 *          event time is unknown
 */
export function calloutLeaveBy(eventTimeIso, driveMinutes) {
  const parts = leaveByParts(eventTimeIso, driveMinutes);
  if (!parts) return null;
  return { time: parts.time, dayWord: parts.sameDay ? null : shortDow(parts.date) };
}

/**
 * The facts row — Drive, Leave by, Dark sky — each one honouring the {@code reachMeasured}
 * discipline (plan §1.12/§5.2): a fact derived from an unmeasured drive is OMITTED, never rendered
 * as "unknown" or a dash. Miles are the caller's to gate (home-origin only — see the module's own
 * caller, {@code MapCallout.jsx}, for why: an away origin's drive comes from the shared region
 * matrix, which carries no {@code distanceMiles} by design, `utils/planOrigin.js`'s own rule).
 *
 * @param {object} args
 * @param {?number} args.driveMinutes  measured drive time, or null/undefined when unmeasured
 * @param {?number} [args.distanceMiles] straight-line miles — HOME origin only; the caller passes
 *        null for an away origin so this never prints a distance measured from the wrong place
 * @param {?string} [args.eventTimeIso] the window's event instant, for the leave-by fact
 * @param {?number} [args.bortleClass] the location's Bortle class, or null when unknown
 * @returns {Array<{key: string, label: string, value: string}>} the facts to render, in order
 */
export function calloutFacts({
  driveMinutes, distanceMiles = null, eventTimeIso = null, bortleClass = null,
}) {
  const facts = [];
  if (Number.isFinite(driveMinutes)) {
    facts.push({
      key: 'drive',
      label: 'Drive',
      value: Number.isFinite(distanceMiles)
        ? `${formatDriveDuration(driveMinutes)} · ${distanceMiles} mi`
        : formatDriveDuration(driveMinutes),
    });
    const leave = calloutLeaveBy(eventTimeIso, driveMinutes);
    if (leave) {
      facts.push({
        key: 'leave',
        label: 'Leave by',
        value: leave.dayWord ? `${leave.time} (${leave.dayWord})` : leave.time,
      });
    }
  }
  if (bortleClass != null) {
    facts.push({
      key: 'dark',
      label: 'Dark sky',
      value: bortleClass <= DARK_SKY_THRESHOLD ? `${bortleClass} · dark` : `${bortleClass}`,
    });
  }
  return facts;
}

/**
 * Whether a location is coastal-and-tidal — the same test {@code MarkerPopupContent} already applies
 * ({@code (location.tideType ?? []).filter(...)}): a location with no {@code tideType} preference at
 * all has no tide to align with, so a tide topic on its window is not about it.
 *
 * @param {?{tideType: ?Array<string>}} location
 * @returns {boolean}
 */
export function isCoastalTidalLocation(location) {
  return Array.isArray(location?.tideType) && location.tideType.length > 0;
}

/**
 * Topic tags filtered to the location (plan §3 P9: "tide topics only where {@code coastalTidal}
 * — reuse {@code windowFirstTopics}' type-map idiom"). Reuses {@link DAY_SCOPED_TOPIC_TYPES}
 * verbatim rather than authoring a second "which types are tide types" list — that module's own
 * warning against a fork applies here as much as it does to the window-scoped filter it was written
 * for.
 *
 * @param {Array<{type: ?string}>} badges the current window's served badges
 * @param {boolean} coastalTidal whether {@link isCoastalTidalLocation} holds for this location
 * @returns {Array<object>} the badges to show
 */
export function filterCalloutTopics(badges, coastalTidal) {
  return (Array.isArray(badges) ? badges : []).filter((badge) => {
    const type = String(badge?.type || '').toUpperCase();
    if (!DAY_SCOPED_TOPIC_TYPES.has(type)) return true;
    return coastalTidal;
  });
}

/**
 * Each window's region gloss, keyed the way {@code locationSheet.buildSlotIndex} keys its own
 * per-window join — {@code date|targetType|regionName} — because the reason prose's fallback is a
 * REGION's gloss (plan §3 P9: "fallback: region gloss"), never a location's, and a region can carry
 * a different gloss on every window it appears in.
 *
 * @param {Array} days {@code briefing.days}
 * @returns {Map<string, {headline: ?string, detail: ?string}>}
 */
export function buildRegionGlossIndex(days) {
  const index = new Map();
  for (const day of Array.isArray(days) ? days : []) {
    if (!day?.date) continue;
    for (const summary of day.eventSummaries ?? []) {
      if (!summary?.targetType) continue;
      for (const region of summary.regions ?? []) {
        if (!region?.regionName) continue;
        const headline = region.glossHeadline || null;
        const detail = region.glossDetail || null;
        if (!headline && !detail) continue;
        const key = `${day.date}|${summary.targetType}|${region.regionName}`;
        if (!index.has(key)) index.set(key, { headline, detail });
      }
    }
  }
  return index;
}

/**
 * One region's gloss for one window, or null — the detail line preferred over the headline, since
 * the callout's reason prose is a sentence, not a heading.
 *
 * @param {?Map} index    from {@link buildRegionGlossIndex}
 * @param {string} date
 * @param {string} eventType SUNRISE or SUNSET — a night window has no region gloss to fall back to
 * @param {?string} regionName
 * @returns {?string} the gloss prose, or null
 */
export function regionGlossFor(index, date, eventType, regionName) {
  if (!index || !regionName) return null;
  const entry = index.get(`${date}|${eventType}|${regionName}`);
  return entry ? (entry.detail || entry.headline || null) : null;
}
