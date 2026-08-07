import { DISPLAY_ORDER } from './briefingDisplay.js';
import { resolveRegionDisplay } from './tierUtils.js';

/**
 * The region row order the heatmap is drawn in — best verdict first, then A–Z.
 *
 * <p><b>A copy of {@code DailyBriefing}'s module-private {@code getSortedRegions}, not an
 * extraction.</b> The rule plan §5a set for P6 applies unchanged: an extraction edits the v1 arm,
 * and §4's whole method rests on the v1 arm staying as it is while both layouts are judged against
 * the same night's data. This one is a pure fold over the payload with no state and no rendering, so
 * a copy cannot drift in behaviour without one of the two suites noticing — and the shared parts
 * that <em>do</em> carry the judgement ({@code DISPLAY_ORDER}, {@code resolveRegionDisplay}) are
 * imported rather than copied, so the two orderings cannot disagree about what "best" means.
 * Reconverge after the flag default flips.
 *
 * @param {Array} upcomingEvents [{date, targetType}]
 * @param {Array} briefingDays   briefing.days
 * @returns {string[]} region names, best verdict first
 */
export function sortRegionsByBestVerdict(upcomingEvents, briefingDays) {
  const regionBest = new Map();
  const regionSeen = [];

  for (const { date, targetType } of upcomingEvents || []) {
    const day = (briefingDays || []).find((d) => d.date === date);
    if (!day) continue;
    const es = (day.eventSummaries || []).find((e) => e.targetType === targetType);
    if (!es) continue;
    for (const region of es.regions || []) {
      const name = region.regionName;
      const v = DISPLAY_ORDER[resolveRegionDisplay(region)] ?? 4;
      if (!regionBest.has(name)) {
        regionBest.set(name, v);
        regionSeen.push(name);
      } else if (v < regionBest.get(name)) {
        regionBest.set(name, v);
      }
    }
  }

  return regionSeen.sort((a, b) => {
    const diff = (regionBest.get(a) ?? 4) - (regionBest.get(b) ?? 4);
    return diff !== 0 ? diff : a.localeCompare(b);
  });
}
