/**
 * Pure geometry for the tide chronology card's sparkline (design README §4 "Tide sparkline", plan
 * §6b). The reference implementation is {@code docs/design/coming-up/Coming Up.html}'s
 * {@code wavePath()}/{@code bar()} functions — this module is that maths, named and testable, with
 * one deliberate change: the design's ghost wave compares against a hardcoded "3.3 m national
 * average"; this reads the representative port's own {@code avgRangeMetres} instead, recovered from
 * the served delta identity ({@code range - delta}), per plan §11.6.
 *
 * <h2>Why this is not `chart/solarDayGeometry.js`</h2>
 *
 * <p>That module's axis is a Europe/London CLOCK DAY — 1440 minutes, sampled by clock time, used by
 * the (now-superseded) Hot Topics tide/surge charts. This sparkline has no relationship to a clock
 * at all: it is a FIXED-PHASE cosine over a 62-unit period centred at x=41, whose only inputs are a
 * run's range/delta/phase. There is no shared axis, no shared sample step and no shared constant to
 * extract — reusing that module here would mean importing unrelated clock-parsing helpers for
 * nothing this chart needs. Recorded as the P3b phase-log's reuse decision.
 *
 * <h2>DELIBERATELY PURE</h2>
 *
 * <p>No JSX, no component. {@code ComingUpTideSparkline.jsx} is the one caller.
 */

/** The sparkline's own viewBox — matches the design's `viewBox="0 0 104 24"` exactly. */
export const SPARKLINE_VIEW_W = 104;
export const SPARKLINE_VIEW_H = 24;

/** The cosine's period in viewBox units — the design's `2π(x-41)/62`. */
export const SPARKLINE_PERIOD = 62;

/** Where the marked water sits on the x-axis — the design's `x=41`. */
export const SPARKLINE_MARKER_X = 41;

/** The axis (no-range) y-coordinate — the design's `y=12`, half the 24-unit height. */
export const SPARKLINE_AXIS_Y = 12;

/** How far apart samples are taken along x — the design's `x+=2`. */
export const SPARKLINE_SAMPLE_STEP = 2;

/**
 * The ghost wave's fixed amplitude — "an average tide", the design's `ga=3`. Deliberately a
 * constant, not derived from `portAvg`: the ghost wave's whole point is to draw what "average" looks
 * like, so it cannot itself scale with how far this run is from average.
 */
export const SPARKLINE_GHOST_AMPLITUDE = 3;

/** The live wave's amplitude floor — the design's `3 + …`; a below-average run still reads as a wave. */
export const SPARKLINE_MIN_AMPLITUDE = 3;

/** The live wave's amplitude ceiling — the design's `Math.min(10, …)`. */
export const SPARKLINE_MAX_AMPLITUDE = 10;

/** Metres-of-range-above-average to amplitude-units scale — the design's `* 3.5`. */
export const SPARKLINE_AMPLITUDE_SCALE = 3.5;

/** The marker circle's radius — the design's `r="2.4"`. */
export const SPARKLINE_MARKER_RADIUS = 2.4;

/**
 * The live wave's amplitude for a run's delta from its port's own average — the design's
 * `amp = min(10, 3 + (range - AVG) * 3.5)`, with `range - AVG` replaced by the served `delta`
 * directly (plan §11.6: never the design's hardcoded 3.3 m; and `delta` already IS `range - portAvg`
 * — the served identity, not a value this function has to re-derive from a separately-passed
 * `portAvg` the way an earlier draft did).
 *
 * <p><b>Clamped at both ends, not just the ceiling.</b> The design's own formula has no floor, and
 * an early draft of this function copied that hole — reachable whenever `delta < -0.857`, which
 * turns the amplitude negative and, because {@link tideWavePath}/{@link tideMarkerY} both multiply
 * by amplitude, silently inverts the wave and drops a marked HIGH water's marker below the axis: the
 * picture would say "low" while the facts text beside it says "high". A spring/king run's peak is
 * above its own port average by construction, so a negative delta should never occur in practice —
 * but the floor costs nothing and turns a silent phase-sign inversion into a flat, honest wave
 * instead of a wrong one.
 *
 * @param {number} delta this run's range minus its port's own average range, metres (signed)
 * @returns {number} amplitude in viewBox units, always within
 *          `[SPARKLINE_MIN_AMPLITUDE, SPARKLINE_MAX_AMPLITUDE]`
 */
export function tideSparklineAmplitude(delta) {
  return Math.max(
    SPARKLINE_MIN_AMPLITUDE,
    Math.min(SPARKLINE_MAX_AMPLITUDE, SPARKLINE_MIN_AMPLITUDE + delta * SPARKLINE_AMPLITUDE_SCALE),
  );
}

/**
 * The wave's path data — the design's `wavePath(amp, low)`. Sign inverts for a marked LOW water: a
 * high-water wave dips first (`-amp*cos`), a low-water wave rises first (`amp*cos`), which is what
 * carries the "this is the low, not the high" fact into the picture (plan §6b).
 *
 * @param {number}  amplitude amplitude in viewBox units
 * @param {boolean} isLow     true when the marked water is a low (phase === 'LW')
 * @returns {string} an SVG `<path>` `d` attribute, `M`+`L` segments across the whole viewBox width
 */
export function tideWavePath(amplitude, isLow) {
  let d = '';
  for (let x = 0; x <= SPARKLINE_VIEW_W; x += SPARKLINE_SAMPLE_STEP) {
    const c = Math.cos((2 * Math.PI * (x - SPARKLINE_MARKER_X)) / SPARKLINE_PERIOD);
    const y = SPARKLINE_AXIS_Y + (isLow ? amplitude * c : -amplitude * c);
    d += (x === 0 ? 'M' : 'L') + x + ' ' + y.toFixed(2);
  }
  return d;
}

/**
 * The marker's y-coordinate — the wave's own peak/trough at `x = SPARKLINE_MARKER_X`, the design's
 * `my = 12 + (low ? amp : -amp)`. Shared by the marker circle and its dashed lead-line.
 *
 * @param {number}  amplitude the LIVE wave's amplitude (never the ghost's)
 * @param {boolean} isLow     true when the marked water is a low
 * @returns {number} y-coordinate in viewBox units
 */
export function tideMarkerY(amplitude, isLow) {
  return SPARKLINE_AXIS_Y + (isLow ? amplitude : -amplitude);
}
