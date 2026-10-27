import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  contourLines, coverageGrid, drawTiles, paintOutline, paintVeil, planOutsidePicks,
} from '../utils/heatField.js';

/**
 * The outside-your-sub-regions layers (sub-regions-plan S5a-1): the veil under the field, the dashed
 * outline after it, and the marching-squares routine the outline is traced with.
 *
 * The context is a local recording stub, as in heatFieldUnknown.test.js (jsdom has no 2d context).
 */

const VEIL_INK = 'rgba(156,158,160,.18)';
const OUTLINE_INK = 'rgba(242,231,211,.85)';

function makeContext() {
  const calls = [];
  const record = (name) => (...args) => {
    calls.push({
      name, args, strokeStyle: ctx.strokeStyle, lineWidth: ctx.lineWidth, dash: ctx.dash, alpha: ctx.globalAlpha,
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
    dash: null,
    createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4), width: w, height: h }),
  };
  ctx.setLineDash = (d) => { ctx.dash = d; calls.push({ name: 'setLineDash', args: [d] }); };
  ['putImageData', 'clearRect', 'fillRect', 'save', 'restore', 'drawImage', 'beginPath', 'fill',
    'stroke', 'clip', 'setTransform', 'translate', 'moveTo', 'lineTo', 'closePath', 'arc'].forEach((name) => {
    ctx[name] = record(name);
  });
  return ctx;
}

let contexts;
let allContexts;
let originalGetContext;

beforeEach(() => {
  contexts = new WeakMap();
  allContexts = [];
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function getContextStub() {
    if (!contexts.has(this)) {
      const ctx = makeContext();
      ctx.canvas = this;
      contexts.set(this, ctx);
      allContexts.push(ctx);
    }
    return contexts.get(this);
  };
});

afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
});

const point = (x, y, rid = 'A') => ({ x, y, rid });

describe('contourLines (marching squares)', () => {
  it('traces one closed loop around a single hot cell, at the half-way crossings', () => {
    // 3x3 grid, only the centre above the level: a diamond through the four edge mid-points.
    const values = new Float32Array([0, 0, 0, 0, 1, 0, 0, 0, 0]);

    const lines = contourLines(values, 3, 3, 0.5);

    expect(lines).toHaveLength(1);
    const loop = lines[0];
    expect(loop[0]).toEqual(loop[loop.length - 1]);
    expect(loop).toHaveLength(5);
    const corners = loop.slice(0, 4).map(([x, y]) => `${x},${y}`).sort();
    expect(corners).toEqual(['0.5,1', '1,0.5', '1,1.5', '1.5,1']);
  });

  it('interpolates linearly: a lower level puts the crossing nearer the low value, so the loop is wider', () => {
    const values = new Float32Array([0, 0, 0, 0, 1, 0, 0, 0, 0]);

    const lines = contourLines(values, 3, 3, 0.25);

    const xs = lines[0].map(([x]) => x);
    expect(Math.min(...xs)).toBeCloseTo(0.25, 5);
    expect(Math.max(...xs)).toBeCloseTo(1.75, 5);
  });

  it('returns an open polyline where the contour leaves the frame, and no loop for it', () => {
    // The left column is above the level, the rest below: one line down the middle, open at both ends.
    const values = new Float32Array([1, 0, 1, 0, 1, 0]);

    const lines = contourLines(values, 2, 3, 0.5);

    expect(lines).toHaveLength(1);
    expect(lines[0]).toEqual([[0.5, 0], [0.5, 1], [0.5, 2]]);
  });

  it('draws nothing for a field wholly above or wholly below the level', () => {
    expect(contourLines(new Float32Array(9).fill(1), 3, 3, 0.5)).toEqual([]);
    expect(contourLines(new Float32Array(9).fill(0), 3, 3, 0.5)).toEqual([]);
  });

  it('keeps two separate hot cells as two separate loops', () => {
    const values = new Float32Array(25);
    values[1 * 5 + 1] = 1;
    values[3 * 5 + 3] = 1;

    const lines = contourLines(values, 5, 5, 0.5);

    expect(lines).toHaveLength(2);
    lines.forEach((loop) => expect(loop[0]).toEqual(loop[loop.length - 1]));
  });

  it('resolves a saddle by the centre mean: connected above, each low corner is cut off by its own line', () => {
    // One 2x2 cell, high on the TR-BL diagonal; the centre mean (0.9) is above 0.5, so the high corners
    // are joined and the LOW corners TL and BR are cut off: one line round TL, one round BR.
    const lines = contourLines(new Float32Array([0, 1.8, 1.8, 0]), 2, 2, 0.5);

    const flat = lines.map((line) => line.map(([x, y]) => [Number(x.toFixed(3)), Number(y.toFixed(3))]));
    expect(flat).toHaveLength(2);
    expect(flat).toContainEqual(expect.arrayContaining([[0.278, 0], [0, 0.278]]));
    expect(flat).toContainEqual(expect.arrayContaining([[1, 0.722], [0.722, 1]]));
  });

  it('resolves a saddle by the centre mean: below it the high corners are kept apart', () => {
    // The same diagonal, centre mean 0.9 under the 1.2 level: each HIGH corner (TR, BL) is cut off by its
    // own line, which is the other pairing of the four edge crossings.
    const lines = contourLines(new Float32Array([0.4, 1.4, 1.4, 0.4]), 2, 2, 1.2);

    const flat = lines.map((line) => line.map(([x, y]) => [Number(x.toFixed(3)), Number(y.toFixed(3))]));
    expect(flat).toHaveLength(2);
    expect(flat).toContainEqual(expect.arrayContaining([[0.8, 0], [1, 0.2]]));
    expect(flat).toContainEqual(expect.arrayContaining([[0, 0.8], [0.2, 1]]));
  });
});

describe('coverageGrid', () => {
  it('is the field\'s own clamp: one isolated place peaks near 0.58 and falls away with distance', () => {
    const grid = coverageGrid([point(40, 40)], 80, 80, 20, 4);

    const at = (x, y) => grid.values[(y / 4) * grid.cols + x / 4];
    expect(at(40, 40)).toBeCloseTo(1 - Math.exp(-1 / 1.15), 4);
    expect(at(40, 40)).toBeGreaterThan(at(60, 40));
    expect(at(0, 0)).toBe(0);
    expect(grid.empty).toBe(false);
  });

  it('reports an empty set as empty, all zero', () => {
    const grid = coverageGrid([], 80, 80, 20, 4);

    expect(grid.empty).toBe(true);
    expect(Math.max(...grid.values)).toBe(0);
  });

  it('fades every other region by 1e-4 when a region is focused', () => {
    const open = coverageGrid([point(40, 40, 'A')], 80, 80, 20, 4);
    const focused = coverageGrid([point(40, 40, 'A')], 80, 80, 20, 4, 'B');

    const centre = (10 * open.cols) + 10;
    expect(open.values[centre]).toBeGreaterThan(0.5);
    expect(focused.values[centre]).toBeLessThan(0.0001);
  });
});

describe('planOutsidePicks', () => {
  it('samples a large radius on a coarser grid than the field\'s own, never coarser than 12px or finer than asked', () => {
    const gpOf = (radius, grid) => planOutsidePicks([point(60, 60)], [point(100, 60)], 200, 120, { radius, grid }).veil.gp;

    expect(gpOf(24, 4)).toBe(6);
    expect(gpOf(90, 6)).toBe(12);
    expect(gpOf(400, 6)).toBe(12);
    expect(gpOf(8, 4)).toBe(4);
    expect(gpOf(8, 6)).toBe(6);
  });

  const W = 200;
  const H = 120;

  it('washes the ground an outside place reaches and leaves the ground an allowed place covers clear', () => {
    const plan = planOutsidePicks([point(30, 60)], [point(168, 60)], W, H, { radius: 24, grid: 4 });

    const { gp, cols } = plan.veil;
    const alphaAt = (x, y) => plan.veil.alpha[(y / gp) * cols + x / gp];
    expect(alphaAt(30, 60)).toBeGreaterThan(0.9);
    expect(alphaAt(168, 60)).toBe(0);
  });

  it('yields to an allowed place standing on the same ground: the veil is gone where it covers', () => {
    const plan = planOutsidePicks([point(100, 60)], [point(100, 60)], W, H, { radius: 24, grid: 4 });

    const { gp, cols } = plan.veil;
    const centre = Math.round(60 / gp) * cols + Math.round(100 / gp);
    expect(plan.veil.alpha[centre]).toBeLessThan(0.2);
  });

  it('has no veil when no outside place can reach the frame', () => {
    const plan = planOutsidePicks([], [point(100, 60)], W, H, { radius: 24, grid: 4 });

    expect(plan.veil).toBeNull();
    expect(plan.lines.length).toBeGreaterThan(0);
  });

  it('draws a veil but no outline for a reader with no picks (nothing allowed)', () => {
    const plan = planOutsidePicks([point(60, 60), point(140, 60)], [], W, H, { radius: 24, grid: 4 });

    expect(plan.veil).not.toBeNull();
    expect(plan.lines).toEqual([]);
    // All the land, not only the ground near the two places: a corner far from both is washed too.
    expect(Math.min(...plan.veil.alpha)).toBe(1);
  });

  it('traces the outline at the 0.42 contour of allowed coverage, in CSS px', () => {
    const plan = planOutsidePicks([], [point(100, 60)], W, H, { radius: 24, grid: 4 });

    expect(plan.lines).toHaveLength(1);
    const [loop] = plan.lines;
    expect(loop[0]).toEqual(loop[loop.length - 1]);
    // Every vertex sits where coverage at radius x 0.9 crosses 0.42: all about the same distance out.
    const R2 = (24 * 0.9) ** 2;
    const expectedDistance = Math.sqrt(-R2 * Math.log(-1.15 * Math.log(1 - 0.42)));
    loop.forEach(([x, y]) => {
      // Linear interpolation across a grid cell: within a pixel or so of the analytic radius.
      expect(Math.abs(Math.hypot(x - 100, y - 60) - expectedDistance)).toBeLessThan(1.5);
    });
  });
});

describe('paintVeil and paintOutline', () => {
  function canvasWithContext() {
    const cv = document.createElement('canvas');
    cv.width = 200;
    cv.height = 120;
    return { cv, ctx: cv.getContext('2d') };
  }

  it('paints the veil in the neutral grey ink, through a mask, and reports it drew', () => {
    const { ctx } = canvasWithContext();
    const plan = planOutsidePicks([point(60, 60)], [], 200, 120, { radius: 24, grid: 4 });

    const drew = paintVeil(ctx, 200, 120, plan, { blur: 4 });

    expect(drew).toBe(true);
    const tempCtx = allContexts.find((c) => c.fillStyle === VEIL_INK);
    expect(tempCtx).toBeTruthy();
    expect(tempCtx.calls.some((c) => c.name === 'fillRect')).toBe(true);
    expect(tempCtx.calls.some((c) => c.name === 'drawImage')).toBe(true);
  });

  it('paints no veil when there is nothing outside', () => {
    const { ctx } = canvasWithContext();
    const plan = planOutsidePicks([], [point(60, 60)], 200, 120, { radius: 24, grid: 4 });

    expect(paintVeil(ctx, 200, 120, plan, {})).toBe(false);
    expect(allContexts.some((c) => c.fillStyle === VEIL_INK)).toBe(false);
  });

  it('strokes the outline dashed 5/3 in bone at the given width, clipped, with the given opacity', () => {
    const { ctx } = canvasWithContext();
    const plan = planOutsidePicks([], [point(100, 60)], 200, 120, { radius: 24, grid: 4 });

    const drew = paintOutline(ctx, plan, {
      clip: () => {}, outlineWidth: 1.6, opacity: 0.5,
    });

    expect(drew).toBe(true);
    const stroke = ctx.calls.find((c) => c.name === 'stroke');
    expect(stroke.strokeStyle).toBe(OUTLINE_INK);
    expect(stroke.lineWidth).toBe(1.6);
    expect(stroke.dash).toEqual([5, 3]);
    expect(stroke.alpha).toBe(0.5);
    expect(ctx.calls.findIndex((c) => c.name === 'clip')).toBeLessThan(
      ctx.calls.findIndex((c) => c.name === 'stroke'),
    );
  });

  it('strokes nothing when no allowed place gives it a contour', () => {
    const { ctx } = canvasWithContext();
    const plan = planOutsidePicks([point(60, 60)], [], 200, 120, { radius: 24, grid: 4 });

    expect(paintOutline(ctx, plan, {})).toBe(false);
    expect(ctx.calls.some((c) => c.name === 'stroke')).toBe(false);
  });
});

describe('drawTiles with the outside layers', () => {
  const map = {
    getSize: () => ({ x: 200, y: 120 }),
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 4) * 20, y: (56 - lat) * 20 }),
  };
  const spot = (lat, lng, score) => ({
    lat, lng, rid: 'A', r: [score],
  });
  const rated = [spot(55, -2, 4)];
  const base = { radius: 24, grid: 4, blur: 3 };

  function run(opts) {
    const cv = document.createElement('canvas');
    cv.width = 200;
    cv.height = 120;
    drawTiles(cv, map, rated, 0, { ...base, ...opts });
    return cv.getContext('2d');
  }

  const callNames = (ctx) => ctx.calls.map((c) => c.name);

  it('draws no veil, no outline and no dash for a host that never passes `outside`', () => {
    const ctx = run({});

    expect(allContexts.some((c) => c.fillStyle === VEIL_INK)).toBe(false);
    expect(callNames(ctx)).not.toContain('setLineDash');
    expect(callNames(ctx)).not.toContain('stroke');
  });

  it('is byte-identical without `outside`: the same calls with or without an `allowed` it would ignore', () => {
    const plain = callNames(run({}));
    const withAllowed = callNames(run({ allowed: [spot(55, -2, 4)] }));

    expect(withAllowed).toEqual(plain);
  });

  it('draws the veil under the field and the outline after it', () => {
    const ctx = run({
      outside: [spot(54.5, -3.5)],
      allowed: [spot(55, -2)],
      clip: () => {},
    });

    const veilCtx = allContexts.find((c) => c.fillStyle === VEIL_INK);
    expect(veilCtx).toBeTruthy();
    // The target context sees the veil's drawImage before the field's, and the stroke last.
    const names = callNames(ctx);
    const strokeAt = names.lastIndexOf('stroke');
    const lastDrawImage = names.lastIndexOf('drawImage');
    expect(strokeAt).toBeGreaterThan(lastDrawImage);
    expect(names).toContain('setLineDash');
  });

  it('treats an empty `outside` as shaped: an outline for the allowed places, no veil', () => {
    const ctx = run({ outside: [], allowed: [spot(55, -2)], clip: () => {} });

    expect(allContexts.some((c) => c.fillStyle === VEIL_INK)).toBe(false);
    expect(callNames(ctx)).toContain('stroke');
  });

  it('draws the veil but no outline for a reader with no picks', () => {
    const ctx = run({ outside: [spot(55, -2)], allowed: [], clip: () => {} });

    expect(allContexts.some((c) => c.fillStyle === VEIL_INK)).toBe(true);
    expect(callNames(ctx)).not.toContain('stroke');
  });
});
