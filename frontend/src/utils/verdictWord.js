/**
 * The per-location star's client-side verdict word — a true leaf module, deliberately split out of
 * `utils/mapLabels.js` (map-tab-v2-plan.md §3 P9) so a caller that needs only this can import it
 * without also pulling in `d3-geo`.
 *
 * <p>⚠️ **This split exists because "imports no d3-geo-carrying module" was false, not because it
 * was fragile.** Before this file existed, {@link verdictWord} lived in `mapLabels.js` alongside
 * {@code regionLabelItems}, which calls {@code centroid} from `heatField.js`, which statically
 * imports `d3-geo` and `topojson-client`. `MapCallout.jsx` imported only {@link verdictWord} from
 * that module — never {@code regionLabelItems} or {@code centroid} — but a source-level import
 * names a MODULE, not an export, and the bundler's chunk-splitting did not fully separate the two:
 * measured on the pre-split build, `regionLabelItems`/`hottestRegion` (dead code for that reachable
 * path) were duplicated straight into the eager `MapView` chunk alongside {@link verdictWord}, and
 * that chunk carried a static import of a shared chunk holding `centroid`, the heat-field canvas
 * kernel and topojson's feature decoder — which itself statically imported the `d3-geo`/`d3-array`
 * bundle. So the Map tab and the Plan-tab overlay (`WindowFirstMapPane.jsx`, which mounts
 * `MapView` before a reader has chosen Heat or Pins, or opened the callout at all) were paying for
 * ~33 KB of JS (`labelPlacement-*.js` + `geo-*.js`, unminified) on every load, exactly the weight
 * the `lazy()` boundaries around {@code MapHeatLayer}/{@code MapLabels}/{@code PinsLayer} in
 * `MapView.jsx` exist to keep off that network path. See `MapView.jsx`'s comment above those
 * `lazy()` calls for the measurement and the full account.
 *
 * <p>The fix is this file: {@link verdictWord} now lives somewhere that cannot reach `heatField.js`
 * even by accident, because there is nothing else in the module for a future addition to sit next
 * to that would create that edge. `MapCallout.jsx` imports directly from here.
 * `utils/mapLabels.js` re-exports the same three bindings so `MapLabels.jsx`/`PinsLayer.jsx` (which
 * already pay for `d3-geo` for the heat-kernel/labelling code they DO use) need no import-path
 * change.
 */

/** @see verdictWord */
export const WORTH_IT_THRESHOLD = 3.7;
/** @see verdictWord */
export const MAYBE_THRESHOLD = 2.8;

/**
 * A bare per-location star has no served verdict enum to read (map-tab-v2-plan.md §3 P9's own
 * callout-contents paragraph: "verdict words come from served enums where the surface has one;
 * the ≥3.7/≥2.8 client thresholds are only for surfaces with no served verdict, and the map's
 * per-location star has none — record the choice in-code"), so the hover tooltip built for P8
 * falls back to the design bundle's own client thresholds: {@code ≥3.7 Worth it},
 * {@code ≥2.8 Maybe}, else {@code Poor}. This IS that recorded in-code decision — a location star
 * is always a whole number in this catalogue, so the fractional thresholds collapse to the
 * familiar {@code ≥4}/{@code ≥3}/{@code else} bands without needing to say so twice.
 *
 * <p>⚠️ P9's callout (`components/map/MapCallout.jsx`) answers the exact same question for the
 * exact same per-location star and MUST import {@link WORTH_IT_THRESHOLD}/{@link MAYBE_THRESHOLD}/
 * this function from here (or from `mapLabels.js`'s re-export of them) rather than re-deriving its
 * own copy — two client thresholds for one ungoverned quantity is exactly the kind of drift a
 * shared constant exists to prevent.
 *
 * @param {?number} rating 1–5, or null/non-finite for "not scored"
 * @returns {?string} {@code 'Worth it'|'Maybe'|'Poor'}, or null when there is no rating to judge
 */
export function verdictWord(rating) {
  if (rating == null || !Number.isFinite(rating)) return null;
  if (rating >= WORTH_IT_THRESHOLD) return 'Worth it';
  if (rating >= MAYBE_THRESHOLD) return 'Maybe';
  return 'Poor';
}
