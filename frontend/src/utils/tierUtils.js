/**
 * Resolves the display signal for a region. Prefers the backend-provided
 * {@code displayVerdict} (which already incorporates Claude ratings when
 * scored) and falls back to mapping the triage {@code verdict} otherwise.
 *
 * @param {{ displayVerdict?: string, verdict?: string }} region
 * @returns {'WORTH_IT' | 'MAYBE' | 'STAND_DOWN' | 'UNRATED' | 'AWAITING'}
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

