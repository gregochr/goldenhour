import { topWindowFor } from './planSearch.js';
import { formatDriveDuration } from './briefingDisplay.js';
import { isOutsidePicks } from './pickScope.js';

/** What an area is called in the sheet's header. Never a rating word. */
const KIND_LABEL = { SUB_REGION: 'Sub-region', NAMED: 'Named area' };

/**
 * The area sheet's view-model (find-and-favourites-plan.md F2): the area, and its spots each with their own best
 * window, ordered by that rating and then by drive.
 *
 * <p>An area has no rating, no verdict and no "best": every figure here belongs to a place and names its window,
 * computed by {@code topWindowFor} (one place's own windows, the same walk the Find row for that place makes). A
 * place the server left outside a Lite reader's picks is listed as outside them and never as unrated: the list is
 * the briefing's served {@code allowedLocationIds}, never a client rule.
 *
 * @param {{name: string, kind: string, regionName: ?string, locationIds: number[]}} area a directory entry
 * @param {Array}   spots the heat spots (id, name, regionName, skySubject, ...)
 * @param {Array}   windows the strip's descriptors, in render order
 * @param {object}  [ctx]
 * @param {?object} [ctx.scoreIndex] from {@code buildScoreIndex}
 * @param {?Map}    [ctx.reachById] the reach map the page plans from, for each place's drive
 * @param {*}       [ctx.allowedLocationIds] the briefing's served allowed list, on a shaped body only
 * @returns {{name: string, kindLabel: string, regionName: ?string, count: number, driveRange: ?string,
 *   ratedCount: number, gatedCount: number, rows: Array}} the sheet
 */
export function buildAreaSheet(area, spots, windows, {
  scoreIndex = null, reachById = null, allowedLocationIds = null,
} = {}) {
  const members = new Set(area.locationIds || []);
  const rows = (spots || [])
    .filter((spot) => spot && members.has(spot.id))
    .map((spot) => {
      const top = topWindowFor(spot, windows, scoreIndex);
      const drive = reachById?.get(spot.id)?.driveMinutes ?? null;
      const gated = isOutsidePicks(allowedLocationIds, spot.id);
      return { spot, top: gated ? null : top, drive, gated };
    })
    // Rated places first, best first; then unrated; the places outside the reader's picks last. Then the nearer
    // drive, then the name, so the order never depends on the catalogue's.
    .sort((a, b) => (Number(a.gated) - Number(b.gated))
      || ((b.top?.rating ?? 0) - (a.top?.rating ?? 0))
      || ((a.drive ?? Infinity) - (b.drive ?? Infinity))
      || a.spot.name.localeCompare(b.spot.name));
  const drives = rows.map((row) => row.drive).filter((d) => d != null);
  const low = drives.length ? Math.min(...drives) : null;
  const high = drives.length ? Math.max(...drives) : null;
  let driveRange = null;
  if (low != null) driveRange = low === high ? formatDriveDuration(low) : `${formatDriveDuration(low)} to ${formatDriveDuration(high)}`;
  return {
    name: area.name,
    kindLabel: KIND_LABEL[area.kind] || KIND_LABEL.NAMED,
    regionName: area.regionName ?? null,
    count: (area.locationIds || []).length,
    driveRange,
    ratedCount: rows.filter((row) => row.top).length,
    gatedCount: rows.filter((row) => row.gated).length,
    rows,
  };
}

/**
 * The window "Show on map →" opens the Map on: the first strip window that is not an away day and has the highest
 * rated place in the area, else simply the first window the strip offers.
 *
 * @param {Array} windows the strip's descriptors, in render order
 * @param {?Array} outlookWindows the area's outlook windows, or null
 * @returns {?{date: string, targetType: string}} the window, or null when the strip has none
 */
export function areaMapWindow(windows, outlookWindows) {
  const usable = (windows || []).filter((card) => card && !card.away && card.date && card.targetType);
  if (usable.length === 0) return null;
  const topByKey = new Map((outlookWindows || []).map((w) => [`${w.date}:${w.targetType}`, w.topRating ?? 0]));
  let best = usable[0];
  for (const card of usable) {
    if ((topByKey.get(card.key) ?? 0) > (topByKey.get(best.key) ?? 0)) best = card;
  }
  return { date: best.date, targetType: best.targetType };
}
