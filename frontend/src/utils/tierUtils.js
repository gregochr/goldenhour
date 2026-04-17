/**
 * Quality tier constants — each tier is a 0-based index matching the 6 slider positions.
 *
 * Tier hierarchy (lower number = higher quality):
 *   0  go-king    GO verdict + king tide signal
 *   1  go-tide    GO verdict + any tide-aligned location (no king)
 *   2  go-plain   GO verdict, no tide alignment
 *   3  ma-tide    MARGINAL verdict + any tide-aligned location
 *   4  ma-plain   MARGINAL verdict, no tide alignment
 *   5  standdown  STANDDOWN verdict
 */
export const TIER_KEYS = ['go-king', 'go-tide', 'go-plain', 'ma-tide', 'ma-plain', 'standdown'];

export const TIER_LABELS = [
  'WORTH IT + king tide',
  'WORTH IT + any tide alignment',
  'All WORTH IT conditions',
  'MAYBE + tide aligned',
  'All MAYBE included',
  'Everything including standdown',
];

/**
 * Returns the quality tier (0–5) for a briefing region object.
 *
 * @param {{ verdict: string, tideHighlights?: string[], slots?: Array<{ tideAligned?: boolean }> }} region
 * @returns {number} 0–5
 */
export function computeCellTier(region) {
  if (!region) return 5;

  const verdict = region.verdict;

  if (verdict === 'STANDDOWN') return 5;

  const hasKingTide = (region.tideHighlights || [])
    .some((h) => h.toLowerCase().includes('king'));

  const hasTideAligned = (region.slots || [])
    .some((s) => s.tideAligned === true);

  if (verdict === 'GO') {
    if (hasKingTide) return 0;
    if (hasTideAligned) return 1;
    return 2;
  }

  if (verdict === 'MARGINAL') {
    if (hasTideAligned) return 3;
    return 4;
  }

  // Unknown verdict — treat as standdown
  return 5;
}

/**
 * Returns the quality tier for an aurora grid cell.
 *
 * @param {{ verdict?: string }} auroraRegion  aurora region summary (from briefing)
 * @param {boolean}              isTomorrow    true for informational tomorrow cells
 * @returns {number} 0–6 (6 = disabled / no dark-sky locations)
 */
export function computeAuroraCellTier(auroraRegion, isTomorrow) {
  if (!auroraRegion || !auroraRegion.verdict) {
    // Tomorrow without per-region data — show at "All GO conditions" tier
    if (isTomorrow) return 2;
    return 6; // disabled (no dark sky)
  }
  if (auroraRegion.verdict === 'GO') return isTomorrow ? 2 : 0;
  if (auroraRegion.verdict === 'STANDDOWN') return 5;
  // Tomorrow informational fallback
  if (isTomorrow) return 2;
  return 5;
}

/**
 * Returns true when a cell with the given tier should be visible at the
 * current slider position (qualityTier).
 *
 * The slider is inclusive: position N shows all tiers 0 through N.
 *
 * @param {number} cellTier - 0–5
 * @param {number} qualityTier - 0–5 (current slider position)
 * @returns {boolean}
 */
export function isCellVisible(cellTier, qualityTier) {
  return cellTier <= qualityTier;
}
