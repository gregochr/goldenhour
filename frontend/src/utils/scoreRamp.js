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
 * <p>The stops are the app's existing verdict palette, which is a happy accident worth keeping:
 * a reader who has learned that amber means "marginal" on a pill reads the same amber correctly
 * in the field. Stops 1 and 4 fill in the ends the verdict vocabulary has no word for.
 *
 * <p>⚠️ This is NOT {@code markerUtils.RATING_COLOURS}. That is v1's ramp and v1 is the pilot's
 * frozen comparison control — it must keep rendering byte-identically until the flag flips, so
 * nothing here may be pushed into it.
 */

/**
 * The five stops, lowest score first. Hex is the single literal for each stop; the numeric
 * triples used by the canvas are derived from it below, so an edit here cannot leave the two
 * representations disagreeing.
 */
export const RAMP_STOPS = [
  { score: 1, hex: '#B03A2A' }, // darker than stand-down: 1★ is worse than "don't go"
  { score: 2, hex: '#C8452F' }, // --color-verdict-standdown
  { score: 3, hex: '#E0A542' }, // --color-verdict-marginal
  { score: 4, hex: '#B0BE74' }, // between marginal and go
  { score: 5, hex: '#8AAE72' }, // --color-verdict-go
];

/** Lowest score the ramp is defined for; anything below clamps to it. */
export const RAMP_MIN = RAMP_STOPS[0].score;
/** Highest score the ramp is defined for; anything above clamps to it. */
export const RAMP_MAX = RAMP_STOPS[RAMP_STOPS.length - 1].score;

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

const STOP_RGB = RAMP_STOPS.map((s) => parseHex(s.hex));

/** Constrains {@code v} to [{@code a}, {@code b}]. Private: heatField.js owns the exported one. */
function clamp(v, a, b) {
  return Math.max(a, Math.min(b, v));
}

/**
 * The ramp itself: a 1–5 score to an [r, g, b] triple, linearly interpolated between stops and
 * clamped at both ends. Called once per grid cell by the heat kernel, so it stays allocation-light
 * and does no validation beyond the clamp.
 *
 * @param {number} score 1–5; values outside the range clamp rather than throwing
 * @returns {number[]} [r, g, b], each 0–255 and integral
 */
export function rampRgb(score) {
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
  while (i < STOP_RGB.length - 2 && s > RAMP_STOPS[i + 1].score) i += 1;
  const ca = STOP_RGB[i];
  const cb = STOP_RGB[i + 1];
  const f = (s - RAMP_STOPS[i].score) / (RAMP_STOPS[i + 1].score - RAMP_STOPS[i].score);
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
  // Upper case so a returned string is directly comparable with the RAMP_STOPS literals. The
  // zero-pad cannot fire for any score — the ramp's darkest channel is 42 (0x2A) — and is kept
  // only so the function stays correct if a stop is ever darkened.
  return `#${rampRgb(score)
    .map((c) => c.toString(16).padStart(2, '0'))
    .join('')}`.toUpperCase();
}
