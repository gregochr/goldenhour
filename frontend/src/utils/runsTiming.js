/**
 * Pure helpers for the Runs Timing and Cost charts (runs-deep-dive-plan.md §4 D4).
 *
 * <p>⚠️ <strong>These are the DD-1 licensed client derivations</strong> (named in
 * {@code docs/engineering/reference/architecture-decisions.md}, "Backend-heavy"). Each is presentation of numbers the
 * server served, never a verdict the server owes:
 * <ol>
 *   <li><strong>Timing chart geometry</strong>: the histogram's bins, the Gaussian KDE curve and the rolling median
 *       over the served duration list ({@link histogram}, {@link kdeCurve}, {@link rollingMedian}).</li>
 *   <li><strong>The sunset planner replay</strong>: a what-if over served durations for a slider offset and a buffer,
 *       with the served p90 and median ({@link replayPlanner}). The server cannot pre-serve every slider position.</li>
 *   <li><strong>The cost chart's 7-day rolling average</strong> over served daily totals ({@link rollingAverage}).</li>
 * </ol>
 * The statistics themselves (median, 9-in-10, slowest, the skew flag, the per-type medians, the cost verdict) are
 * served and are never recomputed here: nothing in this file takes a percentile of the data.
 *
 * <p>Durations are in seconds unless a name says minutes; the planner works in minutes, as its slider does.
 */

/** The types that have a timing, in the handoff's order (Other has none). */
export const TIMING_TYPES = ['OVERNIGHT', 'AFTERNOON', 'EVENING', 'ASK_READY', 'BRIEFING', 'SKY_EVAL'];

/** The ranges the Timing view offers, in days; the first-listed default is {@link DEFAULT_TIMING_RANGE}. */
export const TIMING_RANGES = [30, 60, 120];
export const DEFAULT_TIMING_RANGE = 60;

/** The Cost view's periods, in days, and its default. */
export const COST_RANGES = [14, 30, 60];
export const DEFAULT_COST_RANGE = 30;

/** The histogram's candidate bin widths in seconds: 3s, 6s, 15s, 30s, 1m, 2m, 5m, 10m, 15m, 20m, 30m, 60m. */
export const BIN_WIDTHS = [3, 6, 15, 30, 60, 120, 300, 600, 900, 1200, 1800, 3600];

/** The most bins the histogram aims for. */
export const MAX_BINS = 24;

/** The rolling windows: the scatter's rolling median and the cost chart's average. */
export const ROLLING_RUNS = 7;

/** The KDE curve's number of segments across the chart. */
const KDE_STEPS = 140;

/** Silverman's rule of thumb, narrowed a little (the prototype's 0.8) so the bumps of a lumpy list stay visible. */
const KDE_SILVERMAN = 1.06;
const KDE_NARROWING = 0.8;

/**
 * The smallest bin width of {@link BIN_WIDTHS} that keeps the span within {@link MAX_BINS}, else the largest.
 *
 * @param {number} span the largest value minus the smallest, in seconds
 * @returns {number} the width in seconds
 */
export function binWidth(span) {
  return BIN_WIDTHS.find((width) => span / width <= MAX_BINS) ?? BIN_WIDTHS[BIN_WIDTHS.length - 1];
}

/** The bins a width makes for values from {@code lo} to {@code hi}, aligned to the width (the last value gets a bin). */
function binCount(lo, hi, width) {
  return Math.max(1, Math.ceil((hi - Math.floor(lo / width) * width) / width + 1e-9));
}

/**
 * The smallest width of {@link BIN_WIDTHS} whose aligned bins number at most {@link MAX_BINS}, else the largest. Unlike
 * {@link binWidth} this counts from the aligned start, so a span of 72s from 1s to 73s (25 aligned bins of 3s) steps up.
 *
 * @param {number} lo the smallest value
 * @param {number} hi the largest value
 * @returns {number} the width in seconds
 */
export function alignedBinWidth(lo, hi) {
  return BIN_WIDTHS.find((width) => binCount(lo, hi, width) <= MAX_BINS) ?? BIN_WIDTHS[BIN_WIDTHS.length - 1];
}

/**
 * Buckets durations into equal-width bins aligned to the width. Empty input gives no bins.
 *
 * @param {number[]} values the served durations in seconds
 * @returns {{width: number, start: number, bins: Array<{from: number, to: number, count: number}>}}
 */
export function histogram(values) {
  if (values.length === 0) return { width: BIN_WIDTHS[0], start: 0, bins: [] };
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const width = alignedBinWidth(lo, hi);
  const start = Math.floor(lo / width) * width;
  const count = binCount(lo, hi, width);
  const bins = Array.from({ length: count }, (_, i) => ({ from: start + i * width, to: start + (i + 1) * width, count: 0 }));
  values.forEach((value) => {
    bins[Math.min(count - 1, Math.floor((value - start) / width))].count += 1;
  });
  return { width, start, bins };
}

/**
 * A Gaussian kernel density estimate of the durations, scaled to counts per bin so it overlays the histogram. Needs at
 * least two values (one value has no spread to smooth): fewer give no curve.
 *
 * @param {number[]} values the served durations in seconds
 * @param {number} from the first x of the curve, in seconds
 * @param {number} to the last x of the curve, in seconds
 * @param {number} width the histogram's bin width in seconds (the curve's unit of area)
 * @returns {Array<{x: number, y: number}>} the curve, left to right; empty for fewer than two values
 */
export function kdeCurve(values, from, to, width) {
  const n = values.length;
  if (n < 2) return [];
  const mean = values.reduce((a, b) => a + b, 0) / n;
  const sd = Math.sqrt(values.reduce((a, b) => a + (b - mean) ** 2, 0) / n) || width;
  const h = KDE_SILVERMAN * sd * n ** -0.2 * KDE_NARROWING;
  const norm = 1 / (n * h * Math.sqrt(2 * Math.PI));
  return Array.from({ length: KDE_STEPS + 1 }, (_, i) => {
    const x = from + ((to - from) * i) / KDE_STEPS;
    const density = values.reduce((acc, t) => acc + Math.exp(-0.5 * ((x - t) / h) ** 2), 0) * norm;
    return { x, y: density * n * width };
  });
}

/**
 * The median of a list (the middle value, the mean of the two middle ones for an even count). A helper for the rolling
 * line only; the served median is never recomputed.
 *
 * @param {number[]} values the values
 * @returns {number|null} the median, null for none
 */
function middle(values) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

/**
 * The rolling median of the last {@code window} values at each point (fewer at the start).
 *
 * @param {number[]} values the series, in time order
 * @param {number} [window] the number of values per window
 * @returns {number[]} one value per input value
 */
export function rollingMedian(values, window = ROLLING_RUNS) {
  return values.map((_, i) => middle(values.slice(Math.max(0, i - window + 1), i + 1)));
}

/**
 * The rolling mean of the last {@code window} values at each point (fewer at the start).
 *
 * @param {number[]} values the series, in time order
 * @param {number} [window] the number of values per window
 * @returns {number[]} one value per input value
 */
export function rollingAverage(values, window = ROLLING_RUNS) {
  return values.map((_, i) => {
    const slice = values.slice(Math.max(0, i - window + 1), i + 1);
    return slice.reduce((a, b) => a + b, 0) / slice.length;
  });
}

/** A "nice" step for the scale: 1, 2, 2.5 or 5 times a power of ten. */
function niceStep(raw) {
  if (!(raw > 0)) return 1;
  const exponent = Math.floor(Math.log10(raw));
  const base = 10 ** exponent;
  const fraction = raw / base;
  const factor = [1, 2, 2.5, 5, 10].find((f) => fraction <= f + 1e-9);
  return factor * base;
}

/**
 * An axis maximum that is {@code ticks} nice steps, at least {@code max}: the top gridline.
 *
 * @param {number} max the largest value drawn
 * @param {number} [ticks] the number of gridlines above zero
 * @returns {{max: number, step: number}} the axis top and the step between gridlines
 */
export function niceScale(max, ticks = 4) {
  const step = niceStep(max / ticks);
  return { max: step * ticks, step };
}

/** Time-axis steps in seconds for the histogram and scatter axes. */
const TICK_STEPS = [15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14_400];

/**
 * A readable step for a duration axis: the smallest of {@link TICK_STEPS} giving at most {@code maxTicks} ticks.
 *
 * @param {number} span the axis length in seconds
 * @param {number} [maxTicks] the most ticks wanted
 * @returns {number} the step in seconds
 */
export function durationTickStep(span, maxTicks = 6) {
  return TICK_STEPS.find((step) => span / step <= maxTicks) ?? TICK_STEPS[TICK_STEPS.length - 1];
}

/**
 * A value axis for durations: the top and the step, a whole number of readable steps (seconds under a minute, then
 * {@link TICK_STEPS}) with at most {@code ticks} gridlines.
 *
 * @param {number} max the largest value drawn, in seconds
 * @param {number} [ticks] the most gridlines above zero
 * @returns {{max: number, step: number}} the axis top and step in seconds
 */
export function durationAxis(max, ticks = 4) {
  const step = max < 60 ? Math.max(1, niceStep(max / ticks)) : durationTickStep(max, ticks);
  return { max: Math.max(step, Math.ceil(max / step) * step), step };
}

/**
 * Rounds up to a multiple of five.
 *
 * @param {number} value the value
 * @returns {number} the next multiple of five at or above it
 */
export function ceil5(value) {
  return Math.ceil(value / 5) * 5;
}

/** The planner slider's range and step in minutes (20 minutes to 5 hours, in fives), and its buffers. */
export const PLANNER_MIN = 20;
export const PLANNER_MAX = 300;
export const PLANNER_STEP = 5;
export const PLANNER_BUFFERS = [60, 30, 15, 0];

/** Where the planner opens in the Scheduler: the Afternoon refresh job's panel. No prefill (runs-deep-dive-plan.md F10). */
export const SCHEDULER_HREF = '#manage/scheduler/intraday_forecast_refresh';

/**
 * Clamps a served trigger offset onto the slider (a trigger outside 20 minutes to 5 hours, or off the 5-minute grid,
 * still lands on a valid position).
 *
 * @param {number} minutes the offset in minutes before sunset
 * @returns {number} the nearest slider position
 */
export function clampOffset(minutes) {
  const snapped = Math.round(minutes / PLANNER_STEP) * PLANNER_STEP;
  return Math.min(PLANNER_MAX, Math.max(PLANNER_MIN, snapped));
}

/**
 * The sunset planner replay (DD-1, item 2): what would have happened to the served runs had the refresh started
 * {@code offsetMinutes} before sunset, with answers needed {@code bufferMinutes} before sunset.
 *
 * <p>Each run's answers are ready at {@code -offset + duration} minutes from sunset (negative is before it); it is in
 * time when that is at or before {@code -buffer}. The suggestion is the latest start that still lands 9 runs in 10:
 * {@code ceil5(buffer + p90)}, with p90 served (null for fewer than two runs, so no suggestion). Today's clock times
 * are the served {@code todaySunset} minus the offset, plus the served median and p90.
 *
 * @param {object} input
 * @param {Array<{durationSeconds: number}>} input.runs the served runs
 * @param {number} input.offsetMinutes the slider: minutes before sunset the refresh starts
 * @param {number} input.bufferMinutes minutes before sunset the answers are needed
 * @param {number|null} input.p90Seconds the served 9-in-10 duration, null for fewer than two runs
 * @param {number|null} input.medianSeconds the served median duration
 * @param {string|null} input.todaySunset the served sunset instant for today
 * @returns {{points: Array<{index: number, offsetFromSunset: number, durationSeconds: number, inTime: boolean}>,
 *   total: number, inTime: number, share: number|null, suggestionMinutes: number|null, start: Date|null,
 *   sunset: Date|null, typical: Date|null, nineInTen: Date|null}} {@code share} is null with no runs
 */
export function replayPlanner({
  runs, offsetMinutes, bufferMinutes, p90Seconds, medianSeconds, todaySunset,
}) {
  const points = runs.map((run, index) => {
    const offsetFromSunset = -offsetMinutes + run.durationSeconds / 60;
    return {
      index, offsetFromSunset, durationSeconds: run.durationSeconds, inTime: offsetFromSunset <= -bufferMinutes,
    };
  });
  const inTime = points.filter((point) => point.inTime).length;
  const sunsetMs = todaySunset ? Date.parse(todaySunset) : Number.NaN;
  const known = Number.isFinite(sunsetMs);
  const startMs = sunsetMs - offsetMinutes * 60_000;
  return {
    points,
    total: points.length,
    inTime,
    share: points.length === 0 ? null : inTime / points.length,
    suggestionMinutes: p90Seconds == null ? null : ceil5(bufferMinutes + p90Seconds / 60),
    start: known ? new Date(startMs) : null,
    sunset: known ? new Date(sunsetMs) : null,
    typical: known && medianSeconds != null ? new Date(startMs + medianSeconds * 1000) : null,
    nineInTen: known && p90Seconds != null ? new Date(startMs + p90Seconds * 1000) : null,
  };
}

/**
 * Minutes as "4h 10m", "1h", "45m": the slider's readout and the suggestion's words.
 *
 * @param {number} minutes the minutes
 * @returns {string} the text
 */
export function formatMinutes(minutes) {
  const total = Math.round(minutes);
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  if (hours === 0) return `${rest}m`;
  return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`;
}
