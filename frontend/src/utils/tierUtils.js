/**
 * Quality tier constants — each tier is a 0-based index matching the 6 slider positions.
 *
 * Tier hierarchy (lower number = higher quality):
 *   0  go-king    WORTH_IT + king tide signal
 *   1  go-tide    WORTH_IT + any tide-aligned location (no king)
 *   2  go-plain   WORTH_IT, no tide alignment
 *   3  ma-tide    MAYBE + any tide-aligned location
 *   4  ma-plain   MAYBE, no tide alignment
 *   5  standdown  STAND_DOWN / AWAITING
 */
export const TIER_KEYS = ['go-king', 'go-tide', 'go-plain', 'ma-tide', 'ma-plain', 'standdown'];

export const TIER_LABELS = [
  'Worth it + king tide only',
  'Worth it + tide-aligned',
  'All worth it',
  'Maybe + tide-aligned',
  'All maybe',
  'Everything including stand down',
];

/**
 * Resolves the display signal for a region. Prefers the backend-provided
 * {@code displayVerdict} (which already incorporates Claude ratings when
 * scored) and falls back to mapping the triage {@code verdict} otherwise.
 *
 * @param {{ displayVerdict?: string, verdict?: string }} region
 * @returns {'WORTH_IT' | 'MAYBE' | 'STAND_DOWN' | 'AWAITING'}
 */
export function resolveRegionDisplay(region) {
  if (!region) return 'AWAITING';
  if (region.displayVerdict) return region.displayVerdict;
  switch (region.verdict) {
    case 'GO': return 'WORTH_IT';
    case 'MARGINAL': return 'MAYBE';
    case 'STANDDOWN': return 'STAND_DOWN';
    default: return 'AWAITING';
  }
}

/**
 * Returns the quality tier (0–5) for a briefing region object.
 *
 * @param {{ displayVerdict?: string, verdict?: string, tideHighlights?: string[], slots?: Array<{ tideAligned?: boolean }> }} region
 * @returns {number} 0–5
 */
export function computeCellTier(region) {
  if (!region) return 5;

  const dv = resolveRegionDisplay(region);

  if (dv === 'STAND_DOWN' || dv === 'AWAITING') return 5;

  const hasKingTide = (region.tideHighlights || [])
    .some((h) => h.toLowerCase().includes('king'));

  const hasTideAligned = (region.slots || [])
    .some((s) => s.tideAligned === true);

  if (dv === 'WORTH_IT') {
    if (hasKingTide) return 0;
    if (hasTideAligned) return 1;
    return 2;
  }

  if (dv === 'MAYBE') {
    if (hasTideAligned) return 3;
    return 4;
  }

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
