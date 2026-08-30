/* One glyph per topic family, beside (never instead of) the colour swatch where a swatch element
   exists — condition rows and chips; the timeline card's colour is a border accent, no swatch.
   The swatch carries the topic colour system, the glyph carries recognition. */
export const FAMILY_GLYPHS = {
  coastal: '🌊', 'night-sky': '🌌', aurora: '🌌', 'sun-moon': '☀️',
  dust: '🏜️', air: '☁️', eclipse: '◐',
};
/* wire-type overrides within a family — the moon events read as moon, not sun */
const TYPE_GLYPHS = { supermoon: '🌙' };
export const entryGlyph = (entry) =>
  TYPE_GLYPHS[entry?.type] ?? FAMILY_GLYPHS[entry?.family] ?? null;
/* filter chips (utils/comingUpFeed.js chip ids); 'all' deliberately carries none */
export const CHIP_GLYPHS = { coastal: '🌊', 'night-sky': '🌌', 'sun-moon': '☀️', 'air-dust': '🏜️' };

/**
 * A coincidence sub-line's glyph (plan §4.5 — P3b's `.wf-cu-coin-line`, PR #690, merged). Prefers
 * the line's own served `family` — the same lookup every other surface uses — and falls back to
 * the design's name regex (moon → 🌙, tide/water → 🌊) only when a line carries no family at all,
 * which is not reachable on today's wire (every served coincidence line carries one) but is kept
 * as the documented degrade rather than a silent null.
 *
 * @param {?{family?: string, name?: string}} line a served coincidence line
 * @returns {?string}
 */
export function coincidenceLineGlyph(line) {
  if (line?.family) return FAMILY_GLYPHS[line.family] ?? null;
  const name = (line?.name ?? '').toLowerCase();
  if (/moon/.test(name)) return '🌙';
  if (/tide|water/.test(name)) return '🌊';
  return null;
}
