/**
 * The land clip's geometry — one {@code Path2D} of UK land per zoom level, in absolute Leaflet
 * pixel coordinates, for {@code MapHeatLayer} to clip and stroke the heat field against.
 *
 * <p>Ported from the design bundle's {@code map-tab-v2.js} {@code landPath()} (lines ~130–141),
 * per {@code docs/engineering/map-tab-v2-plan.md} §3 P4 / {@code docs/design/map-tab-v2/README.md}
 * "The heat field". The bundle streams the vendored FeatureCollection through
 * {@code map.project([lat, lng], zoom)} — d3-geo's own coordinate order is {@code [lng, lat]}, so
 * the transform below swaps the arguments back before handing them to Leaflet, exactly as the
 * bundle does — via a {@code d3.geoTransform}, so the whole coastline is projected ONCE per zoom
 * rather than once per frame; panning then costs the caller a {@code translate}
 * ({@code clipDx}/{@code clipDy}), never a re-projection.
 *
 * <p>{@code map.project(latlng, zoom)} returns a point in the SAME absolute pixel space regardless
 * of the map's current view or the container's size — it is a pure function of the coordinate, the
 * zoom and the CRS — so the cached path stays correct across a pan. It is keyed on zoom alone;
 * {@link module:landMask.createLandMask}'s caller invalidates it explicitly for the two other
 * events {@code docs/engineering/map-tab-v2-plan.md} §3 P4 names (a container resize and the
 * topology's own arrival), and does so defensively rather than because the geometry could actually
 * go stale from either — {@code map.project} does not depend on the container at all, so a resize
 * invalidation costs one Path2D rebuild against a mistake, never a correctness fix.
 *
 * <p>⚠️ jsdom has no {@code Path2D} at all (per this plan's §3 P4 note and P1's own build notes), so
 * the constructor is an injected dependency (defaulting to {@code window.Path2D}) rather than a
 * bare reference — a test supplies a recording stub, and this module never touches
 * {@code window.Path2D} when one is given (the default parameter expression is only evaluated when
 * the argument is `undefined`, which is standard JS semantics, not a branch this module writes).
 */

import { geoPath, geoTransform } from 'd3-geo';
import { land } from './heatField.js';

/**
 * Builds and caches a UK land {@code Path2D} for one Leaflet map, one entry per zoom level.
 *
 * @param {object} map a Leaflet map — only {@code getZoom} and {@code project} are read
 * @param {{path2DCtor?: Function}} [opts] {@code path2DCtor} defaults to {@code window.Path2D};
 *        tests inject a recording stub instead. A caller that wants "there is no Path2D here"
 *        (an old browser) passes an explicit falsy value, which makes {@link get} decline forever
 *        rather than throw.
 * @returns {{get: () => (*|null), invalidate: () => void}} {@code get} returns the cached (or
 *          freshly built) path for the map's CURRENT zoom, or {@code null} before the topology has
 *          resolved ({@link module:heatField.load}) or when no Path2D constructor is available —
 *          both cases are graceful: the caller paints unclipped. {@code invalidate} discards the
 *          cache so the next {@code get} rebuilds regardless of zoom.
 */
export function createLandMask(map, opts = {}) {
  const {
    path2DCtor = (typeof window !== 'undefined' ? window.Path2D : undefined),
  } = opts;

  /** {@code {zoom: number, path: *}}, or null before the first successful build. */
  let cached = null;

  function get() {
    if (!path2DCtor) return null;
    const topology = land();
    if (!topology) return null;
    const zoom = map.getZoom();
    if (cached && cached.zoom === zoom) return cached.path;
    // A raw geoTransform stream, not a real projection — d3-geo only needs the `point` method,
    // and Leaflet's own `project` is the actual math. `zoom` is captured explicitly rather than
    // read from `map` a second time inside the stream, so a zoom that moves mid-build (it cannot,
    // this is synchronous, but the explicit capture is what makes that true by construction rather
    // than by accident) cannot produce a path mixing two zooms.
    const transform = geoTransform({
      point(lng, lat) {
        const p = map.project([lat, lng], zoom);
        this.stream.point(p.x, p.y);
      },
    });
    // No context bound to geoPath: called this way it returns SVG path data as a string, which is
    // exactly what `new Path2D(d)` accepts — the same trick the bundle's `landPath()` uses.
    const d = geoPath(transform)(topology);
    const path = new path2DCtor(d);
    cached = { zoom, path };
    return path;
  }

  function invalidate() {
    cached = null;
  }

  return { get, invalidate };
}
