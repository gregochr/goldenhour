/**
 * The v2 score ramp — one colour language for the heat field, the row maps, the strip swatches,
 * the v2 map markers (P4) and the v2 spot badges (P5).
 *
 * <p>Why this module exists rather than a second copy of the stops in each consumer: the heat
 * field is painted on a canvas from JS numbers while the verdict pills are painted by CSS from
 * theme tokens, and those two can desynchronise silently — nothing fails, the map just stops
 * agreeing with the badge beside it. So the hex literals live here, exactly once. The matching
 * `--color-verdict-*` tokens in `index.css` should gain a comment pointing back at this file when
 * P2 first adds heat tokens beside them — P0 adds no CSS, so there is nothing to annotate yet.
 *
 * <p>The verdict stops are the app's existing verdict palette, which is a happy accident worth
 * keeping: a reader who has learned that amber means "marginal" on a pill reads the same amber
 * correctly in the field. Stops 1 and 4 fill in the ends the verdict vocabulary has no word for.
 *
 * <p>This module now carries a second stop list — a temperature ramp, cold blue through gold to
 * hot orange-red — and a module-level {@link setMode}/{@link getMode} switch between them. The
 * switch lives here, not per-consumer, so the field, the markers and the map legend can never
 * disagree about what a colour means (`docs/engineering/heat-scale-unification-plan.md`, rule 1).
 * {@link getMode} defaults to `'verdict'`, so nothing downstream repaints until a later stage
 * flips it.
 *
 * <p>This is the map's only SCORE colour language now. It once stood beside a separate five-bucket
 * table ({@code markerUtils.RATING_COLOURS}) that belonged to v1, the pilot's frozen comparison
 * control, kept deliberately un-ramped so v1 rendered byte-identically until the flag flipped.
 * v1 was retired along with the rest of that UI estate, and its table went with it — every rated
 * marker, cluster bubble and star-filter swatch, in every view, now paints from these stops.
 * Stand-down and no-data markers are not scores and are not on this ramp at all.
 */

/**
 * The verdict ramp's five stops, lowest score first — today's default. Hex is the single literal
 * for each stop; the numeric triples used by the canvas are derived from it below, so an edit
 * here cannot leave the two representations disagreeing.
 */
export const STOPS_VERDICT = [
  { score: 1, hex: '#B03A2A' }, // darker than stand-down: 1★ is worse than "don't go"
  { score: 2, hex: '#C8452F' }, // --color-verdict-standdown
  { score: 3, hex: '#E0A542' }, // --color-verdict-marginal
  { score: 4, hex: '#B0BE74' }, // between marginal and go
  { score: 5, hex: '#8AAE72' }, // --color-verdict-go
];

/**
 * The temperature ramp's eight stops — cold blue at 1★ through gold at 3★ to hot orange-red at
 * 5★, from the design handoff's reference kernel (`docs/design/temperature-scale/heat-field.js`).
 *
 * <p>The uneven spacing is load-bearing and must not be regularised: regional means occupy
 * roughly 1.9–4.6, so evenly spaced stops would spend the blue and the red on values that never
 * survive the blur, rendering every night the same orange. `2.2` is held dark so a bone marker
 * label clears 4.5:1 against it — lightening it breaks marker contrast. `3` exists as its own
 * stop because `rating` is an integer and 3★ is likely the commonest value; interpolating
 * 2.8→3.2 put it on a dun khaki.
 */
export const STOPS_TEMP = [
  { score: 1, hex: '#3A5C70' },
  { score: 2.2, hex: '#506878' },
  { score: 2.8, hex: '#928C80' },
  { score: 3, hex: '#C49440' },
  { score: 3.2, hex: '#C99230' },
  { score: 3.9, hex: '#DF6B2A' },
  { score: 4.3, hex: '#D63A26' },
  { score: 5, hex: '#F26034' },
];

/** Lowest score the ramps are defined for; anything below clamps to it. Both lists span 1–5. */
export const RAMP_MIN = STOPS_VERDICT[0].score;
/** Highest score the ramps are defined for; anything above clamps to it. Both lists span 1–5. */
export const RAMP_MAX = STOPS_VERDICT[STOPS_VERDICT.length - 1].score;

/**
 * An [r, g, b] triple as a CSS colour string. Named for the triple it takes, not the function it
 * emits — it always emits `rgba()`, with alpha 1 when none is given.
 *
 * <p>It lives beside the ramp rather than in `heatField.js` (where the prototype kept it) because
 * it is a colour concern with no geometry in it, and because the DOM consumers that need it —
 * P3's region swatches, P5's `spotBadgeStyle` — would otherwise import the kernel and drag its
 * static `d3-geo` import into a module graph that only wanted a string template. `heatField.js`
 * re-exports it so the ported surface is unchanged.
 *
 * @param {number[]} c   [r, g, b]
 * @param {number} [a]   alpha 0–1; omitted means opaque
 * @returns {string} an `rgba(...)` string
 */
export function rgb(c, a) {
  return `rgba(${c[0]},${c[1]},${c[2]},${a == null ? 1 : a})`;
}

/** Parses '#rrggbb' into [r, g, b]. */
function parseHex(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

// One precomputed [r, g, b] array per stop list, so switching modes never recomputes them —
// `rampRgb` is called once per grid cell by the heat kernel and must stay allocation-light.
const STOP_RGB_VERDICT = STOPS_VERDICT.map((s) => parseHex(s.hex));
const STOP_RGB_TEMP = STOPS_TEMP.map((s) => parseHex(s.hex));

/** Constrains {@code v} to [{@code a}, {@code b}]. Private: heatField.js owns the exported one. */
function clamp(v, a, b) {
  return Math.max(a, Math.min(b, v));
}

/**
 * Which stop list {@link rampRgb}/{@link rampHex} read. Module state rather than a parameter, so
 * every caller across the app — the field, the markers, the map legend — shares one answer to
 * "what does this colour mean" without threading a mode through every call site.
 */
let MODE = 'verdict';

/**
 * Switches the active ramp. Anything other than `'temp'` selects `'verdict'` — an unrecognised
 * value must never silently select the not-yet-shipped ramp.
 *
 * @param {string} m `'temp'` or `'verdict'`
 */
export function setMode(m) {
  MODE = m === 'temp' ? 'temp' : 'verdict';
}

/** @returns {string} the active mode, `'temp'` or `'verdict'` */
export function getMode() {
  return MODE;
}

/** @returns {Array<{score: number, hex: string}>} the active mode's stop list */
function activeStops() {
  return MODE === 'temp' ? STOPS_TEMP : STOPS_VERDICT;
}

/** @returns {number[][]} the active mode's precomputed [r, g, b] triples */
function activeStopRgb() {
  return MODE === 'temp' ? STOP_RGB_TEMP : STOP_RGB_VERDICT;
}

/**
 * The ramp itself: a 1–5 score to an [r, g, b] triple, linearly interpolated between the active
 * mode's stops and clamped at both ends. Called once per grid cell by the heat kernel, so it
 * stays allocation-light and does no validation beyond the clamp.
 *
 * @param {number} score 1–5; values outside the range clamp rather than throwing
 * @returns {number[]} [r, g, b], each 0–255 and integral
 */
export function rampRgb(score) {
  const stops = activeStops();
  const stopRgb = activeStopRgb();
  // A non-finite score resolves to the BOTTOM of the ramp, never the top. `clamp` alone does not
  // do this: `Math.max(1, Math.min(5, NaN))` is NaN, and a NaN then fails every `<=` test — so a
  // segment search would fall out of its loop and return the LAST stop, painting an undefined,
  // NaN or missing score the same green as a 5★ forecast. An unknown reading must never render
  // as the best one; under-reporting is the safe direction. Callers that mean "no score" should
  // not be calling the ramp at all — the kernel's own null-score seam is P1's join to filter.
  const s = Number.isFinite(score) ? clamp(score, RAMP_MIN, RAMP_MAX) : RAMP_MIN;
  // Locate the segment by walking rather than by falling out of a loop, so there is exactly one
  // return and no unreachable branch that could hand a caller the module's own storage.
  let i = 0;
  while (i < stopRgb.length - 2 && s > stops[i + 1].score) i += 1;
  const ca = stopRgb[i];
  const cb = stopRgb[i + 1];
  const f = (s - stops[i].score) / (stops[i + 1].score - stops[i].score);
  return [0, 1, 2].map((k) => Math.round(ca[k] + (cb[k] - ca[k]) * f));
}

/**
 * The same ramp as a CSS hex string, for the DOM-side consumers (swatches, badges, markers) that
 * cannot use a numeric triple.
 *
 * @param {number} score 1–5, clamped as {@link rampRgb}
 * @returns {string} '#rrggbb'
 */
export function rampHex(score) {
  // Upper case so a returned string is directly comparable with the active mode's stop literals.
  // The zero-pad cannot fire for any verdict-ramp score — its darkest channel is 42 (0x2A) — and
  // is kept only so the function stays correct for a ramp whose darkest channel is smaller.
  return `#${rampRgb(score)
    .map((c) => c.toString(16).padStart(2, '0'))
    .join('')}`.toUpperCase();
}

/**
 * Maps a 0–100 metric onto the ramp's 1–5 score domain, so a caller can compose
 * `rampHex(scoreFromPercent(v, lo, hi))` to colour a percentage-based reading on the same ramp as
 * a star rating. Returns a number, not a colour — {@link rampHex}/{@link rampRgb} already take a
 * score, so this stays a pure domain mapping.
 *
 * <p>The reference kernel's equivalent (`rampPct`) returns a colour directly; this app keeps
 * domain-mapping and colour-lookup separate on purpose; the different name is deliberate.
 *
 * @param {number} value the metric's raw reading
 * @param {number} lo the value that maps to a score of 1
 * @param {number} hi the value that maps to a score of 5
 * @returns {number} 1–5, clamped outside [lo, hi]
 */
export function scoreFromPercent(value, lo, hi) {
  return 1 + clamp((value - lo) / (hi - lo), 0, 1) * 4;
}
