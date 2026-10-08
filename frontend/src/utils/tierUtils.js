/**
 * Quality tier hierarchy (lower number = higher quality):
 *   0  go-king    WORTH_IT + king tide signal
 *   1  go-tide    WORTH_IT + any tide-aligned location (no king)
 *   2  go-plain   WORTH_IT, no tide alignment
 *   3  ma-tide    MAYBE + any tide-aligned location
 *   4  ma-plain   MAYBE, no tide alignment
 *   5  standdown  STAND_DOWN / AWAITING
 */

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
 * <p>Whether a slot's tide suits its spot is not a slot field any more: it is served per window as
 * {@code window.tideFacts} (docs/engineering/window-tide-facts-plan.md), so the caller hands in the
 * lookup for the window this region belongs to. There is no default — a caller that has no facts
 * says so with a predicate that answers false.
 *
 * @param {{ displayVerdict?: string, verdict?: string, tideHighlights?: string[], slots?: Array<object> }} region
 * @param {function(object): boolean} alignedOf true when the given slot's tide suits it in this window
 * @returns {number} 0–5
 */
export function computeCellTier(region, alignedOf) {
  if (!region) return 5;

  const dv = resolveRegionDisplay(region);

  if (dv === 'STAND_DOWN' || dv === 'AWAITING') return 5;

  const hasKingTide = (region.tideHighlights || [])
    .some((h) => h.toLowerCase().includes('king'));

  const hasTideAligned = (region.slots || [])
    .some((s) => alignedOf(s) === true);

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
