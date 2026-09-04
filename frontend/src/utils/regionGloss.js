/**
 * The briefing's per-window region glosses, indexed for lookup.
 *
 * <p>Lives in its own module rather than in `mapCallout.js`, where it was written, because BOTH the
 * map callout and the location sheet read it — and the sheet must, since the callout's clamped prose
 * is now a button into the sheet (increment §1). If only the callout had the fallback, a location
 * with no per-location summary would show the region gloss on the card and nothing at all on the
 * sheet it opens, which is exactly the information loss increment §2's "strict subset" rule forbids.
 * `mapCallout.js` imports `locationSheet.js`, so the sheet cannot import it back.
 */
/**
 * Each window's region gloss, keyed the way {@code locationSheet.buildSlotIndex} keys its own
 * per-window join — {@code date|targetType|regionName} — because the reason prose's fallback is a
 * REGION's gloss (plan §3 P9: "fallback: region gloss"), never a location's, and a region can carry
 * a different gloss on every window it appears in.
 *
 * <p>⚠️ <b>Pre-existing bug, found by adversarial review against #737 (map-tab-v2-plan.md §3 P11)
 * and fixed in #739</b> (this note landed via #740, whose independent copy of the same fix
 * deduplicated away on rebase). This read {@code region?.name}/{@code region.name} since P9 — but
 * the served {@code BriefingRegion} record has no {@code name} field at all; its own field is
 * {@code regionName} (confirmed against every sibling join on this arm — `heatSpots.js`,
 * `windowFirstRegions.js` — which have always used the correct field). So every region read here was
 * {@code undefined}, the {@code !region?.name} guard skipped every region on every call, and this
 * index has been silently EMPTY against real data since it shipped: the callout's reason-prose
 * fallback ("fallback: region gloss") never actually supplied one. The bug was masked because
 * `mapCallout.test.js`'s own fixture used the identical wrong field (`{ name: … }`), so the suite
 * stayed green while the feature was dead — the fixture pre-satisfied its own (wrong) predicate
 * rather than exercising the served shape.
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
