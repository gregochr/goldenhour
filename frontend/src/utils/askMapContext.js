import { EVENT_KIND } from './mapEvents.js';

/**
 * What the Map tab tells Ask PhotoCast about the view it is asked from — pure, no React
 * (`docs/engineering/ask-photocast-plan.md` §2.6 "Scope follows the Map's scope, never the camera").
 *
 * <h2>Filter / map / select over served facts — the already-licensed class, not a new one</h2>
 * <p>Nothing here ranks, counts a population or derives a verdict. It reads three things the Map has
 * already decided — which region the reader jumped to, which of "My area" / "Everywhere" the scope
 * segment is on (and the regions that scope holds, off the same scope pool the verdict reads), and
 * which window the pill shows — and writes them as what a question will be sent with: region ids
 * (joined to the regions list by name, the one key the briefing and the regions payload share), a
 * window id in the server's own encoding, and the words for the chips.
 *
 * <h2>What is NOT read: the camera</h2>
 * <p>The scope is the segment, never the viewport. A reader who pans away has not changed what they
 * are asking about, and an answer that quietly followed the pan would contradict the chip above it.
 *
 * <h2>The chip names what is SENT</h2>
 * <p>If a region cannot be resolved to an id (the regions list has not arrived, or does not hold the
 * name), the question would go to every region, so the context says exactly that — "Map ·
 * Everywhere" — rather than claiming a narrower scope than it sends. A narrower claim over a wider
 * request is the one kind of mismatch this module may not produce.
 *
 * <p>⚠️ <b>Two honest limits on "My area"</b>, both decided by the plan (§2.6: "the regions of the scope
 * pool") and recorded here so the chip is not read as more exact than it is. First, the area is drawn
 * round LOCATIONS (a drive-time reach from home), but a question is scoped by whole REGIONS, so the
 * regions of the pool can hold places outside the area: the request is wider than the area, never
 * narrower. Second, with no field to scope from (the heat field is not offered: no briefing yet, or
 * no catalogue) the pool is empty and the context is "Everywhere" whatever the segment says.
 */

/**
 * The window id the server knows a solar window by: {@code yyyy-MM-dd_sunrise} or
 * {@code yyyy-MM-dd_sunset} (`AskWindowId`'s format, the encoding `BriefingRollupBuilder` already
 * used). {@code toLowerCase}, not a locale form: a Turkish locale would lower-case {@code I} to a
 * dotless one and stop the id matching.
 *
 * @param {string} date the event's UK civil date
 * @param {string} eventType {@code SUNRISE} or {@code SUNSET}
 * @returns {string}
 */
export function askWindowId(date, eventType) {
  return `${date}_${String(eventType).toLowerCase()}`;
}

/**
 * The Map's own facts, as {@code MapView} reads them off its state.
 *
 * @typedef {object} MapAskFacts
 * @property {?string} focusRegion the region a Regions jump framed, while that jump stands
 * @property {boolean} scoped the scope segment is on "My area" AND that narrows anything (a home or
 *           an origin exists) — otherwise it is the whole catalogue whatever the segment says
 * @property {Array<string>} areaNames the regions the scope pool holds — WHOLE regions, so possibly
 *           wider than the area itself (see the module note); empty when no field is offered
 * @property {?string} areaLabel what the scope is called, when it is not "My area" (an away
 *           origin's "Around Keswick")
 * @property {?{date: string, eventType: string, label: string}} window the solar window the pill
 *           shows and the briefing served; null for a night row and for a window nothing served
 */

/**
 * Whether an EV row is a window Ask can be asked about: a SOLAR row the briefing actually served. A
 * night (astro, aurora) row is no window of the forecast's, and a filler row beyond the briefing
 * has no window id the server would know.
 *
 * @param {?object} row {@code MapView}'s active EV row
 * @returns {?{date: string, eventType: string, label: string}} its facts, or null
 */
export function askWindowOf(row) {
  if (!row || row.kind !== EVENT_KIND.SOLAR || row.served === false) return null;
  if (typeof row.date !== 'string' || (row.eventType !== 'SUNRISE' && row.eventType !== 'SUNSET')) return null;
  return { date: row.date, eventType: row.eventType, label: String(row.label ?? '') };
}

/**
 * The context the Map publishes.
 *
 * @param {MapAskFacts} facts
 * @param {Array<{id: number, name: string}>} regions the regions list (`GET /api/regions`)
 * @returns {{regionIds: Array<number>, regionNames: Array<string>, windowId: ?string,
 *   windowLabel: ?string, viewLabel: string}}
 */
export function buildMapAskContext(facts, regions) {
  let names = [];
  let viewLabel = 'Map · Everywhere';
  if (facts.focusRegion) {
    names = [facts.focusRegion];
    viewLabel = `Map · ${facts.focusRegion}`;
  } else if (facts.scoped && facts.areaNames.length > 0) {
    names = facts.areaNames;
    viewLabel = `Map · ${facts.areaLabel || 'My area'}`;
  }

  const ids = [];
  for (const name of names) {
    const region = (Array.isArray(regions) ? regions : []).find((r) => r?.name === name);
    if (region == null || !Number.isInteger(region.id)) {
      // One name the list cannot place sends the question everywhere — and says so.
      ids.length = 0;
      names = [];
      viewLabel = 'Map · Everywhere';
      break;
    }
    ids.push(region.id);
  }

  const window = facts.window ?? null;
  return {
    regionIds: ids,
    regionNames: names,
    windowId: window ? askWindowId(window.date, window.eventType) : null,
    windowLabel: window ? window.label : null,
    viewLabel,
  };
}
