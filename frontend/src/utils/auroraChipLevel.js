/**
 * The aurora kind chip's colour level: the aurora banner's own rule, in one place.
 *
 * The banner picks `SEVERITY[level] ?? SEVERITY.MODERATE`, and SEVERE / EXTREME take STRONG's row
 * (`AuroraBanner.jsx`), so a level is "strong" when it is STRONG, SEVERE or EXTREME and "moderate"
 * otherwise (including no alert at all).
 */
const STRONG_LEVELS = new Set(['STRONG', 'SEVERE', 'EXTREME']);

/**
 * @param {?string} level the alert's level (`status.level` or `liteAlert.level`)
 * @returns {'strong'|'moderate'}
 */
export function auroraSeverityTier(level) {
  return STRONG_LEVELS.has(level) ? 'strong' : 'moderate';
}

/**
 * The `data-aurora-level` of a window row's kind chip: null for anything but an AURORA row; "strong"
 * only for the aurora row of the night the alert describes, at a strong level; else "moderate".
 *
 * @param {{eventType?: string, date?: string}} row
 * @param {?string} alertNight  the night the alert describes (`resolveAuroraNight`), YYYY-MM-DD
 * @param {?string} alertLevel  the alert's level
 * @returns {'strong'|'moderate'|null}
 */
export function auroraChipLevel(row, alertNight, alertLevel) {
  if (row?.eventType !== 'AURORA') return null;
  return alertNight != null && row.date === alertNight ? auroraSeverityTier(alertLevel) : 'moderate';
}
