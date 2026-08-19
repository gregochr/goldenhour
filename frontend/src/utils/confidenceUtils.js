/**
 * The confidence channel — one quiet, uniform visual layer read across the whole Plan
 * screen (Best Bet, grid cells, summary pills). It sits ALONGSIDE the star/quality signal,
 * never replacing it: a far-horizon "Worth it" should read visibly more provisional than a
 * same-day one, because weather certainty decays fast past T+1.
 *
 * This is deliberately NOT in tierUtils.js — that file owns the quality/star axis. Confidence
 * is a separate axis with its own home.
 */

/** Valid confidence tiers, most → least certain. */
export const CONFIDENCE_TIERS = ['high', 'medium', 'low'];

const HORIZON_HIGH_MAX_DAYS = 1; // T+0, T+1
const HORIZON_MEDIUM_MAX_DAYS = 3; // T+2, T+3

/**
 * Whole days from {@code todayStr} to {@code dateStr}, both ISO {@code YYYY-MM-DD}.
 * Computed in UTC to avoid DST/local-midnight drift (mirrors HotTopicStrip.leadDayWord).
 *
 * @param {string} dateStr  target date (YYYY-MM-DD)
 * @param {string} todayStr reference "today" (YYYY-MM-DD)
 * @returns {number|null} integer days ahead (may be negative), or null if either is invalid
 */
export function daysOut(dateStr, todayStr) {
  if (!dateStr || !todayStr) return null;
  const a = dateStr.split('-').map(Number);
  const b = todayStr.split('-').map(Number);
  if (a.length !== 3 || b.length !== 3 || a.some(Number.isNaN) || b.some(Number.isNaN)) return null;
  const target = Date.UTC(a[0], a[1] - 1, a[2]);
  const today = Date.UTC(b[0], b[1] - 1, b[2]);
  return Math.round((target - today) / 86400000);
}

/**
 * Horizon-only confidence fallback (used when the backend field is absent — e.g. a briefing
 * cached before the field existed). Mirrors the backend ConfidenceDeriver's horizon base.
 *
 * @param {number|null} days days ahead
 * @returns {'high'|'medium'|'low'|null} tier, or null when {@code days} is unknown
 */
export function horizonConfidence(days) {
  if (days == null) return null;
  if (days <= HORIZON_HIGH_MAX_DAYS) return 'high';
  if (days <= HORIZON_MEDIUM_MAX_DAYS) return 'medium';
  return 'low';
}

/**
 * The strongest tier the FALLBACK may claim. An absent field means one of two things and the
 * frontend cannot tell them apart: a payload cached before the field existed, or a live payload
 * where the backend deliberately derived nothing (ConfidenceDeriver returns null for a region with
 * zero coverage, precisely so it reads provisional). Uncapped, both used to resolve to 'high' at
 * T+0 — so an unscored four-location region rendered at full confidence, which is the exact
 * falsely-confident reading this channel exists to prevent. An inference from the horizon alone
 * never earns the top tier.
 */
const MAX_INFERRED_TIER = 'medium';

/**
 * Resolves the confidence tier for a rated element, fail-soft. Prefers the backend-supplied
 * {@code confidence} (derived from horizon + rating spread + roster size server-side); falls back
 * to a horizon-only tier when the field is missing, capped at {@link MAX_INFERRED_TIER}; defaults
 * to 'medium' when nothing is known. Never throws.
 *
 * <p>Under-reporting is the safe direction here: a legacy payload for a large, fully-scored region
 * at T+0 now reads MEDIUM where it once read HIGH. That is a quieter cell, not a wrong one.
 *
 * @param {object|null} rated an object that may carry a {@code confidence} string
 * @param {number|null} days  days ahead, for the fallback
 * @returns {'high'|'medium'|'low'} the resolved tier
 */
export function resolveConfidence(rated, days) {
  const supplied = rated && rated.confidence;
  if (CONFIDENCE_TIERS.includes(supplied)) return supplied;
  const inferred = horizonConfidence(days) || MAX_INFERRED_TIER;
  // CONFIDENCE_TIERS runs most → least certain, so a lower index is a stronger claim.
  return CONFIDENCE_TIERS.indexOf(inferred) < CONFIDENCE_TIERS.indexOf(MAX_INFERRED_TIER)
    ? MAX_INFERRED_TIER
    : inferred;
}

/**
 * The quiet visual treatment per tier. {@code fillScale} multiplies the verdict fill/border
 * alpha (never the star pill); {@code provisional} flags the low tier for a small marker.
 */
export const CONFIDENCE_TREATMENT = {
  high: { fillScale: 1.0, provisional: false, label: 'High confidence' },
  medium: { fillScale: 0.72, provisional: false, label: 'Medium confidence' },
  low: { fillScale: 0.5, provisional: true, label: 'Low confidence · provisional' },
};

/**
 * The treatment for a tier, defaulting to the neutral 'medium' for an unknown tier.
 *
 * @param {string} tier confidence tier
 * @returns {{fillScale:number, provisional:boolean, label:string}} the treatment
 */
export function confidenceTreatment(tier) {
  return CONFIDENCE_TREATMENT[tier] || CONFIDENCE_TREATMENT.medium;
}

/**
 * The heat kernel's {@code conf} scalar for a confidence tier — the same number
 * {@link confidenceTreatment} scales a verdict fill by.
 *
 * <p>One function so the two channels speak one language (plan D3). The kernel reads it as
 * {@code unc = 1 - conf} and desaturates toward grey and thins alpha as it falls, which is the
 * same statement the badge's fill decay and the provisional mark make about the same window;
 * deriving the haze from its own constants would let a later day look hazier than its badge
 * admits, or the reverse. It is deliberately NOT the design's {@code ◐ 88%} — this project's
 * confidence is three-tier and a percentage would invent precision the backend never claimed.
 *
 * <p>Unknown tiers take the neutral middle, exactly as {@link confidenceTreatment} does; pass a
 * tier through {@link resolveConfidence} first if the source may be absent.
 *
 * @param {string} tier confidence tier
 * @returns {number} the 0–1 scalar for the kernel's {@code conf} option
 */
export function confidenceScalar(tier) {
  return confidenceTreatment(tier).fillScale;
}

/**
 * Multiplies the alpha channel of an {@code rgba(r,g,b,a)} string by {@code scale} (clamped to
 * [0,1]). Returns the input unchanged when scale ≥ 1 or the string is not an rgba() literal, so
 * it is safe to apply blindly.
 *
 * @param {string} rgba  an "rgba(r,g,b,a)" colour string
 * @param {number} scale multiplier for the alpha
 * @returns {string} the scaled colour, or the input unchanged
 */
export function scaleRgbaAlpha(rgba, scale) {
  if (typeof rgba !== 'string' || !(scale < 1)) return rgba;
  const m = rgba.match(/^rgba\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*\)$/i);
  if (!m) return rgba;
  const alpha = Math.max(0, Math.min(1, parseFloat(m[4]) * scale));
  return `rgba(${m[1]}, ${m[2]}, ${m[3]}, ${Number(alpha.toFixed(3))})`;
}
