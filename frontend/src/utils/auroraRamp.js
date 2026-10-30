/**
 * The aurora ramp — night slate through teal to aurora green, for an aurora night painted through
 * the shared heat kernel (`docs/engineering/aurora-heat-plan.md`, A1; spec
 * `docs/design/aurora-heat/README.md`, "Aurora ramp — port exactly"; stop 3 deviates, see {@link AURORA_STOPS}).
 *
 * <p>Why this is its own module and NOT a third {@link setMode} mode in `scoreRamp.js` (plan §4
 * #1): `scoreRamp.js`'s mode is module state backing a persisted user preference
 * (`map_colour_scale`), and every score colour on the app reads it. An aurora window is an
 * <em>event-type</em> override of that preference, not a choice the reader makes, so it must never
 * persist, and it must not leak into the Plan-tab overlay (which shares the module state and has
 * no aurora field). The consumer — `MapView` — picks {@link AURORA_RAMP} when the window is an
 * aurora night and hands it down as props whose defaults are today's `scoreRamp` imports, so
 * nothing outside aurora mode can change.
 *
 * <p>Luminance rises monotonically with score, so on a dark basemap brightness alone ranks the
 * field — the opposite of the temperature ramp, whose hot end is its darkest colour and needs the
 * bloom pass to read. 1★ stays a visible slate so "poor" is distinguishable from "no data".
 * ⚠️ Never use a ramp colour as TEXT; pick ink per fill for contrast
 * (`windowFirstSpots.readableInkOn(fillHex)` is the repo's helper for that).
 */

/**
 * The five stops, lowest score first, ported from the bundle's `ASTOPS`
 * (`docs/design/aurora-heat/aurora-map.js`) with ONE deliberate deviation: stop 3 is [31, 127, 120]
 * (`#1F7F78`), not the spec's [34, 134, 126] (`#22867E`). White ink on the spec's teal is 4.39:1,
 * just under WCAG AA 4.5:1 for the 11px bold pin numeral; this one clears it (4.81:1) and is the
 * same teal to the eye (plan §4 #9, §5 Q3, decided 2026-10-11). Luminance stays monotonic. Each
 * entry is `[score, [r, g, b]]`.
 */
export const AURORA_STOPS = [
  [1, [56, 66, 98]],
  [2, [44, 84, 112]],
  [3, [31, 127, 120]],
  [4, [58, 200, 132]],
  [5, [118, 255, 160]],
];

/**
 * The bloom the aurora field asks the kernel for: the glow colour, and the gate and strength.
 * The 3.4 gate is aurora's own — the shared kernel gate stays 3 (plan §4 #4): this ramp rises
 * monotonically, so a higher gate leaves no dead band.
 */
export const AURORA_BLOOM = { rgb: [120, 255, 170], from: 3.4, a: 150 };

/**
 * A 1–5 aurora rating to an [r, g, b] triple, linearly interpolated between {@link AURORA_STOPS}
 * and clamped at both ends. A non-finite score resolves to the BOTTOM stop, never the top — the
 * rule `scoreRamp.rampRgb` states: an unknown reading must never render as the best one.
 *
 * @param {number} score 1–5; values outside the range clamp
 * @returns {number[]} [r, g, b], each 0–255 and integral
 */
export function auroraRampRgb(score) {
  const last = AURORA_STOPS.length - 1;
  const s = Number.isFinite(score)
    ? Math.max(AURORA_STOPS[0][0], Math.min(AURORA_STOPS[last][0], score))
    : AURORA_STOPS[0][0];
  let i = 0;
  while (i < last - 1 && s > AURORA_STOPS[i + 1][0]) i += 1;
  const [a, ca] = AURORA_STOPS[i];
  const [b, cb] = AURORA_STOPS[i + 1];
  const f = (s - a) / (b - a);
  return [0, 1, 2].map((k) => Math.round(ca[k] + (cb[k] - ca[k]) * f));
}

/**
 * The same ramp as an upper-case CSS hex string, for the DOM-side consumers.
 *
 * @param {number} score 1–5, clamped as {@link auroraRampRgb}
 * @returns {string} '#RRGGBB'
 */
export function auroraRampHex(score) {
  return `#${auroraRampRgb(score)
    .map((c) => c.toString(16).padStart(2, '0'))
    .join('')}`.toUpperCase();
}

/**
 * The ramp as a CSS horizontal gradient. The five stops are evenly spaced 1–5, so index and score
 * coincide and the percentages are the spec's literal 0/25/50/75/100.
 *
 * @returns {string} the spec's exact `linear-gradient(90deg,…)` value
 */
export function auroraRampGradientCss() {
  return 'linear-gradient(90deg,#384262,#2C5470 25%,#1F7F78 50%,#3AC884 75%,#76FFA0)';
}

/**
 * The aurora ramp as one object, so a consumer takes a single prop: `rgb` for the canvas kernel,
 * `hex` for markers, pins and chips, `gradientCss` for the legend and key.
 */
export const AURORA_RAMP = {
  rgb: auroraRampRgb,
  hex: auroraRampHex,
  gradientCss: auroraRampGradientCss,
};
