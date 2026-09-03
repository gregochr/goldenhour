/**
 * The field/marker handover band's pure math — extracted from `MapHeatLayer.jsx` at map-tab-v2-
 * plan.md §3 P10 into its own dependency-free module, so `MapView.jsx` can read the SAME fraction
 * for the Legend panel's "Field → Handing over → Locations" indicator WITHOUT eagerly pulling
 * `MapHeatLayer.jsx`'s own `heatField.js` import (`d3-geo`/`topojson-client`) into every mount's
 * bundle.
 *
 * <p>`MapHeatLayer.jsx` is imported behind a `lazy()` boundary specifically so the Plan-tab overlay
 * — which never needs the field at all — never downloads it (`MapView.jsx`'s own class doc: "the
 * boundary is load-bearing, not tidiness"). A statically-imported `fadeAt` from `MapHeatLayer.jsx`
 * (or from `heatField.js`, whose `clamp` the original definition read) would defeat that boundary
 * for every mount, overlay included — this module has no such import to drag along.
 *
 * <p>`MapHeatLayer.jsx` re-exports {@link fadeAt} so its own existing imports/tests are unaffected;
 * this file is the one definition, never two.
 */

function clamp(v, a, b) {
  return Math.max(a, Math.min(b, v));
}

/**
 * The handover band (D8): the zoom range across which the field gives way to the markers. Below
 * {@link FADE_FROM} the question is WHERE — a county at a time, which is what a blended field
 * answers and what a wall of medallions cannot. Above {@link FADE_TO} the question has become
 * WHICH, and a smear cannot answer which. The field does not vanish at the top: it settles to
 * {@link HEAT_FLOOR}, a faint wash, because the regional answer is still true at street level and
 * removing it entirely would make the two views feel like different maps.
 *
 * <p>Re-tuned at map-tab-v2-plan.md §3 P4 — {@code 10.4 → 12.0} and floor {@code 0.12}, from
 * {@code 10.6 → 12.2}/{@code 0.17} — co-tuned in the same commit as the radius re-tune and the land
 * clip (docs/design/map-tab-v2/README.md, "Field / label handover"): the old band was part of why
 * the field swam offshore.
 */
export const FADE_FROM = 10.4;
export const FADE_TO = 12.0;
export const HEAT_FLOOR = 0.12;

/**
 * Where the map is in the handover, from its zoom.
 *
 * @param {number} zoom the map's current zoom
 * @returns {{markers: number, heat: number}} the marker opacity (0 → 1) and the heat opacity
 *          multiplier (1 → {@link HEAT_FLOOR}), which move in opposite directions across one band
 */
export function fadeAt(zoom) {
  const t = clamp((zoom - FADE_FROM) / (FADE_TO - FADE_FROM), 0, 1);
  return { markers: t, heat: 1 - (1 - HEAT_FLOOR) * t };
}
