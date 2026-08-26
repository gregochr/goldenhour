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
 * {@link getMode}'s own bootstrap value is still `'verdict'` (see {@link MODE}'s doc); the reader
 * who has never chosen gets the temperature scale because `App.jsx` resolves that through
 * {@link resolveMode} and calls {@link setMode} once settings load, not because this module
 * defaults there itself. {@link resolveMode} is how a caller reading a stored preference tells
 * "never chosen" apart from an explicit `'verdict'`.
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
 *
 * <p><b>The hot leg descends monotonically in luminance</b> — 0.264 at 3.9, 0.203 at 4.3, 0.139
 * at 5. It used to dip to 0.175 at 4.3 and recover to 0.275 at 5, which made 4.3★ read hotter
 * than the stop above it. ⚠️ <b>Do not brighten `5` past `4.3`.</b> Gold at 3★ is already the
 * ramp's brightest point, so a bright top end gives a middling night and a great one the same
 * visual weight; the top is the ramp's deepest colour by design. Making the leg monotonic also
 * cut the sub-AA band from 13.2% in three runs to 10.2% in two — a ramp that reverses direction
 * crosses the dead zone twice.
 */
export const STOPS_TEMP = [
  { score: 1, hex: '#3A5C70' },
  { score: 2.2, hex: '#506878' },
  { score: 2.8, hex: '#928C80' },
  { score: 3, hex: '#C49440' },
  { score: 3.2, hex: '#C99230' },
  { score: 3.9, hex: '#DF6B2A' },
  { score: 4.3, hex: '#DE4826' },
  { score: 5, hex: '#C82820' },
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
 *
 * <p>Still initialised to the literal `'verdict'`, deliberately NOT {@link DEFAULT_MODE} — this is
 * the module's raw, pre-any-settings bootstrap value, alive only for the one render before
 * `App.jsx`'s settings fetch resolves and calls `setMode(resolveMode(...))`. Chasing it onto
 * {@link DEFAULT_MODE} would mean every consumer that renders `MapView`/the ramp without going
 * through that real wiring — which is most of this project's test suite — silently starts painting
 * from the temperature ramp instead of the verdict one it was written and pinned against. The one
 * user-visible cost is a same-render flash before the fetch lands, which existed before Stage 7 too
 * (the bootstrap value has never matched a stored `'temp'` choice on first paint either).
 */
let MODE = 'verdict';

/**
 * The default mode for a reader who has never explicitly chosen one — Stage 7's flip target,
 * returned by {@link resolveMode} for a `null`/`undefined` stored preference. NOT the module's own
 * bootstrap value (see {@link MODE}) — the flip is delivered by `App.jsx`/`UserSettingsModal.jsx`
 * calling `setMode(resolveMode(stored))` once settings load, not by this module defaulting to it.
 */
export const DEFAULT_MODE = 'temp';

/**
 * Switches the active ramp. Anything other than `'temp'` selects `'verdict'` — an unrecognised
 * value must never silently select the ramp a caller did not ask for.
 *
 * <p>This is the low-level setter only. It has no notion of "never chosen" — that distinction is
 * {@link resolveMode}'s job, and callers reading a stored preference should go through that first
 * (`setMode(resolveMode(stored))`), not hand a possibly-null value straight to this guard.
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

/**
 * Resolves a STORED preference (`UserSettingsResponse.mapColourScale`, or the modal's own copy of
 * it) to a mode, distinguishing "never chosen" from "chose something this build cannot read" —
 * the distinction V147 stores the column nullable-with-no-DEFAULT to preserve, and that `setMode`'s
 * single fallback branch cannot make on its own (Stage 5a's guard collapses both into `'verdict'`).
 *
 * <ul>
 *   <li>`null`/`undefined` — never chosen — resolves to {@link DEFAULT_MODE}, the new default.</li>
 *   <li>`'temp'` or `'verdict'` — an explicit choice — resolves to itself, unchanged.</li>
 *   <li>anything else — a corrupt or unrecognised value — resolves to `'verdict'`, <b>not</b> to
 *     {@link DEFAULT_MODE}. A non-null value that fails to parse is evidence of a bug somewhere
 *     upstream, not evidence of "no preference" — and defaulting the case this build cannot
 *     interpret to the newer, less-familiar ramp is the direction that could quietly surprise a
 *     reader who once made a real, valid choice. Falling back to `'verdict'`, the ramp every
 *     existing reader has already been seeing, is the safer read of data that cannot be trusted.</li>
 * </ul>
 *
 * @param {string|null|undefined} stored the raw `mapColourScale` value from settings
 * @returns {'temp'|'verdict'}
 */
export function resolveMode(stored) {
  if (stored == null) return DEFAULT_MODE;
  return stored === 'temp' || stored === 'verdict' ? stored : 'verdict';
}

/**
 * The active mode's stop list. **Private.**
 *
 * <p>It was briefly exported so the two gradient consumers (the map legend, the heat strip's
 * footer bar) would read one answer to "which ramp is live" rather than each branching on
 * {@link getMode}. {@link rampGradientCss} does that job properly — it also positions each stop by
 * its score rather than by its index, which is the defect that export left open — so nothing
 * outside this module needs the raw list any more. Callers wanting a gradient take
 * {@link rampGradientCss}; callers wanting one colour take {@link rampHex} or {@link rampRgb}.
 *
 * @returns {Array<{score: number, hex: string}>} the active mode's stop list
 */
function activeStops() {
  return MODE === 'temp' ? STOPS_TEMP : STOPS_VERDICT;
}

/**
 * The active ramp as a CSS horizontal gradient, each stop positioned by its **score** rather than
 * by its index.
 *
 * <p>Index positioning is only correct by accident. `STOPS_VERDICT` has five evenly spaced stops
 * at 1-5, so index and score coincide exactly and every gradient looks right. `STOPS_TEMP` is
 * deliberately uneven, and there the two disagree by up to **16 percentage points** — its `2.2`
 * stop belongs at 30% and index positioning puts it at 14%. That misplaces the legend against the
 * canvas it is a key for, and only in `temp` mode, so a verdict-mode test cannot see it.
 *
 * <p>Both legends call this rather than each formatting their own gradient: the Plan footer and
 * the Map key must never disagree about what a colour means, which is the rule that put `MODE` in
 * this module rather than in each consumer.
 *
 * @returns {string} a `linear-gradient(90deg, …)` value
 */
export function rampGradientCss() {
  const span = RAMP_MAX - RAMP_MIN;
  return `linear-gradient(90deg, ${activeStops()
    .map((stop) => `${stop.hex} ${(((stop.score - RAMP_MIN) / span) * 100).toFixed(1)}%`)
    .join(', ')})`;
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
 * Frozen piecewise raw-to-star tables for the two 0–100 metrics, verbatim from the design
 * handoff's reference kernel (`docs/design/temperature-scale/heat-field.js`). Each entry is
 * `[value, score]`; `starFromScore` interpolates linearly between the bracketing pair.
 *
 * <p>Replaces the linear `lo`/`hi` map ({@code scoreFromPercent}, deleted): both metrics are
 * <b>bimodal</b> — measured over 19,832 production evaluations, fiery peaks at 10–19 and again at
 * 70–79, golden at 20–29 and 70–79, both troughing at 50–59. No two-point linear map can spread a
 * bimodal population; under it 51% of fiery readings landed in the 1★ band and 72/85/100 all
 * rendered as 5★, a good evening and a great one painted identically.
 *
 * <p>Two properties are load-bearing and must not be "improved":
 *
 * <p><b>1. Frozen constants, not a running calibration.</b> Derived from one measurement and then
 * fixed — the same standing {@link STOPS_TEMP}'s uneven spacing already has. Re-measure only to
 * check the physics has not moved; do not re-anchor per season, because that makes colour relative
 * to the population and a 3.0 must look like a 3.0 in every week.
 *
 * <p><b>2. Deliberately NOT even-occupancy spacing.</b> 70% of fiery readings sit below 30 and all
 * mean the same thing — don't bother — so they share 1.3 stars, while the top third of the range
 * holds ~15% of readings and every decision worth making, and gets 1.8. Heavy concentration in the
 * low bands is the intended outcome, not a defect to tune away.
 *
 * @see starFromScore
 */
export const ANCHORS = {
  fiery: [[0, 1], [20, 1.9], [35, 2.4], [50, 2.8], [60, 3.2], [72, 4], [85, 4.7], [100, 5]],
  golden: [[0, 1], [25, 1.9], [40, 2.4], [55, 3], [70, 3.8], [85, 4.6], [100, 5]],
};

/**
 * Maps a 0–100 metric reading onto the ramp's 1–5 score domain via {@link ANCHORS}'s frozen
 * piecewise table for the given metric, so a caller can compose
 * `rampHex(starFromScore(v, metric))` to colour a percentage-based reading on the same ramp as a
 * star rating. Returns a number, not a colour — {@link rampHex}/{@link rampRgb} already take a
 * score, so this stays a pure domain mapping; the reference kernel's equivalent (`rampScore`)
 * composes the colour lookup in, this app keeps the two separate on purpose.
 *
 * <p>⚠️ An unrecognised {@code metric} throws rather than silently falling back to `fiery`
 * (the reference kernel's `ANCHORS[metric] || ANCHORS.fiery`). The two tables disagree — at
 * v=80, fiery gives 4.43 and golden 4.33 — so a typo'd metric name would otherwise return a
 * plausible wrong answer with nothing failing.
 *
 * <p>⚠️ A non-finite {@code value} resolves to the BOTTOM of the ramp (1), never the top, matching
 * {@link rampRgb}'s own rule. `clamp(v, 0, 100)` alone does not give you this: `Math.max(0,
 * Math.min(100, NaN))` is `NaN`, which fails every `v <= x1` test in the interpolation loop below
 * and would fall out the bottom to the reference kernel's trailing `return 5` — painting a missing
 * reading as the ramp's hottest, most confident colour. An unknown reading must never render as
 * the best one; under-reporting is the safe direction.
 *
 * @param {number} value the metric's raw 0–100 reading
 * @param {'fiery'|'golden'} metric which anchor table to use
 * @returns {number} 1–5
 */
export function starFromScore(value, metric) {
  // Object.hasOwn, not bracket-access truthiness: `ANCHORS[metric]` walks the prototype chain,
  // so a metric string that happens to name an Object.prototype member ('toString',
  // 'constructor', 'hasOwnProperty', …) resolves to a truthy non-array value instead of
  // `undefined` — the guard below would then silently pass, the loop body would never execute
  // against that non-array 'anchors', and control would fall through to the trailing `return 5`,
  // exactly the unthrown top-of-ramp failure this function exists to prevent.
  if (!Object.hasOwn(ANCHORS, metric)) {
    throw new Error(`starFromScore: unknown metric '${metric}'`);
  }
  const anchors = ANCHORS[metric];
  if (!Number.isFinite(value)) {
    return 1;
  }
  const v = clamp(value, 0, 100);
  for (let i = 0; i < anchors.length - 1; i += 1) {
    const [x0, y0] = anchors[i];
    const [x1, y1] = anchors[i + 1];
    if (v <= x1) {
      return y0 + (y1 - y0) * ((v - x0) / (x1 - x0));
    }
  }
  return 5;
}
