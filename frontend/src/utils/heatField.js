/**
 * The heat field — ONE field kernel, two hosts.
 *
 * <p>Ported from the design bundle's `docs/design/heat-map/heat-field.js`, close to as-is. The
 * algorithm is load-bearing and the deviations are enumerated in `docs/engineering/heat-field-plan.md`
 * §4.1: ES module exports instead of a `window.HeatField` IIFE, inline `mean` instead of a
 * `d3-array` import (three call sites, in `aspect` and `centroid`), real
 * `d3-geo`/`topojson-client` dependencies, a vendored
 * topology instead of a CDN fetch, `field()` additionally returning its `ImageData` so tests can
 * assert cell values, and the colour ramp living in `scoreRamp.js` so the canvas and the CSS
 * cannot drift. Nothing else changed — in particular no arithmetic did.
 *
 * <p>The kernel ({@link field}, {@link paint}) knows nothing about maps: give it screen-space
 * points and it returns the blended score field. The geo host ({@link drawGeo}) projects with
 * d3 and clips to real coastline for the static Plan thumbnails; the tile host
 * ({@link drawTiles}) projects with a Leaflet map and paints over the basemap. Both get the same
 * colour ramp, the same coverage clamp, the same confidence haze and the same cull, so the two
 * surfaces can never drift in what they mean.
 *
 * <p>Two performance rounds are baked in and both are required — the cull and the 3×3 spatial
 * bucketing. Without bucketing, 204 locations already stalled a pan (~4.5M inner-loop iterations
 * per frame). `heatField.test.js` pins the bucketed field against a brute-force accumulator for
 * exactly this reason: any "simplification" of the bucket walk has to keep that test green.
 */

import { geoMercator, geoPath } from 'd3-geo';
import { feature } from 'topojson-client';
import { rampRgb, rgb } from './scoreRamp.js';

/**
 * Default framing box — northern England and the Scottish border, the app's current coverage.
 * A corner MultiPoint, never a ring: a polygon's winding can be read as the whole globe, which
 * silently fits the projection to the world instead of the area you asked for.
 */
export const BBOX = {
  type: 'MultiPoint',
  coordinates: [
    [-3.85, 53.8],
    [-0.28, 53.8],
    [-0.28, 55.88],
    [-3.85, 55.88],
  ],
};

/** Arithmetic mean of an array, optionally through an accessor. Inlined rather than importing d3-array. */
function mean(values, accessor) {
  if (!values.length) return undefined;
  let total = 0;
  for (const v of values) total += accessor ? accessor(v) : v;
  return total / values.length;
}

/** Cached UK land geometry, populated once by {@link load}. */
let LAND = null;

/** The in-flight {@link load}, so concurrent callers share one decode and one result object. */
let loading = null;

/**
 * The loaded UK land geometry, exposed so a caller can tell "the geometry has not arrived yet"
 * apart from the other reasons {@link drawGeo} declines — see its docs.
 *
 * @returns {object|null} the FeatureCollection, or null before {@link load} has resolved
 */
export function land() {
  return LAND;
}

/**
 * Constrains {@code v} to [{@code a}, {@code b}]. Exported because both hosts need it for their
 * own dials (the prototype's `map-tab.js` and `plan-tab.js` both open by aliasing it).
 *
 * @param {number} v value to constrain
 * @param {number} a lower bound
 * @param {number} b upper bound
 * @returns {number} the constrained value
 */
export function clamp(v, a, b) {
  return Math.max(a, Math.min(b, v));
}

// Re-exported so the ported surface still carries everything the prototype's two hosts alias off
// `HeatField`; it is DEFINED in scoreRamp.js, which is where a colour helper belongs.
export { rgb };

/**
 * The kernel: accumulate every point's score into a blended field.
 *
 * <p>`conf` 0–1 is forecast confidence: lower desaturates and thins, so a day-4 guess cannot look
 * as authoritative as tonight. `focus` fades every other region almost to nothing.
 *
 * @param {Array<{x: number, y: number, sc: number, rid: *}>} pts points in CSS px of the target surface
 * @param {number} w  surface width in CSS px
 * @param {number} h  surface height in CSS px
 * @param {{radius?: number, grid?: number, conf?: number, focus?: *, alpha?: number}} [opts] dials
 * @returns {{canvas: HTMLCanvasElement, cols: number, rows: number, gp: number, n: number,
 *           unc: number, img: ImageData}|null} the field, or null when nothing can contribute
 */
export function field(pts, w, h, opts) {
  opts = opts || {};
  const conf = opts.conf == null ? 1 : clamp(opts.conf, 0, 1);
  const unc = 1 - conf;
  const R = opts.radius || Math.max(14, w * 0.085);
  const R2 = R * R;
  const gp = opts.grid || 4;
  /* cull to the frame plus the kernel's reach (the sum cuts off at d2 > 6R2, i.e. ~2.45R).
     A location that cannot touch a single cell must not cost one iteration per cell. */
  const CUT = 2.45 * R;
  const m = CUT + gp;
  const keep = [];
  for (const p of pts) {
    if (p.x < -m || p.x > w + m || p.y < -m || p.y > h + m) continue;
    keep.push(p);
  }
  if (!keep.length) return null;
  /* bucket by the cutoff distance so each cell sums only its 3x3 neighbourhood instead of the
     whole catalogue. This is what turns O(cells x locations) into O(cells x local density) —
     without it 200 locations already stalled a pan, and 1000 would be hopeless. */
  const B = Math.max(CUT, 1);
  const bx0 = -m;
  const by0 = -m;
  const bw = Math.ceil((w + 2 * m) / B) + 1;
  const bh = Math.ceil((h + 2 * m) / B) + 1;
  const buckets = new Array(bw * bh);
  for (const p of keep) {
    const bi = Math.floor((p.x - bx0) / B);
    const bj = Math.floor((p.y - by0) / B);
    if (bi < 0 || bj < 0 || bi >= bw || bj >= bh) continue;
    const k = bj * bw + bi;
    (buckets[k] || (buckets[k] = [])).push(p);
  }
  const cols = Math.ceil(w / gp) + 1;
  const rows = Math.ceil(h / gp) + 1;
  const off = document.createElement('canvas');
  off.width = cols;
  off.height = rows;
  const octx = off.getContext('2d');
  const img = octx.createImageData(cols, rows);
  const aMax = (opts.alpha == null ? 206 : opts.alpha) * (1 - 0.34 * unc);
  for (let j = 0; j < rows; j += 1) {
    for (let i = 0; i < cols; i += 1) {
      const px = i * gp;
      const py = j * gp;
      let sw = 0;
      let sws = 0;
      const bi = Math.floor((px - bx0) / B);
      const bj = Math.floor((py - by0) / B);
      for (let dj = -1; dj <= 1; dj += 1) {
        for (let di = -1; di <= 1; di += 1) {
          const bjj = bj + dj;
          const bii = bi + di;
          if (bii < 0 || bjj < 0 || bii >= bw || bjj >= bh) continue;
          const list = buckets[bjj * bw + bii];
          if (!list) continue;
          for (const p of list) {
            const dx = px - p.x;
            const dy = py - p.y;
            const d2 = dx * dx + dy * dy;
            if (d2 > R2 * 6) continue;
            let wt = Math.exp(-d2 / R2);
            if (opts.focus && p.rid !== opts.focus) wt *= 1e-4;
            sw += wt;
            sws += wt * p.sc;
          }
        }
      }
      const k = (j * cols + i) * 4;
      if (sw < 0.02) {
        img.data[k + 3] = 0;
        continue;
      }
      let c = rampRgb(sws / sw);
      /* coverage clamp: warmth only where locations actually are, so empty ground stays empty
         instead of being coloured in by interpolation from thirty miles away */
      const cov = 1 - Math.exp(-sw / 1.15);
      if (unc > 0) {
        const g = (c[0] + c[1] + c[2]) / 3;
        const d = 0.6 * unc;
        c = [c[0] + (g - c[0]) * d, c[1] + (g - c[1]) * d, c[2] + (g - c[2]) * d];
      }
      img.data[k] = c[0];
      img.data[k + 1] = c[1];
      img.data[k + 2] = c[2];
      img.data[k + 3] = Math.round(clamp(cov, 0, 1) * aMax);
    }
  }
  octx.putImageData(img, 0, 0);
  return { canvas: off, cols, rows, gp, n: keep.length, unc, img };
}

/**
 * Paints a field into any 2d context, blurred and composited.
 *
 * @param {CanvasRenderingContext2D} ctx target context
 * @param {number} w surface width in CSS px
 * @param {number} h surface height in CSS px
 * @param {Array<object>} pts points, as {@link field}
 * @param {{clip?: Function, blur?: number, opacity?: number}} [opts] plus everything {@link field} takes
 * @returns {object|null} the field that was painted, or null when there was nothing to paint
 */
export function paint(ctx, w, h, pts, opts) {
  opts = opts || {};
  const f = field(pts, w, h, opts);
  if (!f) return null;
  ctx.save();
  if (opts.clip) {
    ctx.beginPath();
    opts.clip(ctx);
    ctx.clip();
  }
  ctx.imageSmoothingEnabled = true;
  ctx.filter = `blur(${(opts.blur || 3) + f.unc * 2.6}px)`;
  ctx.globalAlpha = opts.opacity == null ? 0.92 : opts.opacity;
  ctx.drawImage(f.canvas, 0, 0, f.cols, f.rows, 0, 0, f.cols * f.gp, f.rows * f.gp);
  ctx.restore();
  return f;
}

/**
 * Sizes a canvas for the display's pixel ratio and returns its 2d context.
 * DPR is capped at 2 — beyond that the extra pixels cost real time and buy nothing a blurred
 * field can show.
 *
 * @param {HTMLCanvasElement} cv the canvas to size
 * @param {number} w CSS px
 * @param {number} h CSS px
 * @returns {CanvasRenderingContext2D} the context, pre-scaled so callers draw in CSS px
 */
export function fit(cv, w, h) {
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  cv.width = Math.round(w * dpr);
  cv.height = Math.round(h * dpr);
  cv.style.width = `${w}px`;
  cv.style.height = `${h}px`;
  const ctx = cv.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  return ctx;
}

/**
 * Loads the vendored UK land geometry, once. The prototype fetched world geometry from a CDN;
 * production's CSP allows no CDN, so the topology is generated by
 * `scripts/generate-uk-land.mjs` and committed. It is imported dynamically so it code-splits out
 * of the entry chunk. Filtering to the UK happens at generation time, not here.
 *
 * <p>The asset is in `src/assets/` rather than the repo's usual `public/` deliberately, and all
 * three reasons matter: only a `src/` asset can be dynamically imported at all, only it gets a
 * content hash, and only it rides the runtime `CacheFirst` `/assets/` route the PWA config
 * already defines. Moving it to `public/` would silently break all three.
 *
 * @returns {Promise<object>} a GeoJSON FeatureCollection of UK land; rejects if the asset chunk
 *          cannot be fetched, which callers must handle — an unhandled rejection here shows as a
 *          permanent loading state with no visible cause
 */
export function load() {
  if (LAND) return Promise.resolve(LAND);
  // Share the in-flight promise. Six thumbnails mount at once, and without this each one decodes
  // the topology separately and receives its OWN FeatureCollection — five of the six then holding
  // an object that is not the one `drawGeo` reads. The decode is cheap (~0.1 ms), so this is about
  // identity, not speed. A rejection clears the latch rather than latching failure forever, so a
  // caller that retries can succeed; the rejection itself propagates, because a silent null here
  // would leave every surface blank with nothing to report.
  if (loading) return loading;
  loading = import('../assets/uk-land-50m.json')
    .then((mod) => {
      const topo = mod.default || mod;
      LAND = feature(topo, topo.objects.countries);
      loading = null;
      return LAND;
    })
    .catch((err) => {
      loading = null;
      throw err;
    });
  return loading;
}

/**
 * A Mercator projection fitted to {@code fitTo} (default {@link BBOX}) with a 2px bleed, so the
 * coastline stroke is not clipped at the canvas edge.
 *
 * @param {number} w CSS px
 * @param {number} h CSS px
 * @param {object} [fitTo] a GeoJSON object to fit — a corner MultiPoint, never a ring
 * @returns {Function} the d3 projection
 */
export function proj(w, h, fitTo) {
  return geoMercator().fitExtent(
    [
      [-2, -2],
      [w + 2, h + 2],
    ],
    fitTo || BBOX,
  );
}

/**
 * A padded corner MultiPoint around a set of spots, for {@link proj}/{@link aspect}.
 * Longitude padding is 1.7× the latitude padding because at UK latitudes a degree of longitude is
 * roughly 0.6 of a degree of latitude on screen — equal degrees would pad unevenly.
 *
 * @param {Array<{lat: number, lng: number}>} spots
 * @param {number} [padDeg] latitude padding in degrees (default 0.16)
 * @returns {object} a GeoJSON MultiPoint of the four corners
 */
export function bbox(spots, padDeg) {
  if (!spots || !spots.length) return BBOX;
  const p = padDeg == null ? 0.16 : padDeg;
  const la = spots.map((s) => s.lat);
  const ln = spots.map((s) => s.lng);
  const a = Math.min(...la) - p;
  const b = Math.max(...la) + p;
  const c = Math.min(...ln) - p * 1.7;
  const d = Math.max(...ln) + p * 1.7;
  return {
    type: 'MultiPoint',
    coordinates: [
      [c, a],
      [d, a],
      [d, b],
      [c, b],
    ],
  };
}

/**
 * The same box as `[[south, west], [north, east]]` for Leaflet's `fitBounds`.
 *
 * @param {Array<{lat: number, lng: number}>} spots
 * @param {number} [padDeg] latitude padding in degrees
 * @returns {number[][]} [[south, west], [north, east]]
 */
export function latLngBounds(spots, padDeg) {
  const c = bbox(spots, padDeg).coordinates;
  const la = c.map((x) => x[1]);
  const ln = c.map((x) => x[0]);
  return [
    [Math.min(...la), Math.min(...ln)],
    [Math.max(...la), Math.max(...ln)],
  ];
}

/**
 * Aspect of a frame (height / width) so a surface can size itself to its geography. The longitude
 * span is cos-latitude corrected, which is why a UK frame is taller than its raw degree span.
 *
 * @param {object} [fitTo] a corner MultiPoint (default {@link BBOX})
 * @returns {number} height / width, or 1 for a degenerate frame
 */
export function aspect(fitTo) {
  const cs = (fitTo || BBOX).coordinates;
  const la = cs.map((c) => c[1]);
  const ln = cs.map((c) => c[0]);
  const dLat = Math.max(...la) - Math.min(...la);
  const dLng = (Math.max(...ln) - Math.min(...ln)) * Math.cos((mean(la) * Math.PI) / 180);
  return dLng > 0 ? dLat / dLng : 1;
}

/**
 * The projected centroid of one region's spots, for a map label.
 *
 * @param {Array<{rid: *}>} spots
 * @param {*} rid the region key to select on
 * @param {Function} project maps a spot to an [x, y] screen point
 * @returns {number[]|null} [x, y], or null when the region has no spots
 */
export function centroid(spots, rid, project) {
  const ps = spots.filter((s) => s.rid === rid).map((s) => project(s));
  return ps.length ? [mean(ps, (p) => p[0]), mean(ps, (p) => p[1])] : null;
}

/**
 * Host A: a static canvas with a d3 projection, clipped to real coastline (the Plan thumbnails
 * and the open row's field map).
 *
 * @param {HTMLCanvasElement} cv target canvas
 * @param {number} w CSS px
 * @param {number} h CSS px
 * @param {Array<{lat: number, lng: number, r: number[], rid: *}>} spots the catalogue
 * @param {number} win index into each spot's `r` scores
 * @param {object} [opts] `fit`, `sea`, `plate`, `stroke`, `line`, `focus` + everything {@link field} takes
 * @returns {Function|null} the projection used, or null when it declines — which happens for
 *          THREE different reasons: no canvas, a box 20px or smaller in either dimension (a
 *          zero measure throws on `cv.width`), or {@link load} not yet resolved. P2's rAF
 *          retry must tell the last one apart from the others — a retry budget sized for a
 *          hidden pane's first measure can expire before the topology chunk arrives, leaving
 *          blank thumbnails with no further trigger. Use {@link land} to distinguish them.
 */
export function drawGeo(cv, w, h, spots, win, opts) {
  opts = opts || {};
  if (!cv || !(w > 20) || !(h > 20) || !LAND) return null; // a zero measure throws on cv.width
  const ctx = fit(cv, w, h);
  const projection = proj(w, h, opts.fit);
  const path = geoPath(projection, ctx);
  // ⚠️ `#13100e` is mirrored by `--color-heat-sea` in `index.css`, which the Plan thumbnails carry
  // as a CSS background so an unpainted canvas is the same ground as a painted one. The two are
  // independent constants — this host takes no CSS — so they must be moved together; a mismatch
  // shows as a flash of the wrong colour on every first paint. The token's own note says the same.
  ctx.fillStyle = opts.sea || '#13100e';
  ctx.fillRect(0, 0, w, h);
  ctx.beginPath();
  path(LAND);
  ctx.fillStyle = opts.plate || '#241d18';
  ctx.fill();
  const pts = spots.map((s) => {
    const p = projection([s.lng, s.lat]);
    return { x: p[0], y: p[1], sc: s.r[win], rid: s.rid };
  });
  paint(ctx, w, h, pts, { ...opts, alpha: opts.focus ? 238 : 206, clip: () => path(LAND) });
  ctx.beginPath();
  path(LAND);
  ctx.strokeStyle = opts.stroke || 'rgba(242,231,211,.30)';
  ctx.lineWidth = opts.line || 0.7;
  ctx.stroke();
  return projection;
}

/**
 * Host B: a Leaflet map, painted over the basemap (the tiles carry the geography, so there is no
 * coastline to draw or clip to).
 *
 * @param {HTMLCanvasElement} cv target canvas, sized to the map
 * @param {object} map a Leaflet map
 * @param {Array<{lat: number, lng: number, r: number[], rid: *}>} spots the catalogue
 * @param {number} win index into each spot's `r` scores
 * @param {object} [opts] everything {@link field} and {@link paint} take
 * @returns {object|null} the painted field, or null when the map is too small
 */
export function drawTiles(cv, map, spots, win, opts) {
  opts = opts || {};
  const sz = map.getSize();
  const w = sz.x;
  const h = sz.y;
  if (!(w > 20) || !(h > 20)) return null;
  const ctx = fit(cv, w, h);
  ctx.clearRect(0, 0, w, h);
  const pts = spots.map((s) => {
    const p = map.latLngToContainerPoint([s.lat, s.lng]);
    return { x: p.x, y: p.y, sc: s.r[win], rid: s.rid };
  });
  return paint(ctx, w, h, pts, opts);
}

/**
 * Metres-per-pixel at the map's centre, so a kernel radius can be set in real distance rather
 * than in pixels that mean a different thing at every zoom.
 *
 * @param {object} map a Leaflet map
 * @param {number} metres the radius to express in pixels
 * @param {number} [lo] lower clamp (default 34)
 * @param {number} [hi] upper clamp (default 240)
 * @returns {number} the radius in CSS px, clamped
 */
export function radiusFor(map, metres, lo, hi) {
  const c = map.getCenter();
  const mpp = (156543.03392 * Math.cos((c.lat * Math.PI) / 180)) / Math.pow(2, map.getZoom());
  return clamp(metres / mpp, lo == null ? 34 : lo, hi == null ? 240 : hi);
}
