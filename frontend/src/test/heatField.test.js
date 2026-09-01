import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  BBOX,
  aspect,
  bbox,
  centroid,
  clamp,
  drawGeo,
  drawTiles,
  field,
  fit,
  kmPerPx,
  land,
  latLngBounds,
  load,
  paint,
  proj,
  radiusFor,
  rgb,
} from '../utils/heatField.js';
import { rampRgb, rgb as scoreRampRgb } from '../utils/scoreRamp.js';

/**
 * The heat kernel, tested through a stubbed 2d context.
 *
 * jsdom's `getContext('2d')` returns null, and the plan is explicit that no global canvas
 * polyfill goes into `setup.js` — one file's canvas needs must not silently change what every
 * other file is rendering into. So the stub is local, and it records its calls so the two hosts'
 * paint order can be asserted rather than assumed.
 *
 * The stub's `createImageData` hands back a real `Uint8ClampedArray`, which is not a detail: the
 * kernel writes fractional channel values straight into it and relies on the typed array's own
 * rounding. A plain `Array` would silently store the fractions and every colour assertion below
 * would be testing something the browser never does.
 */

/** The path methods d3's `geoPath(projection, context)` drives when it renders the coastline. */
const PATH_METHODS = ['moveTo', 'lineTo', 'closePath', 'arc'];

function makeContext() {
  const calls = [];
  // Snapshot the paint state with every call. `fillStyle`/`strokeStyle`/`lineWidth` are plain
  // properties, so a recorder that stored only the method name could not tell the sea fill from
  // the land plate — and swapping those two defaults renders an inverted map that no assertion
  // about call ORDER can see. `globalCompositeOperation`/`filter` join them for P1: the bloom
  // pass is distinguished from the field draw ONLY by switching to `'lighter'` and a different
  // blur between two otherwise-identical `drawImage` calls, and a snapshot taken after the fact
  // (as the two pre-existing `ctx.filter` assertions below do) cannot tell which of several calls
  // it belonged to.
  const record =
    (name) =>
    (...args) => {
      calls.push({
        name,
        args,
        fillStyle: ctx.fillStyle,
        strokeStyle: ctx.strokeStyle,
        lineWidth: ctx.lineWidth,
        globalCompositeOperation: ctx.globalCompositeOperation,
        filter: ctx.filter,
      });
    };
  const ctx = {
    calls,
    filter: 'none',
    globalAlpha: 1,
    globalCompositeOperation: 'source-over',
    imageSmoothingEnabled: false,
    fillStyle: '',
    strokeStyle: '',
    lineWidth: 0,
    lineJoin: '',
    lineCap: '',
    createImageData: (w, h) => ({
      data: new Uint8ClampedArray(w * h * 4),
      width: w,
      height: h,
    }),
  };
  ['putImageData', 'clearRect', 'fillRect', 'save', 'restore', 'drawImage', 'beginPath', 'fill',
    'stroke', 'clip', 'setTransform', 'translate', ...PATH_METHODS].forEach((name) => {
    ctx[name] = record(name);
  });
  return ctx;
}

let contexts;
let originalGetContext;
let originalDpr;

beforeEach(() => {
  contexts = new WeakMap();
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function getContextStub() {
    if (!contexts.has(this)) {
      const ctx = makeContext();
      // A real `CanvasRenderingContext2D.canvas` is a read-only back-reference to its element.
      // The soft-mask route (`paint`'s `clipPath` + `clipSoft` branch) reads `ctx.canvas.width`
      // to size its temp surface, exactly as the design bundle does — without this the stub
      // context has no `.canvas` at all and that read throws before the mask is ever built.
      ctx.canvas = this;
      contexts.set(this, ctx);
    }
    return contexts.get(this);
  };
  originalDpr = window.devicePixelRatio;
});

afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  window.devicePixelRatio = originalDpr;
});

/** A canvas whose stub context is reachable for call-order assertions. */
function canvasWithContext() {
  const cv = document.createElement('canvas');
  return { cv, ctx: cv.getContext('2d') };
}

/** The names of the drawing calls made on a context, in order. */
function callNames(ctx) {
  return ctx.calls.map((c) => c.name);
}

/** The stub context belonging to a canvas the kernel created internally. */
function contextOf(canvas) {
  return canvas.getContext('2d');
}

/**
 * Reads one grid cell's RGBA out of a field's ImageData — `img` by default, or `bloomImg` for
 * the P1 emissive layer (same cell indexing; the bloom grid is sized identically to the field's).
 */
function cell(f, i, j, key = 'img') {
  const k = (j * f.cols + i) * 4;
  const data = f[key].data;
  return [data[k], data[k + 1], data[k + 2], data[k + 3]];
}

/** Deterministic PRNG — a fixed seed so a failure is reproducible rather than "sometimes". */
function mulberry32(seed) {
  let a = seed;
  return function next() {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * The comparand for the bucketing optimisation: the same field with no cull and no buckets, every
 * cell summing every point. Written from the algorithm as stated rather than copied from the
 * kernel, so a change to the kernel's structure cannot quietly change both sides at once.
 */
function bruteForceField(pts, w, h, opts = {}) {
  const conf = opts.conf == null ? 1 : Math.max(0, Math.min(1, opts.conf));
  const unc = 1 - conf;
  const R = opts.radius || Math.max(14, w * 0.085);
  const R2 = R * R;
  const gp = opts.grid || 4;
  const cols = Math.ceil(w / gp) + 1;
  const rows = Math.ceil(h / gp) + 1;
  const aMax = (opts.alpha == null ? 206 : opts.alpha) * (1 - 0.34 * unc);
  const data = new Uint8ClampedArray(cols * rows * 4);
  for (let j = 0; j < rows; j += 1) {
    for (let i = 0; i < cols; i += 1) {
      const px = i * gp;
      const py = j * gp;
      let sw = 0;
      let sws = 0;
      for (const p of pts) {
        const dx = px - p.x;
        const dy = py - p.y;
        const d2 = dx * dx + dy * dy;
        if (d2 > R2 * 6) continue;
        let wt = Math.exp(-d2 / R2);
        if (opts.focus && p.rid !== opts.focus) wt *= 1e-4;
        sw += wt;
        sws += wt * p.sc;
      }
      const k = (j * cols + i) * 4;
      if (sw < 0.02) continue;
      let c = rampRgb(sws / sw);
      const cov = 1 - Math.exp(-sw / 1.15);
      if (unc > 0) {
        const g = (c[0] + c[1] + c[2]) / 3;
        const d = 0.6 * unc;
        c = [c[0] + (g - c[0]) * d, c[1] + (g - c[1]) * d, c[2] + (g - c[2]) * d];
      }
      data[k] = c[0];
      data[k + 1] = c[1];
      data[k + 2] = c[2];
      data[k + 3] = Math.round(Math.max(0, Math.min(1, cov)) * aMax);
    }
  }
  return { data, cols, rows };
}

/** ~40 points over and around a `w × h` frame, at a fixed seed. */
function seededPoints(seed, w, h, count = 40) {
  const rnd = mulberry32(seed);
  return Array.from({ length: count }, () => ({
    // ±40% beyond each edge. Measured across the seeds used below: 12-16 of 40 land IN frame and
    // the rest outside it, some beyond the cull margin — P(in frame) is (1/1.8)² = 0.31. That is
    // the mix the bucket walk has to handle, and it is deliberately mostly-outside.
    x: Math.round((rnd() * 1.8 - 0.4) * w),
    y: Math.round((rnd() * 1.8 - 0.4) * h),
    sc: 1 + rnd() * 4,
    rid: ['north', 'south', 'east'][Math.floor(rnd() * 3)],
  }));
}

describe('heatField — the kernel', () => {
  describe('bucketing equals brute force', () => {
    /**
     * THE test that guards the performance structure. The 3×3 bucket walk and the cull are both
     * optimisations, and an optimisation is only allowed if it computes the same thing: bucket
     * size is the cutoff distance precisely so a point within `d² ≤ 6R²` of a cell can never sit
     * outside that cell's neighbourhood. If someone "simplifies" the walk to a smaller
     * neighbourhood, or narrows the cull margin, this fails and nothing else would.
     *
     * Tolerance is ±1 per channel and that is not slack for a wrong answer: the term sets are
     * identical, but the two accumulators sum them in different orders, so a value sitting on a
     * rounding boundary can land either side of it. A structural error moves whole regions of the
     * field, not one least-significant bit.
     */
    const compare = (f, brute) => {
      expect(f.cols).toBe(brute.cols);
      expect(f.rows).toBe(brute.rows);
      const offenders = [];
      for (let k = 0; k < brute.data.length; k += 1) {
        if (Math.abs(f.img.data[k] - brute.data[k]) > 1) {
          offenders.push(`byte ${k}: bucketed ${f.img.data[k]} vs brute ${brute.data[k]}`);
        }
      }
      expect(offenders.slice(0, 5)).toEqual([]);
    };

    it('matches on a mixed in-frame / out-of-frame point set', () => {
      const pts = seededPoints(20260818, 240, 160);
      const opts = { radius: 26, grid: 4 };
      compare(field(pts, 240, 160, opts), bruteForceField(pts, 240, 160, opts));
    });

    it('matches under focus, where most points carry a near-zero weight', () => {
      const pts = seededPoints(77, 240, 160);
      const opts = { radius: 26, grid: 4, focus: 'north' };
      compare(field(pts, 240, 160, opts), bruteForceField(pts, 240, 160, opts));
    });

    it('matches under reduced confidence, where the haze rewrites every channel', () => {
      const pts = seededPoints(1234, 200, 200);
      const opts = { radius: 30, grid: 6, conf: 0.4 };
      compare(field(pts, 200, 200, opts), bruteForceField(pts, 200, 200, opts));
    });

    it('matches at a dense small radius, where buckets hold few points each', () => {
      const pts = seededPoints(5, 180, 120, 60);
      const opts = { radius: 14, grid: 3 };
      compare(field(pts, 180, 120, opts), bruteForceField(pts, 180, 120, opts));
    });
  });

  describe('the cull', () => {
    // Frame and dials chosen so the margin is a round number: CUT = 2.45 × 20 = 49, plus the
    // grid step 4, so the cull line sits exactly 53px outside each edge.
    const W = 120;
    const H = 80;
    const OPTS = { radius: 20, grid: 4 };
    const MARGIN = 2.45 * 20 + 4;

    it('keeps a point exactly on the margin and drops one just beyond it', () => {
      expect(MARGIN).toBe(53);
      expect(field([{ x: -MARGIN, y: 40, sc: 4 }], W, H, OPTS).n).toBe(1);
      expect(field([{ x: -MARGIN - 0.001, y: 40, sc: 4 }], W, H, OPTS)).toBeNull();
    });

    it('applies the margin on all four edges', () => {
      const inside = [
        { x: -MARGIN, y: 40, sc: 4 },
        { x: W + MARGIN, y: 40, sc: 4 },
        { x: 60, y: -MARGIN, sc: 4 },
        { x: 60, y: H + MARGIN, sc: 4 },
      ];
      expect(field(inside, W, H, OPTS).n).toBe(4);
      const outside = inside.map((p, i) => ({
        ...p,
        x: i < 2 ? p.x + (i === 0 ? -0.001 : 0.001) : p.x,
        y: i < 2 ? p.y : p.y + (i === 2 ? -0.001 : 0.001),
      }));
      expect(field(outside, W, H, OPTS)).toBeNull();
    });

    it('changes nothing it drops — a culled point is genuinely out of reach', () => {
      const anchor = { x: 60, y: 40, sc: 5 };
      const alone = field([anchor], W, H, OPTS);
      const withDistant = field([anchor, { x: -400, y: -400, sc: 1 }], W, H, OPTS);
      expect(withDistant.n).toBe(1);
      expect(Array.from(withDistant.img.data)).toEqual(Array.from(alone.img.data));
    });

    it('does paint for a point inside the frame near an edge', () => {
      const f = field([{ x: 2, y: 40, sc: 5 }], W, H, OPTS);
      expect(f.n).toBe(1);
      expect(cell(f, 0, 10)[3]).toBeGreaterThan(0);
    });

    it('returns null for an empty point set', () => {
      expect(field([], W, H, OPTS)).toBeNull();
    });
  });

  describe('the coverage clamp', () => {
    /**
     * The clamp is how this heat map avoids the usual lie — colouring in empty moorland by
     * interpolating from thirty miles away. `Σw < 0.02` is fully transparent, and with R = 20 that
     * threshold falls at d = 20·√(−ln 0.02) = 39.56px, comfortably inside the 2.449R cutoff. So a
     * single point genuinely has three zones: painted, in-reach-but-too-thin, and out of reach.
     */
    const W = 200;
    const H = 2;
    const OPTS = { radius: 20, grid: 1 };
    const POINT = { x: 100, y: 0, sc: 5 };

    it('paints a cell 39px away, where Σw is 0.0223', () => {
      const f = field([POINT], W, H, OPTS);
      expect(cell(f, 61, 0)[3]).toBeGreaterThan(0);
    });

    it('leaves a cell 40px away fully transparent, where Σw is 0.0183', () => {
      const f = field([POINT], W, H, OPTS);
      expect(cell(f, 60, 0)).toEqual([0, 0, 0, 0]);
    });

    it('leaves the far side of the cutoff transparent too', () => {
      const f = field([POINT], W, H, OPTS);
      expect(cell(f, 10, 0)).toEqual([0, 0, 0, 0]);
    });

    it('drops a point past d² > 6R² even when other points hold the cell above the clamp', () => {
      // The cutoff is invisible in a single-point fixture: for one point the coverage clamp bites
      // first (at d = 39.56px) and no cell is ever decided by the 2.449R cutoff. With a near
      // anchor holding Σw well above 0.02, the far point's inclusion is decided by the cutoff
      // ALONE — so widening it to 9R² changes this cell's colour, and narrowing it to 5R² too.
      // Cell 100. The anchor sits 30px away (Σw = 0.105, comfortably over the clamp) and scores
      // 5; the test point scores 1 and sits at 48px (d² = 2304 < 6R² = 2400) or 50px (2500 > 2400).
      // Observing away from the anchor is the point: on top of it the far point is 0.3% of the sum
      // and rounds away, which is precisely why a single-point fixture cannot pin this constant.
      const anchor = { x: 70, y: 0, sc: 5 };
      const insideCutoff = { x: 148, y: 0, sc: 1 };
      const outsideCutoff = { x: 150, y: 0, sc: 1 };
      const alone = field([anchor], W, H, OPTS);
      const withNear = field([anchor, insideCutoff], W, H, OPTS);
      const withFar = field([anchor, outsideCutoff], W, H, OPTS);
      expect(cell(withFar, 100, 0)).toEqual(cell(alone, 100, 0));
      expect(cell(withNear, 100, 0)).not.toEqual(cell(alone, 100, 0));
    });

    it('takes a cell over the threshold when a second point adds weight to it', () => {
      // Neither point alone reaches 0.02 at this cell; together they do. The clamp is on the SUM,
      // which is what makes coverage — not any single location's proximity — the thing it measures.
      const f = field([POINT, { x: 20, y: 0, sc: 5 }], W, H, OPTS);
      expect(cell(f, 60, 0)[3]).toBeGreaterThan(0);
    });

    it('transfers the computed grid into the offscreen canvas it sized', () => {
      // Without this, `octx.putImageData(img, 0, 0)` can be DELETED and every other assertion in
      // this file stays green — they all read the returned `img`, which is populated before the
      // transfer. The app would render nothing at all on every surface.
      const f = field([POINT], W, H, OPTS);
      const octx = contextOf(f.canvas);
      const transfers = octx.calls.filter((c) => c.name === 'putImageData');
      expect(transfers).toHaveLength(1);
      expect(transfers[0].args[0]).toBe(f.img);
      expect(transfers[0].args.slice(1)).toEqual([0, 0]);
    });

    it('sizes the offscreen canvas in GRID CELLS, not surface pixels', () => {
      // `paint` scales it up on draw, so an offscreen canvas sized in pixels makes `drawImage`
      // read a sub-region and the field renders as a corner crop. Asserted at grid 4 on purpose:
      // at the grid 1 used elsewhere in this block, `cols * gp === cols` and the whole class of
      // mistake is invisible.
      const f = field([{ x: 50, y: 20, sc: 5 }], 100, 40, { radius: 20, grid: 4 });
      expect([f.cols, f.rows]).toEqual([26, 11]);
      expect([f.canvas.width, f.canvas.height]).toEqual([f.cols, f.rows]);
      expect(contextOf(f.canvas).calls.find((c) => c.name === 'putImageData').args[0]).toBe(f.img);
    });

    it('reaches full alpha where the weight is high, and never exceeds the cap', () => {
      const f = field([POINT], W, H, OPTS);
      // Σw = 1 at the point itself: cov = 1 − e^(−1/1.15) = 0.5808663, α = round(0.5808663 × 206).
      expect(cell(f, 100, 0)[3]).toBe(120);
      expect(cell(f, 100, 0).slice(0, 3)).toEqual(rampRgb(5));
    });
  });

  describe('focus', () => {
    // A at 40px scoring 5, B at 60px scoring 1, R = 20. On top of B the unfocused blend is
    // 2.08 (warm); with focus on A, B's weight is multiplied by 1e-4 and the blend is 5.
    const SPOTS = [
      { x: 40, y: 20, sc: 5, rid: 'A' },
      { x: 60, y: 20, sc: 1, rid: 'B' },
    ];
    const OPTS = { radius: 20, grid: 1 };

    it('blends both regions when nothing is focused', () => {
      const c = cell(field(SPOTS, 100, 40, OPTS), 60, 20);
      expect(c.slice(0, 3)).not.toEqual(rampRgb(5));
      expect(c[0]).toBeGreaterThan(c[1]); // warm: red channel above green
    });

    it('flips the same cell to the focused region\'s colour', () => {
      const c = cell(field(SPOTS, 100, 40, { ...OPTS, focus: 'A' }), 60, 20);
      // Exactly rampRgb(5) — a weaker factor than 1e-4 (say 1e-2) blends to 4.894 and lands on
      // [142, 176, 114], which this assertion rejects.
      expect(c.slice(0, 3)).toEqual(rampRgb(5));
      expect(c[3]).toBeGreaterThan(0); // still painted, not erased
    });

    it('leaves the focused region\'s own cells alone', () => {
      const focused = cell(field(SPOTS, 100, 40, { ...OPTS, focus: 'A' }), 40, 20);
      expect(focused.slice(0, 3)).toEqual(rampRgb(5));
    });

    it('is inert when the focus id matches nothing — every point fades, so nothing paints', () => {
      expect(field(SPOTS, 100, 40, { ...OPTS, focus: 'nowhere' })).not.toBeNull();
      const c = cell(field(SPOTS, 100, 40, { ...OPTS, focus: 'nowhere' }), 60, 20);
      expect(c[3]).toBe(0);
    });
  });

  describe('confidence', () => {
    const OPTS = { radius: 20, grid: 1 };
    const POINT = [{ x: 50, y: 20, sc: 5 }];

    it('is the identity at conf 1, which is also the default', () => {
      const stated = field(POINT, 100, 40, { ...OPTS, conf: 1 });
      const defaulted = field(POINT, 100, 40, OPTS);
      expect(stated.unc).toBe(0);
      expect(Array.from(stated.img.data)).toEqual(Array.from(defaulted.img.data));
    });

    it('desaturates toward grey and thins alpha at conf 0.5', () => {
      const c = cell(field(POINT, 100, 40, { ...OPTS, conf: 0.5 }), 50, 20);
      // rampRgb(5) is [138, 174, 114], grey = 142; d = 0.6 × 0.5 = 0.3 →
      // [139.2, 164.4, 122.4], and the typed array rounds.
      expect(c.slice(0, 3)).toEqual([139, 164, 122]);
      // α cap = 206 × (1 − 0.34 × 0.5) = 170.98; cov = 0.5808663 → round(99.33).
      expect(c[3]).toBe(99);
    });

    it('desaturates fully toward grey at conf 0 — 60% of the way, not all of it', () => {
      const c = cell(field(POINT, 100, 40, { ...OPTS, conf: 0 }), 50, 20);
      // d = 0.6 → [140.4, 154.8, 130.8]. Still tinted: a conf-0 field is hazy, not monochrome.
      expect(c.slice(0, 3)).toEqual([140, 155, 131]);
      expect(c[3]).toBe(Math.round(0.5808663 * 206 * 0.66));
    });

    it('clamps a confidence outside 0–1 rather than inverting the haze', () => {
      const over = field(POINT, 100, 40, { ...OPTS, conf: 4 });
      const under = field(POINT, 100, 40, { ...OPTS, conf: -2 });
      expect(over.unc).toBe(0);
      expect(under.unc).toBe(1);
    });
  });

  describe('dials', () => {
    it('sizes the offscreen grid from the grid step', () => {
      const pts = [{ x: 50, y: 25, sc: 3 }];
      expect(field(pts, 100, 50, { grid: 4 })).toMatchObject({ cols: 26, rows: 14, gp: 4 });
      expect(field(pts, 100, 50, { grid: 6 })).toMatchObject({ cols: 18, rows: 10, gp: 6 });
    });

    it('defaults the radius to 8.5% of the width, with a 14px floor for a narrow surface', () => {
      // At w = 400 the default radius is 34, so a point 60px away still clears the coverage
      // clamp (e^−(60/34)² = 0.044)…
      expect(cell(field([{ x: 0, y: 0, sc: 5 }], 400, 40, { grid: 1 }), 60, 0)[3]).toBeGreaterThan(0);
      // …but at w = 100 the radius is the 14px floor, and 60px is past the cutoff entirely.
      expect(cell(field([{ x: 0, y: 0, sc: 5 }], 100, 40, { grid: 1 }), 60, 0)[3]).toBe(0);
    });

    it('takes the alpha cap from opts', () => {
      const f = field([{ x: 50, y: 20, sc: 5 }], 100, 40, { radius: 20, grid: 1, alpha: 238 });
      expect(cell(f, 50, 20)[3]).toBe(Math.round(0.5808663 * 238));
    });
  });

  describe('bloom', () => {
    // A single point at distance 0 from the sampled cell: sw = 1 always, so cov is IDENTICAL
    // (0.5808663) whatever the score is — the only thing that changes across these fixtures is
    // the blended score `s0`, which isolates the bloom's score-gated `t` term cleanly from the
    // coverage term it is multiplied by.
    const OPTS = { radius: 20, grid: 1, bloom: 1 };
    const at = (sc) => cell(field([{ x: 50, y: 20, sc }], 100, 40, OPTS), 50, 20, 'bloomImg');

    it('is absent from the return shape when opts.bloom is not set', () => {
      const f = field([{ x: 50, y: 20, sc: 5 }], 100, 40, { radius: 20, grid: 1 });
      expect(f.bloom).toBeNull();
      expect(f.bloomImg).toBeNull();
    });

    it('gains a bloom canvas and a bloomImg ImageData, sized like the main field, when set', () => {
      const f = field([{ x: 50, y: 20, sc: 5 }], 100, 40, OPTS);
      expect(f.bloom).toBeInstanceOf(HTMLCanvasElement);
      expect([f.bloom.width, f.bloom.height]).toEqual([f.cols, f.rows]);
      expect(f.bloomImg.data).toBeInstanceOf(Uint8ClampedArray);
    });

    it('transfers the bloom ImageData into its OWN offscreen canvas, distinct from the field\'s', () => {
      const f = field([{ x: 50, y: 20, sc: 5 }], 100, 40, OPTS);
      expect(f.bloom).not.toBe(f.canvas);
      const octx = contextOf(f.bloom);
      const transfers = octx.calls.filter((c) => c.name === 'putImageData');
      expect(transfers).toHaveLength(1);
      expect(transfers[0].args[0]).toBe(f.bloomImg);
    });

    it('is fully transparent at and below the 3★ gate, at full coverage', () => {
      expect(at(1)[3]).toBe(0);
      expect(at(2.9)[3]).toBe(0);
      expect(at(3)[3]).toBe(0);
    });

    it('is zero below the gate even where coverage is thin, not just where it is full', () => {
      // sc=2 (below the gate) at two different distances from the sampled cell (50,20) — d=0
      // (cov 0.581, the point itself) and d=15 (cov 0.391, visibly thinner but still painted).
      // Both must be zero: the gate silences the EMBER regardless of how much of the main field
      // paints there.
      const near = field([{ x: 50, y: 20, sc: 2 }], 100, 40, OPTS);
      expect(cell(near, 50, 20)[3]).toBeGreaterThan(0); // the main field DID paint here
      expect(cell(near, 50, 20, 'bloomImg')[3]).toBe(0);
      const far = field([{ x: 35, y: 20, sc: 2 }], 100, 40, OPTS);
      expect(cell(far, 50, 20)[3]).toBeGreaterThan(0);
      expect(cell(far, 50, 20)[3]).toBeLessThan(cell(near, 50, 20)[3]); // genuinely thinner
      expect(cell(far, 50, 20, 'bloomImg')[3]).toBe(0);
    });

    it('climbs from the gate to full strength as the score rises to 5★', () => {
      // t = ((s−3)/2)^1.2 at each stop, × cov(=0.5808663) × bloomA(=190) × conf(=1).
      expect(at(3.5)[3]).toBe(21);
      expect(at(4)[3]).toBe(48);
      expect(at(4.5)[3]).toBe(78);
      expect(at(5)[3]).toBe(110);
      // Monotone, not just these four points — the exponent starts the climb gently.
      const series = [3, 3.2, 3.5, 3.8, 4, 4.3, 4.6, 5].map((s) => at(s)[3]);
      for (let i = 1; i < series.length; i += 1) {
        expect(series[i]).toBeGreaterThanOrEqual(series[i - 1]);
      }
      expect(series[series.length - 1]).toBeGreaterThan(series[0]);
    });

    it('paints the ember RGB [255,138,66] wherever it paints anything, regardless of score', () => {
      expect(at(4).slice(0, 3)).toEqual([255, 138, 66]);
      expect(at(5).slice(0, 3)).toEqual([255, 138, 66]);
    });

    it('shifts the gate with opts.bloomFrom instead of the fixed default of 3', () => {
      const gated = (sc) => cell(
        field([{ x: 50, y: 20, sc }], 100, 40, { ...OPTS, bloomFrom: 4 }),
        50, 20, 'bloomImg',
      );
      expect(gated(3.5)[3]).toBe(0); // below the raised gate now
      expect(gated(4)[3]).toBe(0); // AT the gate — t is still 0
      expect(gated(5)[3]).toBe(110); // t=1 at the top regardless of where the gate sits
    });

    it('scales linearly with opts.bloomA, the strength dial', () => {
      const weak = cell(field([{ x: 50, y: 20, sc: 5 }], 100, 40, { ...OPTS, bloomA: 95 }), 50, 20, 'bloomImg');
      expect(weak[3]).toBe(55); // half of 110, since bloomA halved and t=cov=1× unchanged
    });

    it('honours an explicit bloomA:0 (fully off) rather than falling back to the 190 default', () => {
      // `opts.bloomA == null ? 190 : opts.bloomA` is the contract; regressing it to the bundle's
      // `opts.bloomA || 190` would make 0 indistinguishable from "not given", and every other
      // bloomA fixture in this file uses a non-zero value so only this one can catch that.
      const off = cell(field([{ x: 50, y: 20, sc: 5 }], 100, 40, { ...OPTS, bloomA: 0 }), 50, 20, 'bloomImg');
      expect(off[3]).toBe(0);
    });

    it('honours an explicit bloomFrom:0 (gate at the bottom of the ramp) rather than falling back to 3', () => {
      // `opts.bloomFrom == null ? 3 : opts.bloomFrom` is the contract; `||` would treat an
      // explicit 0 the same as "not given" and silently restore the default 3★ gate. With the
      // gate at 0, even the coldest possible score (1★) sits above it and must glow at least a
      // little — the fixed-gate tests above only ever raise or leave the gate, never zero it.
      const gated = cell(field([{ x: 50, y: 20, sc: 1 }], 100, 40, { ...OPTS, bloomFrom: 0 }), 50, 20, 'bloomImg');
      expect(gated[3]).toBeGreaterThan(0);
    });

    it('multiplies by confidence — a day-4 guess must not glow like tonight', () => {
      const half = cell(field([{ x: 50, y: 20, sc: 5 }], 100, 40, { ...OPTS, conf: 0.5 }), 50, 20, 'bloomImg');
      const none = cell(field([{ x: 50, y: 20, sc: 5 }], 100, 40, { ...OPTS, conf: 0 }), 50, 20, 'bloomImg');
      expect(half[3]).toBe(55); // 110 × 0.5
      expect(none[3]).toBe(0); // 110 × 0
    });

    it('is never allocated for an empty point set — field() returns null first', () => {
      // This is the hatch/bloom non-interaction the plan calls out: a window with no ratings
      // returns null before `opts.bloom` is ever inspected, so a hatched, unscored window can
      // never carry a bloom layer either.
      expect(field([], 100, 40, OPTS)).toBeNull();
    });
  });
});

describe('heatField — paint', () => {
  it('composites the field through save/clip/drawImage/restore, in that order', () => {
    const { ctx } = canvasWithContext();
    const clip = vi.fn();
    const f = paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 4 }], { radius: 20, grid: 4, clip });
    expect(f).not.toBeNull();
    expect(clip).toHaveBeenCalledTimes(1);
    expect(callNames(ctx)).toEqual(['save', 'beginPath', 'clip', 'drawImage', 'restore']);
  });

  it('skips the clip entirely when none is given', () => {
    const { ctx } = canvasWithContext();
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 4 }], { radius: 20, grid: 4 });
    expect(callNames(ctx)).toEqual(['save', 'drawImage', 'restore']);
  });

  it('adds up to 2.6px of blur as confidence falls', () => {
    const { ctx } = canvasWithContext();
    const pts = [{ x: 50, y: 20, sc: 4 }];
    paint(ctx, 100, 40, pts, { radius: 20, grid: 4 });
    expect(ctx.filter).toBe('blur(3px)');
    paint(ctx, 100, 40, pts, { radius: 20, grid: 4, conf: 0.5 });
    expect(ctx.filter).toBe('blur(4.3px)');
    paint(ctx, 100, 40, pts, { radius: 20, grid: 4, conf: 0, blur: 4 });
    expect(ctx.filter).toBe('blur(6.6px)');
  });

  it('defaults opacity to 0.92 and takes an override', () => {
    const { ctx } = canvasWithContext();
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 4 }], { radius: 20, grid: 4 });
    expect(ctx.globalAlpha).toBe(0.92);
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 4 }], { radius: 20, grid: 4, opacity: 0.5 });
    expect(ctx.globalAlpha).toBe(0.5);
  });

  it('draws the offscreen grid up to full surface size', () => {
    const { ctx } = canvasWithContext();
    const f = paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 4 }], { radius: 20, grid: 4 });
    const drawn = ctx.calls.find((c) => c.name === 'drawImage');
    expect(drawn.args.slice(1)).toEqual([0, 0, f.cols, f.rows, 0, 0, f.cols * 4, f.rows * 4]);
  });

  it('paints nothing at all when the field is null', () => {
    const { ctx } = canvasWithContext();
    expect(paint(ctx, 100, 40, [], { radius: 20 })).toBeNull();
    expect(ctx.calls).toEqual([]);
  });
});

describe('heatField — paint (bloom pass)', () => {
  it('draws the field, switches to \'lighter\' for the bloom pass, then restores — no extra clip/save', () => {
    const { ctx } = canvasWithContext();
    const f = paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 5 }], { radius: 20, grid: 4, bloom: 1 });
    expect(f.bloom).not.toBeNull();
    expect(callNames(ctx)).toEqual(['save', 'drawImage', 'drawImage', 'restore']);
    const draws = ctx.calls.filter((c) => c.name === 'drawImage');
    expect(draws[0].globalCompositeOperation).toBe('source-over'); // the field itself
    expect(draws[0].args[0]).toBe(f.canvas);
    expect(draws[1].globalCompositeOperation).toBe('lighter'); // the emissive pass, second
    expect(draws[1].args[0]).toBe(f.bloom);
  });

  it('draws only one drawImage call when opts.bloom is absent, even though field() still runs', () => {
    const { ctx } = canvasWithContext();
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 5 }], { radius: 20, grid: 4 });
    expect(ctx.calls.filter((c) => c.name === 'drawImage')).toHaveLength(1);
  });

  it('sets the bloom blur to blur × bloomBlur (default 2.4) plus 3px per unit of uncertainty', () => {
    const { ctx } = canvasWithContext();
    const pts = [{ x: 50, y: 20, sc: 5 }];
    paint(ctx, 100, 40, pts, { radius: 20, grid: 4, bloom: 1 });
    const [, bloomDraw] = ctx.calls.filter((c) => c.name === 'drawImage');
    // 3 × 2.4, conf defaults to 1 (unc 0) — computed rather than hand-typed, since 3 × 2.4 is not
    // exactly representable in binary floating point (7.199999999999999).
    expect(bloomDraw.filter).toBe(`blur(${3 * 2.4}px)`);

    const { ctx: ctx2 } = canvasWithContext();
    paint(ctx2, 100, 40, pts, {
      radius: 20, grid: 4, bloom: 1, bloomBlur: 1, conf: 0.5,
    });
    const [, bloomDraw2] = ctx2.calls.filter((c) => c.name === 'drawImage');
    expect(bloomDraw2.filter).toBe('blur(4.5px)'); // 3 × 1 + 0.5 × 3

    const { ctx: ctx3 } = canvasWithContext();
    paint(ctx3, 100, 40, pts, { radius: 20, grid: 4, blur: 2, bloom: 1 });
    const [fieldDraw3, bloomDraw3] = ctx3.calls.filter((c) => c.name === 'drawImage');
    expect(fieldDraw3.filter).toBe('blur(2px)'); // the field's own blur is untouched by bloomBlur
    expect(bloomDraw3.filter).toBe('blur(4.8px)'); // 2 × 2.4
  });

  it('honours an explicit bloomBlur:0 rather than falling back to the 2.4 default', () => {
    // `opts.bloomBlur == null ? 2.4 : opts.bloomBlur` is the contract; regressing it to the
    // bundle's `opts.bloomBlur || 2.4` would make 0 indistinguishable from "not given" and this
    // is the only test that can tell the two apart, since every other bloomBlur fixture uses a
    // non-zero value.
    const { ctx } = canvasWithContext();
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 5 }], {
      radius: 20, grid: 4, bloom: 1, bloomBlur: 0,
    });
    const [, bloomDraw] = ctx.calls.filter((c) => c.name === 'drawImage');
    expect(bloomDraw.filter).toBe('blur(0px)'); // 3 × 0 + 0 (conf defaults to 1, unc 0)
  });

  it('leaves the field draw itself at globalCompositeOperation source-over', () => {
    // The bloom pass switches the CONTEXT to 'lighter' after the field is already drawn — a
    // regression that set it before the field draw would light the field additively too.
    const { ctx } = canvasWithContext();
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 5 }], { radius: 20, grid: 4, bloom: 1 });
    const [fieldDraw] = ctx.calls.filter((c) => c.name === 'drawImage');
    expect(fieldDraw.globalCompositeOperation).toBe('source-over');
  });

  it('composes inside the same save/restore as a plain clip callback — the bloom pass is not a second layer', () => {
    const { ctx } = canvasWithContext();
    const clip = vi.fn();
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 5 }], { radius: 20, grid: 4, bloom: 1, clip });
    expect(callNames(ctx)).toEqual(['save', 'beginPath', 'clip', 'drawImage', 'drawImage', 'restore']);
  });
});

describe('heatField — paint (soft mask: clipPath + clipSoft)', () => {
  const CLIP_PATH = { sentinel: 'opaque-clip-path-stub' };

  function targetContext(w, h) {
    const cv = document.createElement('canvas');
    cv.width = w;
    cv.height = h;
    return cv.getContext('2d');
  }

  it('renders field+bloom to a temp surface, composes the mask on its OWN surface, and applies a SINGLE destination-in draw to the target', () => {
    const ctx = targetContext(100, 40);
    const f = paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 5 }], {
      radius: 20, grid: 4, bloom: 1, clipPath: CLIP_PATH, clipSoft: 4, clipGrow: 3,
    });
    expect(f).not.toBeNull();

    // The TARGET context only ever receives the finished temp surface: one save, one drawImage,
    // one restore — never a clip call of its own, and never the mask canvas directly.
    expect(callNames(ctx)).toEqual(['save', 'drawImage', 'restore']);
    const finalDraw = ctx.calls[1];
    expect(finalDraw.globalCompositeOperation).toBe('source-over');
    const tempCanvas = finalDraw.args[0];
    expect(tempCanvas).toBeInstanceOf(HTMLCanvasElement);

    // The temp surface: field blit (source-over), bloom blit (lighter), then the mask applied via
    // ONE destination-in draw — never two composite draws with destination-in already active,
    // which would intersect rather than union.
    const tc = contextOf(tempCanvas);
    const tcDraws = tc.calls.filter((c) => c.name === 'drawImage');
    expect(tcDraws).toHaveLength(3);
    expect(tcDraws[0].globalCompositeOperation).toBe('source-over'); // field
    expect(tcDraws[1].globalCompositeOperation).toBe('lighter'); // bloom
    expect(tcDraws[2].globalCompositeOperation).toBe('destination-in'); // the mask, once

    const maskCanvas = tcDraws[2].args[0];
    expect(maskCanvas).toBeInstanceOf(HTMLCanvasElement);
    expect(maskCanvas).not.toBe(tempCanvas); // composed on its OWN surface, never in place

    // The mask surface itself draws in plain source-over — it never sees destination-in — and
    // hands the caller's clipPath opaquely to fill/stroke, never constructing or introspecting it
    // (jsdom 30 has no Path2D at all, so this sentinel stands in for one).
    const mc = contextOf(maskCanvas);
    expect(mc.calls.map((c) => c.name)).toEqual(['setTransform', 'translate', 'stroke', 'fill']);
    expect(mc.calls.every((c) => c.globalCompositeOperation === 'source-over')).toBe(true);
    const strokeCall = mc.calls.find((c) => c.name === 'stroke');
    expect(strokeCall.args[0]).toBe(CLIP_PATH);
    // clipGrow:3 dilates by stroking a band TWICE as wide — halving this constant would erase
    // 7 of 51 coastal locations in P4 with the suite still green, per the plan's own measurement.
    expect(strokeCall.lineWidth).toBe(6);
    expect(mc.lineJoin).toBe('round');
    expect(mc.lineCap).toBe('round');
    const fillCall = mc.calls.find((c) => c.name === 'fill');
    expect(fillCall.args[0]).toBe(CLIP_PATH);
    // clipSoft:4 — the mask blurs by the SAME px the field's own blur uses, so the coastline
    // feathers at the same rate the field does; asserted here (not just in the blur-only test
    // below) because this is the fixture that also exercises the dilation stroke.
    expect(fillCall.filter).toBe('blur(4px)');
  });

  it('sizes off the DEVICE-PIXEL backing store, not the CSS w/h — the DPR>1 case fit() produces', () => {
    // Every other soft-mask test in this file runs at the degenerate scale sx=1 (backing store
    // === CSS size, via `targetContext`'s cv.width = w). At that scale, deleting the temp/mask
    // surfaces' device-px sizing, either setTransform(sx,...) call, the identity reset before the
    // destination-in draw, or the final downscaling drawImage all survive with the suite green —
    // exactly the class of regression that would misalign P4's land mask on any real DPR>1
    // display. This fixture pins the DPR-2 case fit() actually produces: a backing store twice
    // the CSS w/h paint() is called with.
    const cv = document.createElement('canvas');
    cv.width = 200; // 2× the CSS width below
    cv.height = 80; // 2× the CSS height below
    const ctx = cv.getContext('2d');
    const w = 100;
    const h = 40;
    paint(ctx, w, h, [{ x: 50, y: 20, sc: 5 }], {
      radius: 20, grid: 4, clipPath: CLIP_PATH, clipSoft: 4,
    });

    const finalDraw = ctx.calls.find((c) => c.name === 'drawImage');
    const tempCanvas = finalDraw.args[0];
    // The temp surface is allocated at the BACKING-STORE size, not the CSS size — sized 100×40
    // here would silently downsample a DPR-2 field before it was ever masked.
    expect(tempCanvas.width).toBe(200);
    expect(tempCanvas.height).toBe(80);

    const tc = contextOf(tempCanvas);
    const tcTransforms = tc.calls.filter((c) => c.name === 'setTransform');
    // The FIRST setTransform on the temp surface scales it up to device pixels (sx = 200/100 = 2)
    // before the field is blitted into it.
    expect(tcTransforms[0].args).toEqual([2, 0, 0, 2, 0, 0]);
    // The LAST setTransform on the temp surface, immediately before the destination-in mask
    // draw, resets to the identity — the mask canvas is already at device-px size, so drawing it
    // through a still-2× transform would double-scale it.
    expect(tcTransforms[tcTransforms.length - 1].args).toEqual([1, 0, 0, 1, 0, 0]);

    const maskDraw = tc.calls.find(
      (c) => c.name === 'drawImage' && c.globalCompositeOperation === 'destination-in',
    );
    const maskCanvas = maskDraw.args[0];
    // The mask surface is allocated at the SAME backing-store size as the temp surface — a
    // mismatched size would misalign the mask against the field it is meant to clip.
    expect(maskCanvas.width).toBe(200);
    expect(maskCanvas.height).toBe(80);
    const mc = contextOf(maskCanvas);
    // The mask surface's own setTransform matches the temp surface's first — both are scaling
    // the SAME clipPath into the SAME device-px space.
    expect(mc.calls.find((c) => c.name === 'setTransform').args).toEqual([2, 0, 0, 2, 0, 0]);

    // The final draw onto the TARGET context downscales the finished (device-px) temp surface
    // back to CSS px — dropping this is invisible at sx=1 but would double the field's size on
    // any real DPR>1 display.
    expect(finalDraw.args).toEqual([tempCanvas, 0, 0, w, h]);
  });

  it('skips the dilation stroke when clipGrow is absent — fill-only mask, still a single destination-in', () => {
    const ctx = targetContext(100, 40);
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 5 }], {
      radius: 20, grid: 4, clipPath: CLIP_PATH, clipSoft: 2,
    });
    const tempCanvas = ctx.calls.find((c) => c.name === 'drawImage').args[0];
    const tc = contextOf(tempCanvas);
    const maskCanvas = tc.calls.find((c) => c.name === 'drawImage'
      && c.globalCompositeOperation === 'destination-in').args[0];
    const mc = contextOf(maskCanvas);
    expect(mc.calls.map((c) => c.name)).toEqual(['setTransform', 'translate', 'fill']);
  });

  it('blurs the mask by clipSoft px, so the boundary feathers at the same rate as the field', () => {
    const ctx = targetContext(100, 40);
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 5 }], {
      radius: 20, grid: 4, clipPath: CLIP_PATH, clipSoft: 7,
    });
    const tempCanvas = ctx.calls.find((c) => c.name === 'drawImage').args[0];
    const tc = contextOf(tempCanvas);
    const maskCanvas = tc.calls.find((c) => c.name === 'drawImage'
      && c.globalCompositeOperation === 'destination-in').args[0];
    const mc = contextOf(maskCanvas);
    expect(mc.calls.find((c) => c.name === 'fill').filter).toBe('blur(7px)');
  });

  it('slides the mask by clipDx/clipDy before drawing it, defaulting to no offset', () => {
    const ctx = targetContext(100, 40);
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 5 }], {
      radius: 20, grid: 4, clipPath: CLIP_PATH, clipSoft: 2, clipDx: 6, clipDy: -9,
    });
    const tempCanvas = ctx.calls.find((c) => c.name === 'drawImage').args[0];
    const tc = contextOf(tempCanvas);
    const maskCanvas = tc.calls.find((c) => c.name === 'drawImage'
      && c.globalCompositeOperation === 'destination-in').args[0];
    const mc = contextOf(maskCanvas);
    expect(mc.calls.find((c) => c.name === 'translate').args).toEqual([6, -9]);
  });

  it('falls through to the hard-clip route when clipSoft is absent — clipPath alone takes the other branch', () => {
    const ctx = targetContext(100, 40);
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 5 }], { radius: 20, grid: 4, clipPath: CLIP_PATH });
    // The hard-clip route never creates a temp surface — its only drawImage is the field itself,
    // straight onto the target context, bracketed by the translate/clip/untranslate sequence
    // (dx/dy default to 0, but the calls still happen — see the hard-clip describe block below).
    expect(callNames(ctx)).toEqual(['save', 'translate', 'clip', 'translate', 'drawImage', 'restore']);
  });
});

describe('heatField — paint (hard clip: clipPath alone)', () => {
  it('clips via ctx.clip(clipPath) inside a translate/untranslate pair, treating clipPath opaquely', () => {
    const { ctx } = canvasWithContext();
    const CLIP_PATH = { sentinel: true };
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 4 }], {
      radius: 20, grid: 4, clipPath: CLIP_PATH, clipDx: 5, clipDy: -3,
    });
    expect(callNames(ctx)).toEqual(['save', 'translate', 'clip', 'translate', 'drawImage', 'restore']);
    const [t1, clipCall, t2] = ctx.calls.filter((c) => c.name === 'translate' || c.name === 'clip');
    expect(t1.args).toEqual([5, -3]);
    expect(clipCall.args[0]).toBe(CLIP_PATH); // handed opaquely — never constructed or introspected
    expect(t2.args).toEqual([-5, 3]); // untranslated back before the field draws
  });

  it('defaults clipDx/clipDy to 0 when absent', () => {
    const { ctx } = canvasWithContext();
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 4 }], { radius: 20, grid: 4, clipPath: {} });
    const translates = ctx.calls.filter((c) => c.name === 'translate');
    // The untranslate is `-dx, -dy`, which for a 0 default is negative zero in IEEE 754 — still
    // zero-valued, so flatten the sign before comparing rather than pin an incidental -0/+0 split.
    expect(translates.map((c) => c.args.map((v) => v + 0))).toEqual([[0, 0], [0, 0]]);
  });

  it('takes clipPath over a plain clip callback when both are somehow given', () => {
    const { ctx } = canvasWithContext();
    const clip = vi.fn();
    paint(ctx, 100, 40, [{ x: 50, y: 20, sc: 4 }], {
      radius: 20, grid: 4, clipPath: {}, clip,
    });
    expect(clip).not.toHaveBeenCalled();
    expect(callNames(ctx)).toContain('translate');
  });
});

describe('heatField — fit', () => {
  it('scales the backing store by the device pixel ratio and the element by CSS px', () => {
    window.devicePixelRatio = 1.5;
    const cv = document.createElement('canvas');
    const ctx = fit(cv, 200, 120);
    expect([cv.width, cv.height]).toEqual([300, 180]);
    expect([cv.style.width, cv.style.height]).toEqual(['200px', '120px']);
    expect(callNames(ctx)).toEqual(['setTransform']);
    expect(ctx.calls[0].args).toEqual([1.5, 0, 0, 1.5, 0, 0]);
  });

  it('caps the ratio at 2 — beyond that the pixels cost time a blurred field cannot show', () => {
    window.devicePixelRatio = 3;
    const cv = document.createElement('canvas');
    fit(cv, 200, 120);
    expect([cv.width, cv.height]).toEqual([400, 240]);
  });

  it('passes a ratio of exactly 2 through — the cap is the boundary, not below it', () => {
    window.devicePixelRatio = 2;
    const cv = document.createElement('canvas');
    fit(cv, 200, 120);
    expect([cv.width, cv.height]).toEqual([400, 240]);
  });

  it('falls back to 1 when the browser reports no ratio', () => {
    window.devicePixelRatio = 0;
    const cv = document.createElement('canvas');
    fit(cv, 200, 120);
    expect([cv.width, cv.height]).toEqual([200, 120]);
  });
});

describe('heatField — geometry helpers', () => {
  const SPOTS = [
    { lat: 54, lng: -2 },
    { lat: 55, lng: -1 },
  ];

  describe('bbox', () => {
    it('pads latitude by padDeg and longitude by 1.7× that', () => {
      // 1.7 is the cos-latitude correction in reverse: a degree of longitude covers roughly 0.6
      // of a degree of latitude on screen here, so equal degrees would pad unevenly.
      // toBeCloseTo, not toEqual: -2 − 0.16 × 1.7 lands one ulp off -2.272 in binary floating
      // point, and a test that pinned the ulp would be pinning the arithmetic's spelling.
      const corners = bbox(SPOTS).coordinates;
      [
        [-2.272, 53.84],
        [-0.728, 53.84],
        [-0.728, 55.16],
        [-2.272, 55.16],
      ].forEach(([lng, lat], i) => {
        expect(corners[i][0]).toBeCloseTo(lng, 9);
        expect(corners[i][1]).toBeCloseTo(lat, 9);
      });
    });

    it('takes an explicit padding', () => {
      const c = bbox(SPOTS, 0.5).coordinates;
      expect(c[0][0]).toBeCloseTo(-2.85, 9); // -2 − 0.5 × 1.7
      expect(c[0][1]).toBeCloseTo(53.5, 9);
      expect(c[2][0]).toBeCloseTo(-0.15, 9); // -1 + 0.5 × 1.7
      expect(c[2][1]).toBeCloseTo(55.5, 9);
    });

    it('emits corners as a MultiPoint, never a ring', () => {
      // A polygon's winding can be read as the whole globe, which fits the projection to the
      // world instead of the area asked for — silently, and only visible as a blank thumbnail.
      const box = bbox(SPOTS);
      expect(box.type).toBe('MultiPoint');
      expect(box.coordinates).toHaveLength(4);
    });

    it('falls back to the default frame for an empty or missing set', () => {
      expect(bbox([])).toBe(BBOX);
      expect(bbox(null)).toBe(BBOX);
      expect(bbox(undefined)).toBe(BBOX);
    });

    it('handles a single spot as a zero-span box plus padding', () => {
      const c = bbox([{ lat: 54, lng: -2 }], 0.1).coordinates;
      [
        [-2.17, 53.9],
        [-1.83, 53.9],
        [-1.83, 54.1],
        [-2.17, 54.1],
      ].forEach(([lng, lat], i) => {
        expect(c[i][0]).toBeCloseTo(lng, 9);
        expect(c[i][1]).toBeCloseTo(lat, 9);
      });
    });
  });

  describe('latLngBounds', () => {
    it('returns [[south, west], [north, east]] for Leaflet, not the MultiPoint order', () => {
      const [[south, west], [north, east]] = latLngBounds(SPOTS);
      expect(south).toBeCloseTo(53.84, 9);
      expect(west).toBeCloseTo(-2.272, 9);
      expect(north).toBeCloseTo(55.16, 9);
      expect(east).toBeCloseTo(-0.728, 9);
    });

    it('keeps south below north and west below east', () => {
      const [[south, west], [north, east]] = latLngBounds(SPOTS, 0.4);
      expect(south).toBeLessThan(north);
      expect(west).toBeLessThan(east);
    });
  });

  describe('aspect', () => {
    it('corrects the longitude span by cos(mean latitude)', () => {
      // BBOX spans 2.08° of latitude and 3.57° of longitude at mean latitude 54.84°.
      // cos(54.84°) = 0.5760, so the frame is very nearly square: 2.08 / (3.57 × 0.5760).
      expect(aspect()).toBeCloseTo(1.0115, 3);
      // Without the correction it would read 0.5826 — a thumbnail nearly twice as wide as tall.
      expect(aspect()).not.toBeCloseTo(2.08 / 3.57, 2);
    });

    it('reads a taller frame further north, where a degree of longitude is narrower', () => {
      const south = aspect(bbox([{ lat: 50, lng: -3 }, { lat: 52, lng: -1 }], 0));
      const north = aspect(bbox([{ lat: 58, lng: -3 }, { lat: 60, lng: -1 }], 0));
      expect(north).toBeGreaterThan(south);
    });

    it('returns 1 for a frame with no longitude span rather than dividing by zero', () => {
      expect(aspect({ coordinates: [[-2, 54], [-2, 54], [-2, 56], [-2, 56]] })).toBe(1);
    });

    it('defaults to the default frame', () => {
      expect(aspect(null)).toBe(aspect(BBOX));
    });
  });

  describe('centroid', () => {
    const REGIONAL = [
      { rid: 'north', lat: 55, lng: -2 },
      { rid: 'north', lat: 57, lng: -4 },
      { rid: 'south', lat: 51, lng: 0 },
    ];
    const project = (s) => [s.lng * 10, s.lat * 10];

    it('averages only the named region\'s projected points', () => {
      expect(centroid(REGIONAL, 'north', project)).toEqual([-30, 560]);
    });

    it('returns null for a region with no spots, so a caller cannot label empty space', () => {
      expect(centroid(REGIONAL, 'west', project)).toBeNull();
      expect(centroid([], 'north', project)).toBeNull();
    });
  });

  describe('kmPerPx', () => {
    it('measures px-per-km off a linear stub projection', () => {
      // One degree of latitude is 10px under this stub, so kmPerPx = 10 / 111.2.
      const linear = ([lng, lat]) => [lng * 10, lat * 10];
      expect(kmPerPx(linear, [-1.573, 54.855])).toBeCloseTo(10 / 111.2, 9);
    });

    it('reads the scale AT refPoint — a linear stub cannot tell this apart from ignoring it', () => {
      // A latitude-dependent projection kills the mutant that drops the refPoint parameter and
      // hard-codes a fixed reference point instead (the prototype's own HOMEPT literal).
      const latDependent = ([lng, lat]) => [lng * 10, lat * lat];
      const north = kmPerPx(latDependent, [-1.573, 10]);
      const south = kmPerPx(latDependent, [-1.573, 50]);
      expect(north).not.toBeCloseTo(south, 3);
      // |b[1]-a[1]| = (lat+1)^2 - lat^2 = 2*lat + 1
      expect(north).toBeCloseTo(21 / 111.2, 9);
      expect(south).toBeCloseTo(101 / 111.2, 9);
    });

    it('takes the absolute value — a real projection puts north above south (y falls as lat rises)', () => {
      // Both stubs above have y RISE with latitude, so a dropped Math.abs would still pass them —
      // a north-up screen projection (d3's geoMercator included) does the opposite: going further
      // north moves a point UP the canvas, i.e. to a SMALLER y. Without Math.abs this returns a
      // negative scale on exactly the projection shape the function exists to be used with.
      const northUp = ([lng, lat]) => [lng * 10, -lat * 10];
      expect(kmPerPx(northUp, [-1.573, 54.855])).toBeCloseTo(10 / 111.2, 9);
    });
  });

  describe('radiusFor', () => {
    const mapAt = (zoom) => ({ getCenter: () => ({ lat: 54.84 }), getZoom: () => zoom });

    it('converts metres to pixels at the map centre when the result is in range', () => {
      // mpp = 156543.03392 × cos(54.84°) / 2^10 = 88.03 → 8500 / 88.03 = 96.553px.
      expect(radiusFor(mapAt(10), 8500)).toBeCloseTo(96.553, 2);
    });

    it('clamps at the low end when zoomed out, where the radius would vanish', () => {
      expect(radiusFor(mapAt(6), 8500)).toBe(34);
    });

    it('clamps at the high end when zoomed in, where it would swamp the frame', () => {
      expect(radiusFor(mapAt(14), 8500)).toBe(240);
    });

    it('takes explicit bounds', () => {
      expect(radiusFor(mapAt(6), 8500, 10, 50)).toBe(10);
      expect(radiusFor(mapAt(14), 8500, 10, 50)).toBe(50);
      expect(radiusFor(mapAt(10), 8500, 10, 50)).toBe(50);
    });
  });

  describe('proj', () => {
    it('fits the frame edge to edge on its constrained axis, with a 2px bleed', () => {
      // The bleed is what keeps the coastline stroke from being clipped at the canvas edge.
      // fitExtent preserves aspect, so only the constrained axis touches it — here the BBOX is
      // very nearly square (aspect 1.01) inside a 204 × 124 extent, so height binds and the
      // frame is letterboxed horizontally.
      const projection = proj(200, 120, BBOX);
      const xs = BBOX.coordinates.map((c) => projection(c)[0]);
      const ys = BBOX.coordinates.map((c) => projection(c)[1]);
      expect(Math.min(...ys)).toBeCloseTo(-2, 6);
      expect(Math.max(...ys)).toBeCloseTo(122, 6);
      expect(Math.min(...xs) + Math.max(...xs)).toBeCloseTo(200, 6); // centred across the width
      expect(Math.min(...xs)).toBeGreaterThan(-2);
    });

    it('defaults to the default frame', () => {
      expect(proj(200, 120)([-2, 54.8])).toEqual(proj(200, 120, BBOX)([-2, 54.8]));
    });
  });

  describe('rgb', () => {
    // Defined in scoreRamp.js (a colour helper does not belong in the geometry module) and
    // re-exported here so the ported surface still carries everything the prototype's two hosts
    // alias off `HeatField`. Its behaviour is pinned in scoreRamp.test.js; this pins the re-export,
    // because dropping it breaks the documented kernel surface without breaking anything else.
    it('is re-exported from the kernel, identical to scoreRamp\'s', () => {
      expect(rgb).toBe(scoreRampRgb);
      expect(rgb([138, 174, 114], 0.4)).toBe('rgba(138,174,114,0.4)');
    });
  });

  describe('clamp', () => {
    it('constrains to both bounds and passes a value already inside through', () => {
      expect(clamp(5, 1, 3)).toBe(3);
      expect(clamp(-5, 1, 3)).toBe(1);
      expect(clamp(2, 1, 3)).toBe(2);
    });
  });
});

describe('heatField — load', () => {
  it('resolves the vendored UK geometry as a FeatureCollection', async () => {
    const uk = await load();
    expect(uk.type).toBe('FeatureCollection');
    expect(uk.features).toHaveLength(1);
    expect(uk.features[0].id).toBe('826');
    expect(uk.features[0].geometry.type).toBe('MultiPolygon');
  });

  it('caches, so six thumbnails do not each decode the topology', async () => {
    expect(await load()).toBe(await load());
    expect(land()).toBe(await load());
  });

  it('hands CONCURRENT callers the same object, not one decode each', async () => {
    // The six thumbnails mount together, so the real case is six calls in flight at once — which
    // the sequential test above cannot see, because by its second call the cache is already warm.
    // Without an in-flight latch each caller decodes separately and gets its OWN FeatureCollection,
    // and five of the six then hold an object that is not the one drawGeo reads.
    vi.resetModules();
    const fresh = await import('../utils/heatField.js');
    const results = await Promise.all(Array.from({ length: 6 }, () => fresh.load()));
    results.forEach((r) => expect(r).toBe(results[0]));
    expect(fresh.land()).toBe(results[0]);
  });
});

describe('heatField — drawGeo (host A: static canvas, real coastline)', () => {
  const SPOTS = [
    { lat: 54.5, lng: -2.5, r: [4, 2], rid: 'north' },
    { lat: 55.2, lng: -1.6, r: [5, 1], rid: 'north' },
  ];

  beforeEach(async () => {
    await load();
  });

  it('paints sea, then land plate, then the clipped field, then the coastline stroke', () => {
    const { cv, ctx } = canvasWithContext();
    expect(drawGeo(cv, 200, 140, SPOTS, 0)).toBeInstanceOf(Function);
    const order = callNames(ctx).filter((n) => !PATH_METHODS.includes(n));
    expect(order).toEqual([
      'setTransform',
      'fillRect', // sea
      'beginPath',
      'fill', // land plate
      'save',
      'beginPath',
      'clip', // field clipped to the coastline
      'drawImage',
      'restore',
      'beginPath',
      'stroke', // coastline
    ]);
  });

  it('drives the coastline through the path methods d3 needs', () => {
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0);
    expect(callNames(ctx).filter((n) => n === 'moveTo').length).toBeGreaterThan(0);
    expect(callNames(ctx).filter((n) => n === 'lineTo').length).toBeGreaterThan(0);
  });

  /** The pixels drawGeo actually composited, read back off the offscreen canvas it drew from. */
  function paintedBy(ctx) {
    const drawn = ctx.calls.find((c) => c.name === 'drawImage');
    if (!drawn) return null;
    const offscreen = contextOf(drawn.args[0]);
    return offscreen.calls.find((c) => c.name === 'putImageData').args[0];
  }

  it('reads each spot\'s score for the window index it is given', () => {
    // Window 0 scores 4 and 5; window 1 scores 2 and 1 — two genuinely different fields. Asserting
    // only that each call drew once leaves `s.r[win]` → `s.r[0]` green, which is the whole strip
    // showing window 0's forecast under six different window labels.
    const a = canvasWithContext();
    const b = canvasWithContext();
    drawGeo(a.cv, 200, 140, SPOTS, 0, { fit: bbox(SPOTS) });
    drawGeo(b.cv, 200, 140, SPOTS, 1, { fit: bbox(SPOTS) });
    const zero = paintedBy(a.ctx);
    const one = paintedBy(b.ctx);
    expect(Array.from(one.data)).not.toEqual(Array.from(zero.data));
    // And the direction is right: window 0's blend sits high on the ramp, window 1's low.
    const brightest = (img) => {
      let best = null;
      for (let k = 0; k < img.data.length; k += 4) {
        if (img.data[k + 3] > 0 && (best === null || img.data[k + 3] > img.data[best + 3])) best = k;
      }
      return [img.data[best], img.data[best + 1], img.data[best + 2]];
    };
    expect(brightest(zero)[1]).toBeGreaterThan(brightest(one)[1]); // greener
    expect(brightest(one)[0]).toBeGreaterThan(brightest(one)[1]); // window 1 is warm/red
  });

  it('forwards its dials to the kernel instead of painting a default field', () => {
    // `paint(ctx, w, h, pts, { ...opts, … })` — drop the spread and every drawGeo test below still
    // passes, while radius, grid and conf silently stop working on every thumbnail.
    const coarse = canvasWithContext();
    const fine = canvasWithContext();
    drawGeo(coarse.cv, 200, 140, SPOTS, 0, { fit: bbox(SPOTS), grid: 10 });
    drawGeo(fine.cv, 200, 140, SPOTS, 0, { fit: bbox(SPOTS), grid: 4 });
    expect(paintedBy(coarse.ctx).width).toBeLessThan(paintedBy(fine.ctx).width);

    // conf rides through to the blur, which is set on the destination context.
    const hazy = canvasWithContext();
    drawGeo(hazy.cv, 200, 140, SPOTS, 0, { fit: bbox(SPOTS), conf: 0.5 });
    expect(hazy.ctx.filter).toBe('blur(4.3px)');
  });

  it('honours opts.fit rather than framing every thumbnail to the default box', () => {
    // Without this, `proj(w, h, opts.fit)` → `proj(w, h)` is green, and every Plan thumbnail frames
    // to northern England whatever area was selected — visible only as a blank-looking map.
    const fit = bbox([{ lat: 57.5, lng: -5.2 }, { lat: 58.4, lng: -4.1 }]);
    const { cv } = canvasWithContext();
    const projection = drawGeo(cv, 200, 140, SPOTS, 0, { fit });
    expect(projection([-4.65, 57.95])).toEqual(proj(200, 140, fit)([-4.65, 57.95]));
    expect(projection([-4.65, 57.95])).not.toEqual(proj(200, 140)([-4.65, 57.95]));
  });

  it('lifts the alpha cap from 206 to 238 when a region is focused', () => {
    // The focused field is drawn more opaquely so a drilled-into region reads as the subject.
    // Asserting only "drawImage was called" leaves `alpha: opts.focus ? 238 : 206` → `206` green.
    const plain = canvasWithContext();
    const focused = canvasWithContext();
    drawGeo(plain.cv, 200, 140, SPOTS, 0, { fit: bbox(SPOTS) });
    drawGeo(focused.cv, 200, 140, SPOTS, 0, { fit: bbox(SPOTS), focus: 'north' });
    const peak = (img) => Math.max(...Array.from(img.data).filter((_, i) => i % 4 === 3));
    const plainPeak = peak(paintedBy(plain.ctx));
    const focusedPeak = peak(paintedBy(focused.ctx));
    // Same geometry, same scores, same region — only the cap differs, in the ratio 238 : 206. The
    // peaks are already-rounded bytes, so the derived expectation can be a whole unit out; ±1 is
    // rounding headroom, not slack. Dropping the lift makes the two peaks EQUAL, ~15% apart.
    expect(Math.abs(focusedPeak - Math.round((plainPeak / 206) * 238))).toBeLessThanOrEqual(1);
    expect(focusedPeak).toBeGreaterThan(plainPeak);
  });

  it('paints the sea, then the land plate, in those colours and not the reverse', () => {
    // Swapping the two defaults renders a light sea behind dark land — an inverted map that a
    // call-ORDER assertion cannot see, because the order is unchanged.
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, { fit: bbox(SPOTS) });
    expect(ctx.calls.find((c) => c.name === 'fillRect').fillStyle).toBe('#13100e');
    expect(ctx.calls.find((c) => c.name === 'fill').fillStyle).toBe('#241d18');
    const stroke = ctx.calls.find((c) => c.name === 'stroke');
    expect(stroke.strokeStyle).toBe('rgba(242,231,211,.30)');
    expect(stroke.lineWidth).toBe(0.7);
  });

  it('takes overrides for every painted colour and the coastline width', () => {
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, {
      fit: bbox(SPOTS),
      sea: '#000102',
      plate: '#030405',
      stroke: '#060708',
      line: 2.5,
    });
    expect(ctx.calls.find((c) => c.name === 'fillRect').fillStyle).toBe('#000102');
    expect(ctx.calls.find((c) => c.name === 'fill').fillStyle).toBe('#030405');
    const stroke = ctx.calls.find((c) => c.name === 'stroke');
    expect(stroke.strokeStyle).toBe('#060708');
    expect(stroke.lineWidth).toBe(2.5);
  });

  describe('the unscored hatch', () => {
    /** The stroke calls that are the hatch, i.e. everything but the coastline's own. */
    const hatchStrokes = (ctx) => ctx.calls
      .filter((c) => c.name === 'stroke' && c.strokeStyle !== 'rgba(242,231,211,.30)');

    it('draws nothing extra when the option is absent — every existing host is untouched', () => {
      const { cv, ctx } = canvasWithContext();
      drawGeo(cv, 200, 140, SPOTS, 0, { fit: bbox(SPOTS) });
      expect(hatchStrokes(ctx)).toHaveLength(0);
    });

    it('rules a clipped 45° hatch over the plate when asked', () => {
      const { cv, ctx } = canvasWithContext();
      drawGeo(cv, 200, 140, [], 0, { fit: bbox(SPOTS), hatch: true });
      const strokes = hatchStrokes(ctx);
      expect(strokes).toHaveLength(1);
      expect(strokes[0].strokeStyle).toBe('rgba(242,231,211,.18)');
      expect(strokes[0].lineWidth).toBe(0.6);
    });

    it('sweeps from a full height left of the frame, so both corners are covered', () => {
      // A 45° line starting at (x, h) ends at (x + h, 0). Beginning the sweep at x = 0 leaves the
      // whole bottom-left triangle bare — which on a coastline reads as a deliberate shape rather
      // than as a texture, and it is exactly what a `for (let x = 0; ...)` typo produces.
      const { cv, ctx } = canvasWithContext();
      drawGeo(cv, 200, 140, [], 0, { fit: bbox(SPOTS), hatch: true });
      // 45° pairs: every moveTo/lineTo the coastline drew came before the clip; the hatch's own
      // are the ones whose two points differ by exactly (h, -h).
      const pairs = [];
      ctx.calls.forEach((c, i) => {
        if (c.name !== 'moveTo') return;
        const next = ctx.calls[i + 1];
        if (next?.name !== 'lineTo') return;
        if (next.args[0] - c.args[0] === 140 && c.args[1] - next.args[1] === 140) pairs.push(c);
      });
      expect(pairs.length).toBe(Math.ceil((200 + 140) / 7));
      expect(pairs[0].args).toEqual([-140, 140]);
      expect(pairs[pairs.length - 1].args[0]).toBeLessThan(200);
    });

    it('clips to the coastline, inside its own save/restore', () => {
      // Unclipped it would hatch the sea as well, which says "no data" about water nobody was
      // ever going to be scored on; unrestored, the clip would swallow the coastline stroke drawn
      // straight after it.
      const { cv, ctx } = canvasWithContext();
      drawGeo(cv, 200, 140, [], 0, { fit: bbox(SPOTS), hatch: true });
      const names = ctx.calls.map((c) => c.name);
      const stroke = names.lastIndexOf('stroke');
      const restore = names.lastIndexOf('restore');
      expect(names.lastIndexOf('clip')).toBeLessThan(restore);
      expect(restore).toBeLessThan(stroke); // the coastline is drawn after the hatch is restored
    });

    it('takes overrides for its own dials without touching the coastline\'s', () => {
      const { cv, ctx } = canvasWithContext();
      drawGeo(cv, 200, 140, [], 0, {
        fit: bbox(SPOTS), hatch: true, hatchInk: '#abcdef', hatchLine: 2, hatchGap: 40,
      });
      const strokes = hatchStrokes(ctx);
      expect(strokes[0].strokeStyle).toBe('#abcdef');
      expect(strokes[0].lineWidth).toBe(2);
      expect(ctx.calls.find((c) => c.name === 'stroke' && c.strokeStyle === 'rgba(242,231,211,.30)'))
        .toBeTruthy();
    });

    it('marks an EMPTY point set and a painted one alike — it is the caller\'s claim', () => {
      // Deliberately not inferred from "the field painted nothing": `field()` also returns null
      // when every point is culled outside the frame, which is a framing answer rather than an
      // absence of data. The host that knows which windows carry ratings is the one that says so.
      const { cv, ctx } = canvasWithContext();
      drawGeo(cv, 200, 140, SPOTS, 0, { fit: bbox(SPOTS), hatch: true });
      expect(hatchStrokes(ctx)).toHaveLength(1);
      expect(ctx.calls.some((c) => c.name === 'drawImage')).toBe(true);
    });
  });

  it('declines a canvas 20px or smaller — a zero measure throws on cv.width', () => {
    const { cv } = canvasWithContext();
    expect(drawGeo(cv, 20, 140, SPOTS, 0)).toBeNull();
    expect(drawGeo(cv, 200, 20, SPOTS, 0)).toBeNull();
    expect(drawGeo(cv, 0, 0, SPOTS, 0)).toBeNull();
    expect(drawGeo(cv, 21, 21, SPOTS, 0)).toBeInstanceOf(Function);
  });

  it('declines a missing canvas', () => {
    expect(drawGeo(null, 200, 140, SPOTS, 0)).toBeNull();
  });

  it('draws the geography and no field when the catalogue is empty', () => {
    const { cv, ctx } = canvasWithContext();
    expect(drawGeo(cv, 200, 140, [], 0)).toBeInstanceOf(Function);
    expect(callNames(ctx)).toContain('stroke');
    expect(callNames(ctx)).not.toContain('drawImage');
  });

  it('treats a null score as zero, so unscored spots must be filtered BEFORE the kernel', () => {
    // Documenting the seam rather than hiding it: `sws += wt * null` is `+= 0`, so a spot with no
    // score for this window does not vanish — it drags the blend to the bottom of the ramp and
    // paints the region red. The join (`utils/heatSpots.js`, P1) drops null-score spots per
    // window for exactly this reason, and that is the coverage clamp's honesty, not a bug here.
    const { cv, ctx } = canvasWithContext();
    const unscored = SPOTS.map((s) => ({ ...s, r: [null, null] }));
    expect(drawGeo(cv, 200, 140, unscored, 0, { fit: bbox(SPOTS) })).toBeInstanceOf(Function);
    expect(callNames(ctx)).toContain('drawImage');

    // And the same at kernel level, where the resulting colour is visible.
    const f = field([{ x: 50, y: 20, sc: null }], 100, 40, { radius: 20, grid: 1 });
    expect(cell(f, 50, 20).slice(0, 3)).toEqual(rampRgb(1));
  });
});

describe('heatField — drawGeo before the geometry has loaded', () => {
  it('declines rather than throwing, so a first paint that beats the import is harmless', async () => {
    vi.resetModules();
    const fresh = await import('../utils/heatField.js');
    expect(fresh.land()).toBeNull();
    const { cv } = canvasWithContext();
    expect(fresh.drawGeo(cv, 200, 140, [{ lat: 54, lng: -2, r: [4], rid: 'a' }], 0)).toBeNull();
  });
});

describe('heatField — drawTiles (host B: over a Leaflet basemap)', () => {
  const SPOTS = [
    { lat: 54.5, lng: -2.5, r: [4], rid: 'north' },
    { lat: 55.2, lng: -1.6, r: [5], rid: 'north' },
  ];
  const fakeMap = (w, h) => ({
    getSize: () => ({ x: w, y: h }),
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
  });

  it('clears the canvas before painting — the tiles carry the geography, so nothing is redrawn', () => {
    const { cv, ctx } = canvasWithContext();
    const f = drawTiles(cv, fakeMap(300, 200), SPOTS, 0, { radius: 40, grid: 6 });
    expect(f).not.toBeNull();
    expect(callNames(ctx)).toEqual(['setTransform', 'clearRect', 'save', 'drawImage', 'restore']);
  });

  it('draws no coastline of its own', () => {
    const { cv, ctx } = canvasWithContext();
    drawTiles(cv, fakeMap(300, 200), SPOTS, 0, { radius: 40, grid: 6 });
    expect(callNames(ctx)).not.toContain('stroke');
    expect(callNames(ctx)).not.toContain('fillRect');
  });

  it('projects through the map, not through d3', () => {
    const map = fakeMap(300, 200);
    const spy = vi.spyOn(map, 'latLngToContainerPoint');
    const { cv } = canvasWithContext();
    drawTiles(cv, map, SPOTS, 0, { radius: 40, grid: 6 });
    expect(spy).toHaveBeenCalledTimes(2);
    expect(spy).toHaveBeenCalledWith([54.5, -2.5]);
  });

  it('forwards its dials to the kernel instead of painting a default field', () => {
    // `return paint(ctx, w, h, pts, opts)` — drop `opts` and this host still paints, so every
    // radiusFor/grid/conf dial P4 sets on a pan would silently do nothing.
    const { cv } = canvasWithContext();
    const coarse = drawTiles(cv, fakeMap(300, 200), SPOTS, 0, { radius: 40, grid: 10 });
    const fine = drawTiles(canvasWithContext().cv, fakeMap(300, 200), SPOTS, 0, { radius: 40, grid: 4 });
    expect(coarse.gp).toBe(10);
    expect(fine.gp).toBe(4);
    expect(coarse.cols).toBeLessThan(fine.cols);

    const hazy = drawTiles(canvasWithContext().cv, fakeMap(300, 200), SPOTS, 0, {
      radius: 40, grid: 6, conf: 0.5,
    });
    expect(hazy.unc).toBe(0.5);
  });

  it('reads each spot\'s score for the window index it is given', () => {
    const scored = [
      { lat: 54.5, lng: -2.5, r: [5, 1], rid: 'north' },
      { lat: 55.2, lng: -1.6, r: [5, 1], rid: 'north' },
    ];
    const best = drawTiles(canvasWithContext().cv, fakeMap(300, 200), scored, 0, { radius: 40, grid: 6 });
    const worst = drawTiles(canvasWithContext().cv, fakeMap(300, 200), scored, 1, { radius: 40, grid: 6 });
    const at = (f, i, j) => {
      const k = (j * f.cols + i) * 4;
      return [f.img.data[k], f.img.data[k + 1], f.img.data[k + 2]];
    };
    // Spot 1 projects to (50, 150) under fakeMap, which at grid 6 is cell (8, 25).
    expect(at(best, 8, 25)).toEqual(rampRgb(5));
    expect(at(worst, 8, 25)).toEqual(rampRgb(1));
  });

  it('declines a map 20px or smaller in either dimension, and draws at 21', () => {
    const { cv } = canvasWithContext();
    expect(drawTiles(cv, fakeMap(20, 200), SPOTS, 0)).toBeNull();
    expect(drawTiles(cv, fakeMap(300, 20), SPOTS, 0)).toBeNull();
    // The "one above" side of the same threshold, so a guard widened to >= 20 is caught.
    const near = [{ lat: 55.9, lng: -2.9, r: [4], rid: 'north' }]; // projects to (10, 10)
    expect(drawTiles(cv, fakeMap(21, 21), near, 0, { radius: 14, grid: 3 })).not.toBeNull();
  });

  it('returns null when every spot is out of the map\'s reach', () => {
    const far = [{ lat: 20, lng: 40, r: [4], rid: 'north' }];
    const { cv } = canvasWithContext();
    expect(drawTiles(cv, fakeMap(300, 200), far, 0, { radius: 40, grid: 6 })).toBeNull();
  });

  describe('opts.score', () => {
    // Same single spot the "reads each spot's score" test above already pins at cell (8, 25) —
    // its r[0] is deliberately a LOW score so a callback that scores it differently is visible.
    const SPOT = [{ lat: 54.5, lng: -2.5, r: [1], rid: 'north', nightRating: 5 }];
    const at = (f, i, j) => {
      const k = (j * f.cols + i) * 4;
      return [f.img.data[k], f.img.data[k + 1], f.img.data[k + 2]];
    };

    it('scores through the callback instead of s.r[win] when one is given', () => {
      const f = drawTiles(canvasWithContext().cv, fakeMap(300, 200), SPOT, 0, {
        radius: 40, grid: 6, score: (s) => s.nightRating,
      });
      // r[0] is 1 (cold); the callback reads nightRating, 5 (hot) — proving the callback ran and
      // not just that SOME score was used.
      expect(at(f, 8, 25)).toEqual(rampRgb(5));
    });

    it('lets a host score a location not present in r[] at all — the astro/aurora night-row case', () => {
      const nightOnly = [{ lat: 54.5, lng: -2.5, r: [], rid: 'north', nightRating: 4 }];
      const f = drawTiles(canvasWithContext().cv, fakeMap(300, 200), nightOnly, 0, {
        radius: 40, grid: 6, score: (s) => s.nightRating,
      });
      // r[0] would be undefined here — a default s.r[win] read gives NaN, which would render as
      // the ramp's bottom clamp, not this location's actual 4★ night rating.
      expect(at(f, 8, 25)).toEqual(rampRgb(4));
    });

    it('falls back to s.r[win] — the existing behaviour — when no score callback is given', () => {
      const f = drawTiles(canvasWithContext().cv, fakeMap(300, 200), SPOT, 0, {
        radius: 40, grid: 6,
      });
      expect(at(f, 8, 25)).toEqual(rampRgb(1));
    });
  });
});
