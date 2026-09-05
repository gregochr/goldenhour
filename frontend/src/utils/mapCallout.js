import { clamp } from './heatGeometry.js';
import { leaveByParts } from './leaveBy.js';
import { shortDow } from './locationSheet.js';
import { formatDriveDuration } from './briefingDisplay.js';
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
 * <h2>⚠️ A bar may opt OUT of the width test, and one does</h2>
 *
 * <p>The width rule is a proxy for "is this real chrome or an incidental box", and it gets the
 * counts footer wrong. <b>Measured on the running app at 375×633</b> (increment §1's own phone
 * check): the footer measured <b>184px — 48.9% of the frame</b>, just under the threshold, so it
 * was skipped, the band ran straight through it, and the callout painted over the counts line both
 * collapsed and expanded. The increment's check 1 and the bundle's check 4 both say the callout
 * never covers a control, and this is the control it covered.
 *
 * <p>⚠️ That 48.9% is a measurement of the string the footer printed at the time
 * ({@code "16 named · 16 rated of 18"}, since shortened to {@code "16 of 18 shown"} by the
 * plain-English copy pass, #748). The exact figure has therefore already moved — which is the
 * strongest argument for the opt-out over a lower threshold: a rule tuned to a percentage is a rule
 * that COPY can break, and this one cannot.
 *
 * <p>Lowering the threshold is the wrong fix — it would start counting Leaflet's own zoom+home
 * corner, which is small chrome in a corner the card rarely reaches and which SHOULD be skipped, and
 * the number would then be tuned against two opposing cases at once. So the footer opts out by name
 * instead ({@code always: true}, set at its one call site in `MapCallout`), which states the fact
 * the width test was standing in for: bottom-centred chrome the card must clear at any width. Every
 * other bar keeps the width rule unchanged.
 *
 * @param {object} args
 * @param {number} args.frameWidth  the map container's width, px
 * @param {number} args.frameHeight the map container's height, px
 * @param {Array<{top: number, bottom: number, width: number, height: number, always: ?boolean}>}
 *        args.bars live chrome rects, already relative to the container's own top-left corner.
 *        {@code always} skips the width test for that bar alone
 * @returns {{top: number, bot: number}} the band, in the same container-relative px space
 */
export function calloutBand({ frameWidth, frameHeight, bars }) {
  let top = BAND_EDGE_PAD;
  let bot = frameHeight - BAND_EDGE_PAD;
  for (const bar of Array.isArray(bars) ? bars : []) {
    if (!bar || !(bar.width > 0) || !(bar.height > 0)) continue;
    if (!bar.always && bar.width < frameWidth * 0.5) continue;
    if (bar.bottom < frameHeight * 0.5) {
      top = Math.max(top, bar.bottom + BAND_EDGE_PAD);
    } else {
      bot = Math.min(bot, bar.top - BAND_EDGE_PAD);
    }
  }
  return { top, bot: Math.max(bot, top + CALLOUT_MIN_BAND) };
}

/**
 * Where the card lands, recomputed every paint so the callout travels with its point through pan
 * and zoom (README §7). Prefers below the marker; flips above when it would overflow the band;
 * clamps horizontally to {@link CALLOUT_MARGIN}.
 *
 * <p>⚠️ It no longer places a tail: the card's 11px pointer was removed on 2026-09-05 at the
 * owner's request (map-tab-v2-plan.md §4.30), and the {@code tailLeft} this returned went with it —
 * a coordinate with no renderer is the write-only shape this codebase deletes on sight.
 *
 * <p>{@code below} is deliberately NOT treated the same way, though the tail span was its only
 * production reader and {@code MapCallout} now takes {@code left}/{@code top} alone. It is not a
 * derived coordinate for a deleted element: it is this function's own flip DECISION, which it makes
 * regardless in order to choose {@code top}, and returning it is what lets a test say "it flipped"
 * rather than compare two pixel values that can coincide. Kept as the readable expression of that
 * branch, not as a value anything paints.
 *
 * @param {object} args
 * @param {{x: number, y: number}} args.point   the marker's own container-relative pixel position
 * @param {number} args.cardWidth   the card's own measured width, px
 * @param {number} args.cardHeight  the card's own measured height, px
 * @param {number} args.frameWidth  the map container's width, px
 * @param {{top: number, bot: number}} args.band from {@link calloutBand}
 * @param {number} [args.gap]    vertical gap between the marker and the card
 * @param {number} [args.margin] horizontal clamp margin
 * @returns {{left: number, top: number, below: boolean}}
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
  return { left, top, below };
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
 * Topic tags filtered to the location.
 *
 * <p>⚠️ <b>Moved, not copied.</b> The location sheet's meta row needs the identical rule (increment
 * §2), and `locationSheet.js` importing THIS module would close a cycle — `mapCallout` already
 * imports `shortDow` from it. The implementation moved to `windowFirstTopics.js`, beside the
 * {@code DAY_SCOPED_TOPIC_TYPES} set it has always read, which is where it belonged anyway. This
 * re-export keeps the callout arm's own vocabulary and its existing importers intact.
 */
export { filterCalloutTopics } from './windowFirstTopics.js';

/**
 * The briefing's per-window region glosses.
 *
 * <p>⚠️ <b>Moved, not copied</b> — the third such move in this file, and for the third time the same
 * reason: the location sheet needs this index too (it is the callout's prose FALLBACK, and without
 * it the sheet can show less prose than the callout that routes into it), and `locationSheet.js`
 * importing THIS module would close a cycle. The implementation lives in `utils/regionGloss.js`;
 * these re-exports keep the callout arm's own vocabulary and its existing importers intact.
 */
export { buildRegionGlossIndex, regionGlossFor } from './regionGloss.js';
