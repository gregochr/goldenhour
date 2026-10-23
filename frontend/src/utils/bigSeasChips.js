/**
 * The Map tab's big-seas chips: which served structures get a chip on the window on screen, and the
 * words their accessible name and hover tooltip print (docs/engineering/big-seas-plan.md §3 F2).
 *
 * <p>A LEAF module on purpose (no {@code d3-geo} chain): {@code MapView} needs the chip list itself,
 * for the legend key's one shared condition, while {@code mapLabels.js} (which re-exports everything
 * here) is reachable only through the lazy {@code MapLabels}/{@code PinsLayer} chunks. Same reason
 * {@code verdictWord.js} is a leaf.
 *
 * <p><b>Filter and select only.</b> The backend decided which structures qualify and their wave
 * facts; this module joins them to coordinates and prints them. It never tests a wave or a window.
 */
import { degreesToCompass16, formatEventTimeUk } from './conversions.js';
import { formatDriveDuration } from './briefingDisplay.js';

/** Chips are hidden from this zoom up (handoff "Hidden at zoom >= 13"): street level needs no cue. */
export const BIG_SEAS_CHIP_MAX_ZOOM = 13;

/** No chips: one stable identity, so a default prop never repaints a label pass. */
export const NO_BIG_SEAS_CHIPS = Object.freeze([]);

/**
 * The chips for one full big-seas day on one EV row.
 *
 * <p>Joins each served {@code structures[].locationId} to {@code locationsById} (the FULL roster),
 * never to the label pool: that pool drops stood-down places and anything under the star floor, which
 * is exactly the storm-day case. A structure whose location is missing, or has no finite coordinates,
 * has nowhere to be drawn and is skipped.
 *
 * <p>The caller passes {@code day} only when {@code bigSeasFor(...).form === 'full'} (null otherwise).
 * Chips are solar only: a night row (astro, aurora) draws none, and neither does a past row or a map
 * at street level. A chip is ringed when the row on screen is the day's aligned event.
 *
 * @param {?object} day the served {@code BigSeasDay} of the full form, or null
 * @param {?Map<number, {name: string, lat: number, lon: number, regionName: ?string,
 *   driveMinutes: ?number}>} locationsById the roster by id
 * @param {number} zoom the map's zoom
 * @param {?{kind: string, eventType: string, past: ?boolean}} evRow the EV row on screen
 * @returns {Array<{key: string, locationId: number, name: string, label: string, lat: number,
 *   lng: number, heightM: number, ringed: boolean, day: object, structure: object,
 *   regionName: string, driveMinutes: ?number}>} the chips, in the order served
 */
export function bigSeasChipItems(day, locationsById, zoom, evRow) {
  if (!day || !evRow || evRow.kind !== 'solar' || evRow.past) return NO_BIG_SEAS_CHIPS;
  if (!(zoom < BIG_SEAS_CHIP_MAX_ZOOM)) return NO_BIG_SEAS_CHIPS;
  const structures = Array.isArray(day.structures) ? day.structures : [];
  const ringed = Boolean(day.alignedEvent) && evRow.eventType === day.alignedEvent;
  const items = [];
  for (const structure of structures) {
    const loc = locationsById?.get(structure.locationId);
    if (!loc || !Number.isFinite(loc.lat) || !Number.isFinite(loc.lon)) continue;
    items.push({
      key: `bs:${structure.locationId}`,
      locationId: structure.locationId,
      name: loc.name,
      label: structure.label || loc.name,
      lat: loc.lat,
      lng: loc.lon,
      heightM: Number(structure.waveHeightM),
      ringed,
      day,
      structure,
      regionName: loc.regionName || '',
      driveMinutes: Number.isFinite(loc.driveMinutes) ? loc.driveMinutes : null,
    });
  }
  return items.length === 0 ? NO_BIG_SEAS_CHIPS : items;
}

/**
 * The chips with the protected places taken out. An Ask pick's chip and the selected place's chip are
 * never suppressed in favour of a big-seas chip (the pick is the answer; the selection is the ring
 * and callout's anchor), so in that case the big-seas chip is the one dropped.
 *
 * @param {Array<{name: string}>} items {@link bigSeasChipItems}' output
 * @param {{selectedName: ?string, askNames: ?Set<string>}} protectedNames
 * @returns {Array<object>} the chips that stay
 */
export function dropProtectedBigSeasChips(items, { selectedName = null, askNames = null } = {}) {
  if (items.length === 0) return items;
  const kept = items.filter((it) => it.name !== selectedName && !askNames?.has(it.name));
  return kept.length === items.length ? items : kept;
}

/**
 * The ordinary chip pool minus the places a big-seas chip stands for (on a full day the structure's
 * ordinary chip would say the same place twice).
 *
 * @param {Array<{name: string}>} spots the label pool
 * @param {Array<{name: string}>} items the big-seas chips that stay
 * @returns {Array<object>} the pool without those places; the same array when nothing is removed
 */
export function withoutBigSeasDuplicates(spots, items) {
  if (items.length === 0) return spots;
  const names = new Set(items.map((it) => it.name));
  return spots.filter((spot) => !names.has(spot.name));
}

/**
 * The collision tiers in placement order: home, rings, Ask picks, regions (and the stand-down
 * labels), then the big-seas chips, then the ordinary chips. An earlier item keeps its space
 * whatever a later one would have preferred.
 *
 * @param {{home?: Array, rings?: Array, picks?: Array, regions?: Array, bigSeas?: Array,
 *   chips?: Array}} tiers measured items, grouped
 * @returns {Array} one list in priority order
 */
export function labelTierOrder({
  home = [], rings = [], picks = [], regions = [], bigSeas = [], chips = [],
}) {
  return [...home, ...rings, ...picks, ...regions, ...bigSeas, ...chips];
}

/** "15:30–18:15" on the UK clock. */
function windowText(day) {
  return `${formatEventTimeUk(day.windowStart)}–${formatEventTimeUk(day.windowEnd)}`;
}

/** "4.3" from a served height. */
function metres(heightM) {
  return Number(heightM).toFixed(1);
}

/** "sunset" / "sunrise" from the served enum, or null. */
function eventWord(day) {
  return day.alignedEvent ? day.alignedEvent.toLowerCase() : null;
}

/**
 * The chip's accessible name. An {@code aria-label} REPLACES the content, so it carries everything
 * the chip shows and the ring's meaning: "Roker Pier, big seas, waves 4.3 metres, 15:30 to 18:15,
 * high water on the sunset".
 *
 * @param {{label: string, heightM: number, ringed: boolean, day: object}} item
 * @returns {string}
 */
export function bigSeasChipAriaLabel(item) {
  const { day } = item;
  const base = `${item.label}, big seas, waves ${metres(item.heightM)} metres, `
    + `${formatEventTimeUk(day.windowStart)} to ${formatEventTimeUk(day.windowEnd)}`;
  const event = eventWord(day);
  return item.ringed && event ? `${base}, high water on the ${event}` : base;
}

/** A period like 11 or 8.5 seconds. */
function seconds(periodS) {
  const p = Number(periodS);
  return Number.isInteger(p) ? String(p) : p.toFixed(1);
}

/**
 * The hover tooltip's lines, per the handoff: name / "Big seas · 15:30–18:15" / "High water 16:58 ·
 * 49 min before sunset" (ringed only) / "Waves 4.3 m from NNE, 11 s · faces NE, onshore" / "region · drive".
 *
 * @param {object} item a chip from {@link bigSeasChipItems}
 * @returns {string[]} the lines; the last is omitted when it would be empty
 */
export function bigSeasChipTipLines(item) {
  const { day, structure } = item;
  const lines = [item.name, `Big seas · ${windowText(day)}`];
  const event = eventWord(day);
  if (item.ringed) {
    let hw = `High water ${formatEventTimeUk(day.hwTime)}`;
    if (event && day.gapMinutes != null && day.gapSide) {
      hw += ` · ${day.gapMinutes} min ${day.gapSide === 'BEFORE' ? 'before' : 'after'} ${event}`;
    }
    lines.push(hw);
  }
  lines.push(
    `Waves ${metres(structure.waveHeightM)} m from ${degreesToCompass16(structure.waveDirectionDeg)}, `
    + `${seconds(structure.wavePeriodS)} s · faces ${degreesToCompass16(structure.facesDeg)}, onshore`,
  );
  const where = [item.regionName, item.driveMinutes != null ? formatDriveDuration(item.driveMinutes) : null]
    .filter(Boolean).join(' · ');
  if (where) lines.push(where);
  return lines;
}
