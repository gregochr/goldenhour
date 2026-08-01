/**
 * Shared geometry for the 24-hour local-day charts on the Hot Topics strip.
 *
 * Two charts now sit in that strip — the tide run's curve and the storm surge's — and both plot a
 * Europe/London local day against the same axis, so a reader comparing adjacent pills is comparing
 * the same x. That shared axis is the only thing extracted here.
 *
 * DELIBERATELY PURE. Constants and side-effect-free functions only: no JSX, no component, no
 * curve-building. The two charts differ in the one place that matters — the tide row maps a
 * boolean (`high ? HIGH_Y : LOW_Y`), the surge row maps metres through a domain→range scale — and
 * a shared "chart" abstraction would have to smear over exactly that difference. The JSX frame
 * (night wash, solar rules, label rail) stays duplicated for now, on purpose: extracting it in the
 * same change that introduces the second chart would leave nothing able to prove the extraction was
 * behaviour-preserving. `TideRunRow`'s existing tests passing with zero edits IS that proof.
 */

/**
 * Chart geometry. The viewBox is a 1000-unit day against a 32-unit height, stretched to the
 * container by `preserveAspectRatio="none"` — the curve carries shape, not scale.
 */
export const VIEW_W = 1000;
export const VIEW_H = 32;
export const MINUTES_PER_DAY = 1440;
export const SAMPLE_MINUTES = 8;

/**
 * A label within this many minutes of a sun marker is suppressed: the two would overprint, and the
 * verdict already names that moment in words.
 */
export const LABEL_COLLISION_MINUTES = 45;

/** Inside this percentage of either edge, a label is pinned flush instead of centre-transformed. */
export const EDGE_PERCENT = 9;

/**
 * `"05:44"` → minutes past local midnight.
 *
 * @param {string} clock a 24-hour `HH:mm` clock time
 * @returns {number} minutes past local midnight
 */
export function toMinutes(clock) {
  return Number(clock.slice(0, 2)) * 60 + Number(clock.slice(3, 5));
}

/**
 * Percentage across the 24-hour axis for a clock time.
 *
 * @param {string} clock a 24-hour `HH:mm` clock time
 * @returns {number} 0–100 across the local day
 */
export function percentOf(clock) {
  return (toMinutes(clock) / MINUTES_PER_DAY) * 100;
}

/**
 * Positioning for a label at `x` percent across the axis, pinned flush at either edge so it cannot
 * overflow the chart.
 *
 * @param {number} x percentage across the axis
 * @returns {object} inline style fragment
 */
export function edgePin(x) {
  if (x < EDGE_PERCENT) {
    return { left: 0, transform: 'none' };
  }
  if (x > 100 - EDGE_PERCENT) {
    return { right: 0, left: 'auto', transform: 'none' };
  }
  return {};
}
