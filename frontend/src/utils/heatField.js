/**
 * The heat field — ONE field kernel, two hosts.
 *
 * <p>The base port is from the design bundle's `docs/design/heat-map/heat-field.js`, close to
 * as-is. The algorithm is load-bearing and the deviations are enumerated in
 * `docs/engineering/heat-field-plan.md` §4.1: ES module exports instead of a `window.HeatField`
 * IIFE, inline `mean` instead of a `d3-array` import (three call sites, in `aspect` and
 * `centroid`), real `d3-geo`/`topojson-client` dependencies, a vendored topology instead of a CDN
 * fetch, `field()` additionally returning its `ImageData` so tests can assert cell values, and the
 * colour ramp living in `scoreRamp.js` so the canvas and the CSS cannot drift. Nothing else
 * changed in that base port — in particular no arithmetic did.
 *
 * <p>The bloom, soft-mask and `drawTiles` score-callback delta is forward-ported separately, from
 * the Map tab v2 bundle's own `docs/design/map-tab-v2/heat-field.js`, per
 * `docs/engineering/map-tab-v2-plan.md` §3 P1 — that plan section is this delta's own deviation
 * ledger (notably: `field()` additionally returns `bloomImg` alongside `bloom`, the same reason
 * the base port added `img`; and `paint()`'s `bloomBlur` honours an explicit `0` where the
 * bundle's `opts.bloomBlur||2.4` would not).
 *
 * <p>The kernel ({@link field}, {@link paint}) knows nothing about maps: give it screen-space
 * points and it returns the blended score field. The geo host ({@link drawGeo}) projects with
 * d3 and clips to real coastline for the static Plan thumbnails; the tile host
 * ({@link drawTiles}) projects with a Leaflet map and paints over the basemap. Both get the same
 * colour ramp by default (a host may inject its own through `opts.ramp`), the same coverage
 * clamp, the same confidence haze and the same cull, so the two surfaces can never drift in what
 * they mean.
 *
 * <p>Two performance rounds are baked in and both are required — the cull and the 3×3 spatial
 * bucketing. Without bucketing, 204 locations already stalled a pan (~4.5M inner-loop iterations
 * per frame). `heatField.test.js` pins the bucketed field against a brute-force accumulator for
 * exactly this reason: any "simplification" of the bucket walk has to keep that test green.
 */

import { geoMercator, geoPath } from 'd3-geo';
import { feature } from 'topojson-client';
import { rampRgb, rgb } from './scoreRamp.js';
import {
  aspect, BBOX, bbox, clamp, latLngBounds, mean,
} from './heatGeometry.js';

// Re-exported so the ported surface is unchanged and every existing caller — the strip, the row
// map, the tests — imports exactly what it always did. The definitions moved to `heatGeometry.js`
// only because they need no projection library, and a caller that wants a bounding box should not
// have to fetch `d3-geo` to get one. See that module's header.
export {
  aspect, BBOX, bbox, clamp, latLngBounds,
};

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

// Re-exported so the ported surface still carries everything the prototype's two hosts alias off
// `HeatField`; it is DEFINED in scoreRamp.js, which is where a colour helper belongs.
export { rgb };

/**
 * Buckets points by the kernel's cutoff distance, after culling to the frame plus the kernel's
 * reach — the spatial index {@link field} and {@link unknownMask} share, so the two can never
 * disagree about which points a cell can see.
 *
 * <p>The cull: the sum cuts off at d2 > 6R2, i.e. ~2.45R, so a location that cannot touch a
 * single cell must not cost one iteration per cell. The buckets are the cutoff distance wide, so
 * each cell sums only its 3x3 neighbourhood instead of the whole catalogue. This is what turns
 * O(cells x locations) into O(cells x local density) — without it 200 locations already stalled
 * a pan, and 1000 would be hopeless. The geometry depends only on {@code w}, {@code h},
 * {@code R} and {@code gp}, so two calls with the same four share one lattice.
 *
 * @param {Array<{x: number, y: number}>} pts points in CSS px of the target surface
 * @param {number} w surface width in CSS px
 * @param {number} h surface height in CSS px
 * @param {number} R kernel radius in CSS px
 * @param {number} gp grid pitch in CSS px
 * @returns {{keep: Array<object>, B: number, bx0: number, by0: number, bw: number, bh: number,
 *           buckets: Array<?Array<object>>}} the kept points and their buckets
 */
function bucketPoints(pts, w, h, R, gp) {
  const CUT = 2.45 * R;
  const m = CUT + gp;
  const keep = [];
  for (const p of pts) {
    if (p.x < -m || p.x > w + m || p.y < -m || p.y > h + m) continue;
    keep.push(p);
  }
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
  return { keep, B, bx0, by0, bw, bh, buckets };
}

/**
 * Hands {@code visit} every non-empty bucket in the 3x3 neighbourhood of the cell at
 * ({@code px}, {@code py}) — the bucket walk, shared by every sum over {@link bucketPoints}.
 *
 * @param {object} grid from {@link bucketPoints}
 * @param {number} px cell x in CSS px
 * @param {number} py cell y in CSS px
 * @param {function(Array<object>): void} visit called once per non-empty neighbouring bucket
 */
function forNeighbours(grid, px, py, visit) {
  const { B, bx0, by0, bw, bh, buckets } = grid;
  const bi = Math.floor((px - bx0) / B);
  const bj = Math.floor((py - by0) / B);
  for (let dj = -1; dj <= 1; dj += 1) {
    for (let di = -1; di <= 1; di += 1) {
      const bjj = bj + dj;
      const bii = bi + di;
      if (bii < 0 || bjj < 0 || bii >= bw || bjj >= bh) continue;
      const list = buckets[bjj * bw + bii];
      if (list) visit(list);
    }
  }
}

/**
 * The kernel: accumulate every point's score into a blended field.
 *
 * <p>`conf` 0–1 is forecast confidence: lower desaturates and thins, so a day-4 guess cannot look
 * as authoritative as tonight. `focus` fades every other region almost to nothing.
 *
 * <p>`opts.bloom` builds a second, optional emissive layer keyed to SCORE rather than to the
 * ramp colour's own luminance. The temperature ramp ({@link STOPS_TEMP} in `scoreRamp.js`) peaks
 * in luminance at the gold 3★ and its hot end is its darkest colour, so on a dark basemap
 * luminance contrast ranks the middle highest and the ordering inverts — see the design bundle's
 * README, "The heat bloom (required on a dark ground)". No blend mode fixes that: `screen` and
 * `lighter` are both monotonic in source luminance, so a dark red can never outrank a light gold
 * under them. A separate layer whose alpha rises with the blended score can, and it leaves the
 * frozen ramp stops alone.
 *
 * @param {Array<{x: number, y: number, sc: number, rid: *}>} pts points in CSS px of the target surface
 * @param {number} w  surface width in CSS px
 * @param {number} h  surface height in CSS px
 * @param {{radius?: number, grid?: number, conf?: number, focus?: *, alpha?: number, bloom?: *,
 *          bloomFrom?: number, bloomA?: number, ramp?: function(number): number[],
 *          bloomRGB?: number[]}} [opts] dials. `ramp` maps a blended score to [r, g, b] (default
 *          the active `scoreRamp` mode's `rampRgb`); `bloomRGB` is the bloom's colour (default the
 *          ember [255,138,66]). Both exist so an aurora night can paint through this same kernel
 *          in its own colours (`auroraRamp.js`) without a second kernel.
 * @returns {{canvas: HTMLCanvasElement, bloom: HTMLCanvasElement|null, cols: number, rows: number,
 *           gp: number, n: number, unc: number, img: ImageData,
 *           bloomImg: ImageData|null}|null} the field, or null when nothing can contribute
 */
export function field(pts, w, h, opts) {
  opts = opts || {};
  const conf = opts.conf == null ? 1 : clamp(opts.conf, 0, 1);
  const unc = 1 - conf;
  const R = opts.radius || Math.max(14, w * 0.085);
  const R2 = R * R;
  const gp = opts.grid || 4;
  const grid = bucketPoints(pts, w, h, R, gp);
  const { keep } = grid;
  if (!keep.length) return null;
  const cols = Math.ceil(w / gp) + 1;
  const rows = Math.ceil(h / gp) + 1;
  const off = document.createElement('canvas');
  off.width = cols;
  off.height = rows;
  const octx = off.getContext('2d');
  const img = octx.createImageData(cols, rows);
  // Optional emissive layer — see this function's own doc comment for why it exists. Allocated
  // only on request: every non-bloom surface (still most of them, until P2 flips the call sites)
  // must not pay for a second ImageData it never reads.
  const bl = opts.bloom ? octx.createImageData(cols, rows) : null;
  const ramp = opts.ramp || rampRgb;
  const bc = opts.bloomRGB || [255, 138, 66];
  const aMax = (opts.alpha == null ? 206 : opts.alpha) * (1 - 0.34 * unc);
  let sw = 0;
  let sws = 0;
  let px = 0;
  let py = 0;
  // One closure for the whole walk, not one per cell: it reads the cell under the cursor off
  // `px`/`py` and adds to `sw`/`sws`.
  const accumulate = (list) => {
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
  };
  for (let j = 0; j < rows; j += 1) {
    for (let i = 0; i < cols; i += 1) {
      px = i * gp;
      py = j * gp;
      sw = 0;
      sws = 0;
      forNeighbours(grid, px, py, accumulate);
      const k = (j * cols + i) * 4;
      if (sw < 0.02) {
        img.data[k + 3] = 0;
        continue;
      }
      const s0 = sws / sw;
      let c = ramp(s0);
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
      if (bl) {
        /* On the shared/temperature ramp the gate MUST stay at 3★ — where that ramp's own
           luminance peaks (see this function's doc comment and the design bundle README, "The
           heat bloom"). A monotonic ramp injected via `opts.ramp` (aurora, plan §4 #4) may pass
           its own `bloomFrom`, since it has no dead band. On the temperature ramp any higher
           gate leaves a DEAD BAND between 3★ and the gate in which the ramp is already darkening
           and the bloom contributes nothing, which reintroduces the exact inversion the bloom
           exists to remove. To stop a small surface washing out, cut `bloomBlur` in {@link paint}
           (which keeps the glow on the hot cores) — never raise the gate or drop the strength.
           Exponent 1.2 starts the climb gently so a 3.2★ does not glow.

           The bundle disagrees with itself on the size of that dead band: this kernel's own
           source comment (`docs/design/map-tab-v2/heat-field.js`) says a 3.7 gate put 3★ and 5★
           "0.2 luminance apart"; the bundle's README ("The heat bloom (required on a dark
           ground)") measures the same scenario at "0.9". Carrying the README's 0.9 here — the
           README is the bundle's measured reference table, the kernel comment an inline aside —
           and recording the kernel comment's variance so a reviewer doesn't flag this as a
           transcription error. Neither number changes the gate itself, which the rule above fixes
           at 3 regardless of which measurement is more precise. */
        const g = opts.bloomFrom == null ? 3 : opts.bloomFrom;
        const t = Math.pow(clamp((s0 - g) / (5 - g), 0, 1), 1.2);
        bl.data[k] = bc[0];
        bl.data[k + 1] = bc[1];
        bl.data[k + 2] = bc[2];
        bl.data[k + 3] = Math.round(
          t * clamp(cov, 0, 1) * (opts.bloomA == null ? 190 : opts.bloomA) * conf,
        );
      }
    }
  }
  octx.putImageData(img, 0, 0);
  let bloom = null;
  if (bl) {
    bloom = document.createElement('canvas');
    bloom.width = cols;
    bloom.height = rows;
    bloom.getContext('2d').putImageData(bl, 0, 0);
  }
  return {
    canvas: off, bloom, cols, rows, gp, n: keep.length, unc, img, bloomImg: bl,
  };
}

/**
 * Draws the field, then its emissive bloom pass, into {@code ctx} — shared by the hard-clip and
 * soft-mask routes below {@link paint} so both draw the same two layers in the same order (the
 * bundle's `_blit` split, kept private: nothing outside {@link paint} needs it).
 *
 * <p>The bloom pass is additive (`globalCompositeOperation:'lighter'`) and drawn INSIDE the same
 * surface as the field it just drew, which is why it works where a blend against the basemap
 * tiles could not: the tile host's overlay canvas is a DOM sibling of the tile pane and is
 * cleared every frame, so a canvas-level blend against the tiles has nothing to composite
 * against (see the design bundle README, "The heat bloom (required on a dark ground)").
 *
 * @param {CanvasRenderingContext2D} ctx target context
 * @param {object} f the field returned by {@link field}
 * @param {{blur?: number, opacity?: number, bloomBlur?: number}} opts {@link paint}'s own
 *        blur/opacity/bloomBlur dials — not {@link field}'s, which this function never calls
 */
function blitField(ctx, f, opts) {
  ctx.imageSmoothingEnabled = true;
  ctx.filter = `blur(${(opts.blur || 3) + f.unc * 2.6}px)`;
  ctx.globalAlpha = opts.opacity == null ? 0.92 : opts.opacity;
  ctx.drawImage(f.canvas, 0, 0, f.cols, f.rows, 0, 0, f.cols * f.gp, f.rows * f.gp);
  if (f.bloom) {
    ctx.globalCompositeOperation = 'lighter';
    ctx.filter = `blur(${(opts.blur || 3) * (opts.bloomBlur == null ? 2.4 : opts.bloomBlur) + f.unc * 3}px)`;
    ctx.drawImage(f.bloom, 0, 0, f.cols, f.rows, 0, 0, f.cols * f.gp, f.rows * f.gp);
  }
}

/**
 * Masks the temp surface {@code tc} with a BLURRED fill of {@code opts.clipPath}, so the land
 * boundary feathers at the same rate the layer under it does. {@link paint}'s soft route, shared
 * with {@link paintUnknown}.
 *
 * <p>The mask is composed on its OWN surface and applied in a single destination-in — see
 * {@link paint}'s doc comment for why fill-then-stroke straight onto the field cannot do this job.
 *
 * @param {CanvasRenderingContext2D} tc the temp surface, already drawn into at scale {@code sx}
 * @param {number} w surface width in CSS px
 * @param {number} h surface height in CSS px
 * @param {number} sx backing-store px per CSS px
 * @param {{clipPath: *, clipSoft: number, clipGrow?: number, clipDx?: number,
 *          clipDy?: number}} opts the soft route's dials
 */
function applySoftMask(tc, w, h, sx, opts) {
  const mk = document.createElement('canvas');
  mk.width = tc.canvas.width;
  mk.height = tc.canvas.height;
  const mc = mk.getContext('2d');
  mc.setTransform(sx, 0, 0, sx, 0, 0);
  mc.filter = `blur(${opts.clipSoft}px)`;
  mc.translate(opts.clipDx || 0, opts.clipDy || 0);
  mc.fillStyle = '#fff';
  mc.strokeStyle = '#fff';
  // clipGrow DILATES the mask by stroking the same path — a uniform band that follows the
  // coastline, absorbing a coarse coastline's survey error without inventing a shape. Unioning
  // discs at each location did the same job but drew visible circles offshore, because the
  // error is geographic and the disc would have to grow with zoom.
  if (opts.clipGrow > 0) {
    mc.lineWidth = opts.clipGrow * 2;
    mc.lineJoin = 'round';
    mc.lineCap = 'round';
    mc.stroke(opts.clipPath);
  }
  mc.fill(opts.clipPath);
  tc.globalCompositeOperation = 'destination-in';
  tc.globalAlpha = 1;
  tc.filter = 'none';
  tc.setTransform(1, 0, 0, 1, 0, 0);
  tc.drawImage(mk, 0, 0);
}

/**
 * Clips {@code ctx} to the caller's land by {@link paint}'s hard routes ({@code clipPath} slid by
 * {@code clipDx}/{@code clipDy}, else the {@code clip} callback), shared with
 * {@link paintUnknown}. The caller owns the surrounding save/restore.
 *
 * @param {CanvasRenderingContext2D} ctx target context
 * @param {{clipPath?: *, clipDx?: number, clipDy?: number, clip?: Function}} opts the clip dials
 */
function applyHardClip(ctx, opts) {
  if (opts.clipPath) {
    const dx = opts.clipDx || 0;
    const dy = opts.clipDy || 0;
    ctx.translate(dx, dy);
    ctx.clip(opts.clipPath);
    ctx.translate(-dx, -dy);
  } else if (opts.clip) {
    ctx.beginPath();
    opts.clip(ctx);
    ctx.clip();
  }
}

/**
 * Paints a field into any 2d context, blurred and composited.
 *
 * <p>Three clip routes, in order of precedence:
 *
 * <ul>
 *   <li>{@code opts.clipPath} + {@code opts.clipSoft} — the SOFT mask route (P4's land clip). A
 *   hard clip gives a crisp edge against a blurred field, which reads as an artifact the moment
 *   the mask contains anything the eye knows should not be a sharp line — a perfect circle, or a
 *   coastline at a zoom where 1:50m survey error is visible. This route renders the field (and
 *   its bloom) to a temp surface, then masks it with a BLURRED fill of the same path so the
 *   boundary feathers at the same rate the field does. The mask is composed on its OWN surface —
 *   `mk` below — and applied to the temp surface in a SINGLE `destination-in` draw. Filling then
 *   stroking the SAME path directly onto the field with `destination-in` already set would make
 *   the two draws INTERSECT rather than union, which erases the land the dilation (`clipGrow`)
 *   was meant to extend and leaves only the dilation band. `clipPath` is treated as opaque
 *   throughout — handed only to `fill`/`stroke`/`clip`, never constructed or introspected, so it
 *   can be a `Path2D` in a browser or any object a caller's clip abstraction produces.</li>
 *   <li>{@code opts.clipPath} alone — the HARD clip route. `clipPath` lives in absolute pixel
 *   space, offset by {@code clipDx}/{@code clipDy}: the tile host builds one land mask per zoom
 *   and slides it, so the coast is a clip rather than a redraw every frame.</li>
 *   <li>{@code opts.clip} — a callback that lays a path into {@code ctx} itself (the geo host's
 *   usage, where d3 has already bound a `geoPath` to this exact context).</li>
 * </ul>
 *
 * @param {CanvasRenderingContext2D} ctx target context
 * @param {number} w surface width in CSS px
 * @param {number} h surface height in CSS px
 * @param {Array<object>} pts points, as {@link field}
 * @param {{clip?: Function, clipPath?: *, clipSoft?: number, clipGrow?: number, clipDx?: number,
 *          clipDy?: number, blur?: number, opacity?: number, bloomBlur?: number}} [opts] plus
 *        everything {@link field} takes
 * @returns {object|null} the field that was painted, or null when there was nothing to paint
 */
export function paint(ctx, w, h, pts, opts) {
  opts = opts || {};
  const f = field(pts, w, h, opts);
  if (!f) return null;
  if (opts.clipPath && opts.clipSoft) {
    const t = document.createElement('canvas');
    t.width = ctx.canvas.width;
    t.height = ctx.canvas.height;
    const tc = t.getContext('2d');
    const sx = t.width / w;
    tc.setTransform(sx, 0, 0, sx, 0, 0);
    blitField(tc, f, opts);
    applySoftMask(tc, w, h, sx, opts);
    ctx.save();
    ctx.globalAlpha = 1;
    ctx.filter = 'none';
    ctx.globalCompositeOperation = 'source-over';
    ctx.drawImage(t, 0, 0, w, h);
    ctx.restore();
    return f;
  }
  ctx.save();
  applyHardClip(ctx, opts);
  blitField(ctx, f, opts);
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
  const px = Math.round(w * dpr);
  const py = Math.round(h * dpr);
  // ⚠️ Guarded, because assigning `canvas.width` REALLOCATES and zeroes the backing store even when
  // the value is unchanged. The two static hosts call this on a resize and never noticed; P4's
  // Leaflet host calls it on every frame of a pan, where an unguarded write is ~19.8 MB of
  // allocate-and-memset per frame on a full-screen desktop map — about 1.2 GB/s at 60fps, before a
  // single pixel is drawn. The kernel's arithmetic is untouched: the assignments still happen
  // whenever the size genuinely changes, and `drawTiles` clears the canvas itself.
  if (cv.width !== px || cv.height !== py) {
    cv.width = px;
    cv.height = py;
  }
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
 * Px per km at {@code refPoint}, measured off the projection rather than assumed, so a ring drawn
 * from it is real distance. 1° latitude ≈ 111.2 km.
 *
 * <p>The bundle's own {@code kmPx}, parameterised on the reference point instead of a hard-coded
 * home coordinate — production's home is a per-user saved geocode, never a constant.
 *
 * @param {Function} project the projection, mapping {@code [lng, lat]} to a screen point
 * @param {number[]} refPoint {@code [lng, lat]} to measure the scale at
 * @returns {number} px per km at that point
 */
export function kmPerPx(project, refPoint) {
  const a = project(refPoint);
  const b = project([refPoint[0], refPoint[1] + 1]);
  return Math.abs(b[1] - a[1]) / 111.2;
}

/**
 * The unscored plate's hatch — spacing in CSS px, ink and line width.
 *
 * <p>CSS px rather than a fraction of the surface, so the texture reads the same on a 55px
 * six-across thumbnail and a 110px three-across one; a proportional gap would give the small
 * tile four lines and the large one four wider-spaced lines, which is a different mark rather
 * than the same mark at two sizes.
 *
 * <p>The ink is the coastline's own bone at a lower alpha (.18 against the stroke's .30) —
 * the hatch must read as part of the plate rather than as a second, competing coastline.
 */
const HATCH_GAP = 7;
const HATCH_LINE = 0.6;
const HATCH_INK = 'rgba(242,231,211,.18)';

/**
 * Rules a 45° hatch across the land plate, clipped to the coastline.
 *
 * <p><b>It marks "nothing was scored here", never "everything scored badly".</b> The two are
 * indistinguishable on an unhatched plate — the kernel returns null for an empty point set, so a
 * window nobody rated renders as bare geography, exactly as a window whose field happened to
 * paint nothing would. That blank was being read as a forecast: on a Plan strip where the verdict
 * word underneath comes from the briefing's own weather thresholds (which run the full horizon),
 * an unscored Saturday printed a confident "Poor" above an empty map beside a Thursday whose
 * identical "Poor" was 200 locations actually rated bad.
 *
 * <p>It is deliberately mute about WHY. The reason lives in the served verdict word and region
 * wording (the honesty filter words a blank by what the pipeline did: not scored at this range, a
 * travel day, a run that did not finish, or a weather stand-down) — all this plate sees is a
 * window with no ratings — so the mark says that and no more, and the strip's footer is where the
 * convention is named in words.
 *
 * @param {CanvasRenderingContext2D} ctx target context
 * @param {number} w surface width in CSS px
 * @param {number} h surface height in CSS px
 * @param {Function} clip lays the land path into {@code ctx}, taking NO arguments — it is the
 *        caller's {@code geoPath}, which d3 has already bound to this context. {@code paint}'s
 *        {@code opts.clip} is handed {@code ctx} for the same job, and that is a public option
 *        whose arity is the caller's business; this one has a single call site and passing it an
 *        argument the arrow never declared claimed a contract nothing honours.
 * @param {{hatchGap?: number, hatchInk?: string, hatchLine?: number}} opts the hatch's dials
 */
function hatchPlate(ctx, w, h, clip, opts) {
  ctx.save();
  ctx.beginPath();
  clip();
  ctx.clip();
  ruleHatch(ctx, w, h, opts);
  ctx.restore();
}

/**
 * Rules the 45° hatch across the whole {@code w}×{@code h} surface, unclipped — the mark itself,
 * shared by {@link hatchPlate} (clipped to the plate) and {@link paintUnknown} (masked to the
 * ground around unrated places).
 *
 * @param {CanvasRenderingContext2D} ctx target context, drawing in CSS px
 * @param {number} w surface width in CSS px
 * @param {number} h surface height in CSS px
 * @param {{hatchGap?: number, hatchInk?: string, hatchLine?: number}} opts the hatch's dials
 */
function ruleHatch(ctx, w, h, opts) {
  // Floored at 1: a zero or negative gap does not draw a denser hatch, it hangs the render
  // thread. The option exists for the tests, and a shared kernel should not carry a loop a
  // caller can freeze the tab with.
  const gap = Math.max(1, opts.hatchGap || HATCH_GAP);
  ctx.beginPath();
  // 45°, drawn from below the left edge to beyond the right so both corners are covered: a line
  // starting at (x, h) ends at (x + h, 0), so the sweep has to begin a full height to the left.
  for (let x = -h; x < w; x += gap) {
    ctx.moveTo(x, h);
    ctx.lineTo(x + h, 0);
  }
  ctx.strokeStyle = opts.hatchInk || HATCH_INK;
  ctx.lineWidth = opts.hatchLine || HATCH_LINE;
  ctx.stroke();
}

/** One isolated place's own peak coverage is 0.58; the mask's threshold sits a margin below it. */
const UNKNOWN_THRESHOLD = 0.45;

/**
 * The unknown-ground mask: how strongly each grid cell should carry the "not rated yet" hatch.
 *
 * <p>Per cell, over the places within the kernel's cutoff ({@code d² <= 6R²}), with the same
 * gaussian weight the field uses: {@code su} sums the UNRATED places, {@code sr} the RATED ones;
 * each is run through the field's own coverage clamp ({@code 1 - exp(-s / 1.15)}) to
 * {@code cu}/{@code cr}; then {@code a = clamp(cu / 0.45, 0, 1) * clamp(1 - cr / 0.45, 0, 1)}.
 * 0.45 is about one isolated place's own peak coverage (0.58) less a margin, so a lone unrated
 * place gets a solid disc, and a rated place suppresses the hatch where it stands — colour wins
 * at an overlap. Empty ground is 0: no places, no claim.
 *
 * <p>Shares {@link bucketPoints}/{@link forNeighbours} with {@link field}, so the two walk one
 * lattice. {@code opts.focus} multiplies every other region's weight by 1e-4, exactly as the
 * field's colour fades them, so a focused region's hatch fades with its colour.
 *
 * @param {Array<{x: number, y: number, rid: *}>} unrated unrated places in CSS px of the surface
 * @param {Array<{x: number, y: number, rid: *}>} rated rated places in CSS px of the surface
 * @param {number} w surface width in CSS px
 * @param {number} h surface height in CSS px
 * @param {{radius?: number, grid?: number, focus?: *}} [opts] the field's radius, grid and focus
 * @returns {{cols: number, rows: number, gp: number, alpha: Float32Array}|null} per-cell mask in
 *          [0, 1], or null when no unrated place can reach the frame
 */
export function unknownMask(unrated, rated, w, h, opts) {
  opts = opts || {};
  const R = opts.radius || Math.max(14, w * 0.085);
  const R2 = R * R;
  const gp = opts.grid || 4;
  const ug = bucketPoints(unrated, w, h, R, gp);
  if (!ug.keep.length) return null;
  const rg = bucketPoints(rated, w, h, R, gp);
  const cols = Math.ceil(w / gp) + 1;
  const rows = Math.ceil(h / gp) + 1;
  const alpha = new Float32Array(cols * rows);
  let px = 0;
  let py = 0;
  let sum = 0;
  const accumulate = (list) => {
    for (const p of list) {
      const dx = px - p.x;
      const dy = py - p.y;
      const d2 = dx * dx + dy * dy;
      if (d2 > R2 * 6) continue;
      let wt = Math.exp(-d2 / R2);
      if (opts.focus && p.rid !== opts.focus) wt *= 1e-4;
      sum += wt;
    }
  };
  for (let j = 0; j < rows; j += 1) {
    for (let i = 0; i < cols; i += 1) {
      px = i * gp;
      py = j * gp;
      sum = 0;
      forNeighbours(ug, px, py, accumulate);
      const su = sum;
      sum = 0;
      forNeighbours(rg, px, py, accumulate);
      const sr = sum;
      const cu = 1 - Math.exp(-su / 1.15);
      const cr = 1 - Math.exp(-sr / 1.15);
      alpha[j * cols + i] = clamp(cu / UNKNOWN_THRESHOLD, 0, 1)
        * clamp(1 - cr / UNKNOWN_THRESHOLD, 0, 1);
    }
  }
  return { cols, rows, gp, alpha };
}

/**
 * Paints the "not rated yet" hatch around unrated places, UNDER the field and ABOVE the plate.
 *
 * <p>The hatch is ruled over the whole surface on a temp canvas, multiplied by the
 * {@link unknownMask} (upscaled with the field's OWN blur, {@code blur + (1 - conf) * 2.6}, so the
 * edge feathers at the colour's rate) in one {@code destination-in}, then drawn through the same
 * land clip the field takes: the soft route when {@code clipPath} + {@code clipSoft} are set, else
 * the hard ones. It honours {@code opts.focus} through the mask.
 *
 * @param {CanvasRenderingContext2D} ctx target context, drawing in CSS px
 * @param {number} w surface width in CSS px
 * @param {number} h surface height in CSS px
 * @param {Array<{x: number, y: number, rid: *}>} unrated unrated places, in CSS px
 * @param {Array<{x: number, y: number, rid: *}>} rated rated places, in CSS px
 * @param {object} [opts] what {@link paint} takes for its clip, plus {@code radius}, {@code grid},
 *        {@code blur}, {@code conf}, {@code focus}, the hatch dials and {@code opacity} (a factor
 *        on the final draw; absent is 1)
 * @returns {boolean} whether anything was drawn
 */
export function paintUnknown(ctx, w, h, unrated, rated, opts) {
  opts = opts || {};
  return paintMasked(ctx, w, h, unrated, rated, opts, (tc) => ruleHatch(tc, w, h, opts));
}

/** The stand-down fill: a dark veil under the rule, so the hatch reads on any basemap. */
const STAND_FILL = 'rgba(16,15,19,.66)';
/** The stand-down rule: an 8px pitch of 1.2px 45 degree lines in a cool grey, off the score ramp. */
const STAND_RULE = { hatchGap: 8, hatchLine: 1.2, hatchInk: 'rgba(184,197,220,.5)' };

/**
 * Paints the stand-down hatch around the places a window stood down for the weather, UNDER the
 * field and ABOVE the plate (big-seas plan F3; docs/design/big-seas/README.md "Stand-down hatch").
 *
 * <p>The same projection-independent signature as {@link paintUnknown} (points already in CSS px,
 * no Leaflet {@code map}), so the Map and the Plan tab's d3 hosts share it. It differs in the
 * texture (a {@link STAND_FILL} veil, then the {@link STAND_RULE} lines), in taking the disc radius
 * from {@code opts.standRadius} (the stood places' reach, not the field's), and in nothing else:
 * the mask is {@link unknownMask} with the stood places in place of the unrated ones, so a rated
 * place's colour wins where the two overlap, and the clip is the same soft land path.
 *
 * @param {CanvasRenderingContext2D} ctx target context, drawing in CSS px
 * @param {number} w surface width in CSS px
 * @param {number} h surface height in CSS px
 * @param {Array<{x: number, y: number, rid: *}>} stood stood places, in CSS px
 * @param {Array<{x: number, y: number, rid: *}>} rated rated places, in CSS px
 * @param {object} [opts] what {@link paintUnknown} takes, plus {@code standRadius} (the disc
 *        radius in px; absent, the field's {@code radius})
 * @returns {boolean} whether anything was drawn
 */
export function paintStandDown(ctx, w, h, stood, rated, opts) {
  opts = opts || {};
  const merged = opts.standRadius ? { ...opts, radius: opts.standRadius } : opts;
  return paintMasked(ctx, w, h, stood, rated, merged, (tc) => {
    tc.fillStyle = STAND_FILL;
    tc.fillRect(0, 0, w, h);
    ruleHatch(tc, w, h, STAND_RULE);
  });
}

/**
 * The shared body of {@link paintUnknown} and {@link paintStandDown}: ruled onto a temp canvas by
 * {@code rule}, multiplied by the {@link unknownMask} in one {@code destination-in}, then drawn
 * through the land clip.
 *
 * @param {CanvasRenderingContext2D} ctx target context, drawing in CSS px
 * @param {number} w surface width in CSS px
 * @param {number} h surface height in CSS px
 * @param {Array<{x: number, y: number, rid: *}>} subject the places the layer is drawn around
 * @param {Array<{x: number, y: number, rid: *}>} rated rated places, which the mask yields to
 * @param {object} opts the options both layers take
 * @param {function(CanvasRenderingContext2D): void} rule draws the texture on the temp canvas
 * @returns {boolean} whether anything was drawn
 */
function paintMasked(ctx, w, h, subject, rated, opts, rule) {
  const m = unknownMask(subject, rated, w, h, opts);
  if (!m) return false;
  const off = document.createElement('canvas');
  off.width = m.cols;
  off.height = m.rows;
  const octx = off.getContext('2d');
  const data = octx.createImageData(m.cols, m.rows);
  for (let k = 0; k < m.alpha.length; k += 1) {
    const o = k * 4;
    data.data[o] = 255;
    data.data[o + 1] = 255;
    data.data[o + 2] = 255;
    data.data[o + 3] = Math.round(m.alpha[k] * 255);
  }
  octx.putImageData(data, 0, 0);
  const unc = 1 - (opts.conf == null ? 1 : clamp(opts.conf, 0, 1));
  const W = ctx.canvas.width;
  const H = ctx.canvas.height;
  const sx = W / w;
  const t = document.createElement('canvas');
  t.width = W;
  t.height = H;
  const tc = t.getContext('2d');
  tc.setTransform(sx, 0, 0, sx, 0, 0);
  rule(tc);
  const mk = document.createElement('canvas');
  mk.width = W;
  mk.height = H;
  const mc = mk.getContext('2d');
  mc.setTransform(sx, 0, 0, sx, 0, 0);
  mc.imageSmoothingEnabled = true;
  mc.filter = `blur(${(opts.blur || 3) + unc * 2.6}px)`;
  mc.drawImage(off, 0, 0, m.cols, m.rows, 0, 0, m.cols * m.gp, m.rows * m.gp);
  tc.setTransform(1, 0, 0, 1, 0, 0);
  tc.globalCompositeOperation = 'destination-in';
  tc.drawImage(mk, 0, 0);
  const soft = Boolean(opts.clipPath && opts.clipSoft);
  if (soft) applySoftMask(tc, w, h, sx, opts);
  ctx.save();
  if (!soft) applyHardClip(ctx, opts);
  // The hatch is part of the field, so it takes the field's own opacity (the Map tab's zoom handover
  // and Ask's dim); absent, full strength, which is what the Plan hosts have always drawn.
  ctx.globalAlpha = opts.opacity == null ? 1 : clamp(opts.opacity, 0, 1);
  ctx.filter = 'none';
  ctx.globalCompositeOperation = 'source-over';
  ctx.drawImage(t, 0, 0, w, h);
  ctx.restore();
  return true;
}

/**
 * Points ready for the unknown layer from caller spots — projected, with only what the mask reads.
 *
 * @param {Array<object>} spots spots with {@code lat}, {@code lng}, {@code rid}
 * @param {function(object): number[]} project maps a spot to {@code [x, y]}
 * @returns {Array<{x: number, y: number, rid: *}>} mask points
 */
function maskPoints(spots, project) {
  return (Array.isArray(spots) ? spots : []).map((s) => {
    const p = project(s);
    return { x: p[0], y: p[1], rid: s.rid };
  });
}

/**
 * The rated points' mask projection — only a place with a finite score takes part, because a
 * null-scored point is not a rating however it got into the point set.
 *
 * @param {Array<{x: number, y: number, sc: *, rid: *}>} pts the field's projected points
 * @returns {Array<{x: number, y: number, rid: *}>} the rated ones
 */
function ratedMaskPoints(pts) {
  return pts.filter((p) => typeof p.sc === 'number' && Number.isFinite(p.sc));
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
 * @param {object} [opts] `fit`, `sea`, `plate`, `stroke`, `line`, `focus`, `hatch`, `unrated` +
 *        everything {@link field} takes. `unrated` is the "not rated yet" places
 *        ({@code unratedPointsFor}'s shape): the ground around them is hatched, under the field and
 *        over the plate, so colour wins where a rated place is near — absent, nothing is drawn.
 *        `stood` (with `standRadius`, the disc radius in px) does the same for the weather-stood
 *        places ({@link paintStandDown}), painted straight after the unrated hatch.
 *        `hatch` rules {@link hatchPlate}'s no-data texture over the plate;
 *        it is the CALLER's claim that this window carries no scores, never inferred from an
 *        empty field — {@link field} also returns null when every point is culled outside the
 *        frame, which is a framing answer rather than an absence of data.
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
  const clip = () => path(LAND);
  // Above the plate, under the field: colour wins where a rated place is near. Absent by default,
  // so every existing host's output is exactly what it was.
  if (opts.unrated && opts.unrated.length) {
    paintUnknown(
      ctx, w, h,
      maskPoints(opts.unrated, (s) => projection([s.lng, s.lat])),
      ratedMaskPoints(pts),
      { ...opts, clip },
    );
  }
  // Stood-down ground (big-seas plan F3/F4): after the unrated hatch, still under the field, so a
  // rated neighbour's colour wins. Absent by default.
  if (opts.stood && opts.stood.length) {
    paintStandDown(
      ctx, w, h,
      maskPoints(opts.stood, (s) => projection([s.lng, s.lat])),
      ratedMaskPoints(pts),
      { ...opts, clip },
    );
  }
  paint(ctx, w, h, pts, { ...opts, alpha: opts.focus ? 238 : 206, clip });
  // After the field and before the coastline: the mark belongs to the plate, so it sits under the
  // edge that defines the plate. Ordinary callers never set it, so every existing host's paint
  // order is unchanged.
  if (opts.hatch) hatchPlate(ctx, w, h, () => path(LAND), opts);
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
 * @param {{score?: (spot: object) => number, unrated?: Array<object>, stood?: Array<object>,
 *        standRadius?: number}} [opts] `stood` hatches the ground around the weather-stood places
 *        ({@link paintStandDown}), after the unrated hatch and under the field; `score` lets a host
 *        score a location by something other than a solar window index — the Map tab's night
 *        events (astro, aurora) are derived, not present in `r[]` — and `unrated` hatches the
 *        ground around those places under the field (see {@link drawGeo}; the soft land clip applies),
 *        plus everything {@link field} and {@link paint} take
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
  const score = opts.score || ((s) => s.r[win]);
  const pts = spots.map((s) => {
    const p = map.latLngToContainerPoint([s.lat, s.lng]);
    return { x: p.x, y: p.y, sc: score(s), rid: s.rid };
  });
  if (opts.unrated && opts.unrated.length) {
    paintUnknown(
      ctx, w, h,
      maskPoints(opts.unrated, (s) => {
        const p = map.latLngToContainerPoint([s.lat, s.lng]);
        return [p.x, p.y];
      }),
      ratedMaskPoints(pts),
      opts,
    );
  }
  // After the unrated hatch, before the field (big-seas plan F3): rated colour wins.
  paintStoodOnTiles(ctx, w, h, map, opts, pts);
  return paint(ctx, w, h, pts, opts);
}

/**
 * Paints {@code opts.stood} on a Leaflet host's context, when there is any.
 *
 * @param {CanvasRenderingContext2D} ctx target context, drawing in CSS px
 * @param {number} w map width in CSS px
 * @param {number} h map height in CSS px
 * @param {object} map a Leaflet map
 * @param {{stood?: Array<object>}} opts the host's options
 * @param {Array<{x: number, y: number, sc: *, rid: *}>} pts the field's projected points
 * @returns {boolean} whether anything was drawn
 */
function paintStoodOnTiles(ctx, w, h, map, opts, pts) {
  if (!opts.stood || !opts.stood.length) return false;
  return paintStandDown(
    ctx, w, h,
    maskPoints(opts.stood, (s) => {
      const p = map.latLngToContainerPoint([s.lat, s.lng]);
      return [p.x, p.y];
    }),
    ratedMaskPoints(pts),
    opts,
  );
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
