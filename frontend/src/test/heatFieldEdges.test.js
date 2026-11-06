import {
  describe, it, expect, beforeAll, beforeEach, afterEach, vi,
} from 'vitest';
import {
  contourLines, coverageGrid, drawGeo, drawTiles, field, fit, load, paint, paintOutline,
  paintStandDown, paintUnknown, paintVeil, planOutsidePicks, unknownMask,
} from '../utils/heatField.js';

/**
 * Edge behaviour of the heat kernel that the larger heatField suites leave unpinned: the exact
 * cut-off, the order of canvas calls, the temp-surface geometry of the hatches, the outside-picks
 * plan and marching squares. Each case asserts an observable (a pixel, a mask cell, a recorded
 * canvas call), never that a line ran.
 *
 * The 2d context is a local recording stub, as in heatField.test.js (jsdom has none, and a global
 * polyfill is deliberately not added to setup.js).
 */

let allContexts;
let puts;
let contexts;
let originalGetContext;
let originalDpr;

function makeContext() {
  const calls = [];
  const snap = (name, args) => ({
    name,
    args,
    fillStyle: ctx.fillStyle,
    strokeStyle: ctx.strokeStyle,
    lineWidth: ctx.lineWidth,
    alpha: ctx.globalAlpha,
    composite: ctx.globalCompositeOperation,
    filter: ctx.filter,
    dash: ctx.dash,
  });
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
    putImageData: (img) => {
      puts.push({ data: img.data, width: img.width, height: img.height });
      calls.push(snap('putImageData', [img]));
    },
    setLineDash: (d) => {
      ctx.dash = d;
      calls.push(snap('setLineDash', [d]));
    },
  };
  ['clearRect', 'fillRect', 'save', 'restore', 'drawImage', 'beginPath', 'fill', 'stroke', 'clip',
    'setTransform', 'translate', 'moveTo', 'lineTo', 'closePath', 'arc'].forEach((name) => {
    ctx[name] = (...args) => calls.push(snap(name, args));
  });
  return ctx;
}

beforeAll(() => load());

beforeEach(() => {
  contexts = new WeakMap();
  allContexts = [];
  puts = [];
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
  originalDpr = window.devicePixelRatio;
  window.devicePixelRatio = 1;
});

afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  window.devicePixelRatio = originalDpr;
});

/** A canvas of the given backing size with its stub context. */
function host(width, height) {
  const cv = document.createElement('canvas');
  cv.width = width;
  cv.height = height;
  return { cv, ctx: cv.getContext('2d') };
}

/** The contexts of canvases created after {@code ctx}'s own: temp surfaces, in creation order. */
function internals(ctx) {
  return allContexts.slice(allContexts.indexOf(ctx) + 1);
}

const names = (ctx) => ctx.calls.map((c) => c.name);
const only = (ctx, name) => ctx.calls.filter((c) => c.name === name);

/** One mask cell's alpha byte out of a recorded putImageData. */
function maskAlpha(put, i, j) {
  return put.data[(j * put.width + i) * 4 + 3];
}

/** A Leaflet-shaped map whose container point is (lng, lat), so a test can say where a place is. */
function fakeMap(x, y) {
  return {
    getSize: () => ({ x, y }),
    latLngToContainerPoint: ([lat, lng]) => ({ x: lng, y: lat }),
  };
}

describe('the kernel at its limits', () => {
  it('paints with the widest radius a map ever asks for (240px) on a small frame', () => {
    const f = field([{ x: 100, y: 100, sc: 4, rid: 'A' }], 300, 200, { radius: 240 });
    expect(f).not.toBeNull();
    expect(f.n).toBe(1);
  });

  it('copes with a radius far wider than the frame', () => {
    const f = field([{ x: 100, y: 100, sc: 4, rid: 'A' }], 300, 200, { radius: 1e5 });
    expect(f.n).toBe(1);
    expect(unknownMask([{ x: 100, y: 100, rid: 'A' }], [], 300, 200, { radius: 1e5 })).not.toBeNull();
  });

  it('builds the unknown mask with the widest radius too', () => {
    const m = unknownMask([{ x: 100, y: 100, rid: 'A' }], [], 300, 200, { radius: 240 });
    expect(m).not.toBeNull();
    expect(m.alpha).toHaveLength(m.cols * m.rows);
  });

  // R is the double for which R * R * 6 is exactly 25, so a place 3px by 4px away sits exactly on
  // the cut-off (d2 === 6 R2). The sum cuts off only beyond it (d2 > 6 R2), so that place counts.
  const R_EDGE = 2.041241452319315;

  it('counts a place exactly on the cut-off distance (field)', () => {
    const f = field(
      [{ x: 3, y: 4, sc: 0, rid: 'A' }, { x: 0, y: 0, sc: 255, rid: 'A' }],
      10, 10,
      { radius: R_EDGE, grid: 1, ramp: (s) => [s, 0, 0] },
    );
    const k = (4 * f.cols + 3) * 4;
    // the far place (score 255) has weight exp(-6), pulling the cell's blend from 0 up to about 0.63
    expect(f.img.data[k]).toBe(1);
  });

  it('counts a place exactly on the cut-off distance (unknown mask)', () => {
    const m = unknownMask([{ x: 0, y: 0, rid: 'A' }], [], 10, 10, { radius: R_EDGE, grid: 1 });
    expect(m.alpha[4 * m.cols + 3]).toBeGreaterThan(0);
  });

  it('counts a place exactly on the cut-off distance (coverage grid)', () => {
    const g = coverageGrid([{ x: 0, y: 0, rid: 'A' }], 10, 10, R_EDGE, 1);
    expect(g.values[4 * g.cols + 3]).toBeGreaterThan(0);
  });
});

describe('the bloom layer', () => {
  it('writes the ember colour into all three channels of a covered cell', () => {
    const f = field([{ x: 20, y: 20, sc: 5, rid: 'A' }], 40, 40, { bloom: true });
    const k = (5 * f.cols + 5) * 4;
    expect(Array.from(f.bloomImg.data.slice(k, k + 3))).toEqual([255, 138, 66]);
    expect(f.bloomImg.data[k + 3]).toBeGreaterThan(0);
  });

  it('writes an injected bloom colour into all three channels', () => {
    const f = field([{ x: 20, y: 20, sc: 5, rid: 'A' }], 40, 40, { bloom: true, bloomRGB: [10, 20, 30] });
    const k = (5 * f.cols + 5) * 4;
    expect(Array.from(f.bloomImg.data.slice(k, k + 3))).toEqual([10, 20, 30]);
  });
});

describe('the bloom layer, last cell', () => {
  it('colours the final grid cell too (its blue channel is its own, not a neighbour\'s)', () => {
    const f = field([{ x: 40, y: 40, sc: 5, rid: 'A' }], 40, 40, { bloom: true });
    const k = ((f.rows - 1) * f.cols + (f.cols - 1)) * 4;
    expect(f.img.data[k + 3]).toBeGreaterThan(0);
    expect(Array.from(f.bloomImg.data.slice(k, k + 3))).toEqual([255, 138, 66]);
  });
});

describe('paint draws the field smoothed and at its grid scale', () => {
  it('smooths the upscale and stretches both layers by the grid pitch', () => {
    const { ctx } = host(40, 40);
    const f = paint(ctx, 40, 40, [{ x: 20, y: 20, sc: 5, rid: 'A' }], { bloom: true });
    expect(ctx.imageSmoothingEnabled).toBe(true);
    const draws = only(ctx, 'drawImage');
    expect(draws).toHaveLength(2);
    expect(draws[0].args).toEqual([f.canvas, 0, 0, f.cols, f.rows, 0, 0, f.cols * 4, f.rows * 4]);
    expect(draws[1].args).toEqual([f.bloom, 0, 0, f.cols, f.rows, 0, 0, f.cols * 4, f.rows * 4]);
  });
});

describe('the soft mask dilation', () => {
  const path = { id: 'land' };
  const softPaint = (extra) => {
    const { ctx } = host(40, 40);
    paint(ctx, 40, 40, [{ x: 20, y: 20, sc: 5, rid: 'A' }], { clipPath: path, clipSoft: 2, ...extra });
    // field's own canvas, the temp surface, then the mask surface
    return internals(ctx)[2];
  };

  it('strokes the path to grow the mask only when clipGrow is positive', () => {
    const grown = softPaint({ clipGrow: 3 });
    expect(only(grown, 'stroke')).toHaveLength(1);
    expect(only(grown, 'stroke')[0].lineWidth).toBe(6);
  });

  it('does not stroke for a clipGrow of zero or none', () => {
    expect(only(softPaint({ clipGrow: 0 }), 'stroke')).toHaveLength(0);
    expect(only(softPaint({}), 'stroke')).toHaveLength(0);
  });
});

describe('fit only reallocates a canvas whose size really changed', () => {
  function tracked() {
    const cv = document.createElement('canvas');
    const writes = [];
    let w = 300;
    let h = 150;
    Object.defineProperty(cv, 'width', { get: () => w, set: (v) => { writes.push(['width', v]); w = v; }, configurable: true });
    Object.defineProperty(cv, 'height', { get: () => h, set: (v) => { writes.push(['height', v]); h = v; }, configurable: true });
    return { cv, writes };
  }

  it('writes nothing when the size is unchanged', () => {
    const { cv, writes } = tracked();
    fit(cv, 300, 150);
    expect(writes).toEqual([]);
  });

  it('resizes when only the width differs', () => {
    const { cv, writes } = tracked();
    fit(cv, 100, 150);
    expect(cv.width).toBe(100);
    expect(writes).toContainEqual(['width', 100]);
  });

  it('resizes when only the height differs', () => {
    const { cv, writes } = tracked();
    fit(cv, 300, 60);
    expect(cv.height).toBe(60);
    expect(writes).toContainEqual(['height', 60]);
  });
});

describe('load', () => {
  const MODULE = '../assets/uk-land-50m.json';
  const tinyTopology = {
    type: 'Topology',
    arcs: [],
    objects: { countries: { type: 'GeometryCollection', geometries: [] } },
  };

  afterEach(() => {
    vi.doUnmock(MODULE);
    vi.resetModules();
  });

  it('rules stand-down lines and dashes the outline from a freshly loaded module', async () => {
    vi.resetModules();
    const fresh = await import('../utils/heatField.js');
    const { ctx } = host(100, 100);
    fresh.paintStandDown(ctx, 100, 100, [{ x: 50, y: 50, rid: 'A' }], [], {});
    const tc = internals(ctx)[1];
    expect(only(tc, 'moveTo')).toHaveLength(Math.ceil(200 / 8));
    expect(only(tc, 'stroke')[0]).toMatchObject({ lineWidth: 1.2, strokeStyle: 'rgba(184,197,220,.5)' });
    const { ctx: line } = host(100, 100);
    fresh.paintOutline(line, { lines: [[[1, 2], [3, 4]]] });
    expect(only(line, 'stroke')[0].dash).toEqual([5, 3]);
  });

  it('reads the default export of the topology module and shares one in-flight promise', async () => {
    vi.resetModules();
    vi.doMock(MODULE, () => ({ default: tinyTopology }));
    const fresh = await import('../utils/heatField.js');
    const first = fresh.load();
    const second = fresh.load();
    expect(second).toBe(first);
    const land = await first;
    expect(land.type).toBe('FeatureCollection');
    expect(await fresh.load()).toBe(land);
  });

  it('rejects when the chunk fails, then lets a later call succeed', async () => {
    vi.resetModules();
    vi.doMock(MODULE, () => { throw new Error('chunk failed'); });
    const fresh = await import('../utils/heatField.js');
    await expect(fresh.load()).rejects.toThrow();
    expect(fresh.land()).toBeNull();

    vi.doMock(MODULE, () => ({ default: tinyTopology }));
    const land = await fresh.load();
    expect(land.type).toBe('FeatureCollection');
  });
});

describe('drawGeo paint order', () => {
  const spot = { lat: 54, lng: -2, r: [4], rid: 'A' };

  it('lays the coastline into the plate fill, the field clip and the final stroke', () => {
    const { cv, ctx } = host(100, 100);
    drawGeo(cv, 100, 100, [spot], 0, {});
    const all = names(ctx);
    const iFill = all.indexOf('fill');
    const iBegin = all.indexOf('beginPath');
    expect(iFill - iBegin).toBeGreaterThan(1); // path ops between beginPath and the plate fill
    const rest = all.slice(iFill + 1);
    expect(rest[0]).toBe('save');
    expect(rest[1]).toBe('beginPath');
    const iClip = rest.indexOf('clip');
    expect(iClip).toBeGreaterThan(2); // the clip callback laid the coastline first
    expect(rest).toContain('drawImage');
    const iRestore = rest.indexOf('restore');
    expect(iRestore).toBeGreaterThan(iClip);
    const tail = rest.slice(iRestore + 1);
    expect(tail[0]).toBe('beginPath');
    expect(tail.length).toBeGreaterThan(3); // the coastline path ahead of the stroke
    expect(tail[tail.length - 1]).toBe('stroke');
  });

  it('rules the no-data hatch inside a save, a clip to the coastline and a fresh path', () => {
    const { cv, ctx } = host(100, 100);
    drawGeo(cv, 100, 100, [], 0, { hatch: true });
    const rest = names(ctx).slice(names(ctx).indexOf('fill') + 1);
    expect(rest[0]).toBe('save');
    expect(rest[1]).toBe('beginPath');
    const iClip = rest.indexOf('clip');
    expect(iClip).toBeGreaterThan(2);
    expect(rest[iClip + 1]).toBe('beginPath');
    expect(rest[iClip + 2]).toBe('moveTo');
    const iStroke = rest.indexOf('stroke');
    expect(rest[iStroke + 1]).toBe('restore');
    const tail = rest.slice(iStroke + 2);
    expect(tail[0]).toBe('beginPath');
    expect(tail.length).toBeGreaterThan(3);
    expect(tail[tail.length - 1]).toBe('stroke');
  });
});

describe('the hatch texture', () => {
  it('starts no line at or beyond the right edge', () => {
    const { ctx } = host(20, 20);
    paintUnknown(ctx, 20, 20, [{ x: 10, y: 10, rid: 'A' }], [], { hatchGap: 10 });
    const tc = internals(ctx)[1];
    const starts = only(tc, 'moveTo').map((c) => c.args[0]);
    expect(starts).toEqual([-20, -10, 0, 10]);
    expect(only(tc, 'lineTo')[0].args).toEqual([0, 0]);
  });

  it('uses the default 7px pitch, 0.6px line and bone ink for the unrated hatch', () => {
    const { ctx } = host(100, 100);
    paintUnknown(ctx, 100, 100, [{ x: 50, y: 50, rid: 'A' }], [], {});
    const tc = internals(ctx)[1];
    expect(only(tc, 'moveTo')).toHaveLength(Math.ceil(200 / 7));
    const stroke = only(tc, 'stroke')[0];
    expect(stroke.lineWidth).toBe(0.6);
    expect(stroke.strokeStyle).toBe('rgba(242,231,211,.18)');
  });
});

describe('paintUnknown temp-surface geometry', () => {
  // A 200x100 backing store drawn at 100x50 CSS px, so the backing scale is 2.
  const run = (opts = {}) => {
    const { ctx } = host(200, 100);
    const drawn = paintUnknown(ctx, 100, 50, [{ x: 50, y: 25, rid: 'A' }], [], opts);
    const [off, tc, mc] = internals(ctx);
    return { ctx, off, tc, mc, drawn };
  };

  it('scales the hatch and mask surfaces by backing pixels per CSS pixel, then resets the hatch surface', () => {
    const { tc, mc, drawn } = run();
    expect(drawn).toBe(true);
    expect(only(tc, 'setTransform').map((c) => c.args)).toEqual([[2, 0, 0, 2, 0, 0], [1, 0, 0, 1, 0, 0]]);
    expect(only(mc, 'setTransform').map((c) => c.args)).toEqual([[2, 0, 0, 2, 0, 0]]);
  });

  it('upscales the mask smoothed, by the grid pitch, with the field blur', () => {
    const { off, mc } = run();
    expect(mc.imageSmoothingEnabled).toBe(true);
    expect(mc.filter).toBe('blur(3px)');
    const cols = Math.ceil(100 / 4) + 1;
    const rows = Math.ceil(50 / 4) + 1;
    expect(only(mc, 'drawImage')[0].args).toEqual([off.canvas, 0, 0, cols, rows, 0, 0, cols * 4, rows * 4]);
  });

  it('thickens the mask blur as confidence falls', () => {
    expect(run({ conf: 0.5 }).mc.filter).toBe('blur(4.3px)');
    expect(run({ conf: 1, blur: 5 }).mc.filter).toBe('blur(5px)');
  });

  it('draws the result through the hard clip when only a clipPath is given', () => {
    const { ctx } = host(200, 100);
    const clipPath = { id: 'land' };
    paintUnknown(ctx, 100, 50, [{ x: 50, y: 25, rid: 'A' }], [], { clipPath });
    expect(only(ctx, 'clip').map((c) => c.args[0])).toEqual([clipPath]);
    expect(allContexts.some((c) => only(c, 'fill').length > 0)).toBe(false);
  });

  it('does not take the soft route on a clipSoft with no clipPath', () => {
    const { ctx } = host(200, 100);
    paintUnknown(ctx, 100, 50, [{ x: 50, y: 25, rid: 'A' }], [], { clipSoft: 3 });
    expect(only(ctx, 'clip')).toHaveLength(0);
    expect(allContexts.some((c) => only(c, 'fill').length > 0)).toBe(false);
  });

  it('feathers the land clip on the soft route: fills the blurred path and skips the hard clip', () => {
    const { ctx } = host(200, 100);
    const clipPath = { id: 'land' };
    paintUnknown(ctx, 100, 50, [{ x: 50, y: 25, rid: 'A' }], [], { clipPath, clipSoft: 3 });
    expect(only(ctx, 'clip')).toHaveLength(0);
    const [, tc, , softMask] = internals(ctx);
    expect(only(softMask, 'fill').map((c) => c.args[0])).toEqual([clipPath]);
    expect(softMask.filter).toBe('blur(3px)');
    expect(tc.globalCompositeOperation).toBe('destination-in');
  });

  it('takes the field opacity on the final draw', () => {
    const { ctx } = host(200, 100);
    paintUnknown(ctx, 100, 50, [{ x: 50, y: 25, rid: 'A' }], [], { opacity: 0.4 });
    expect(only(ctx, 'drawImage')[0].alpha).toBe(0.4);
    const { ctx: plain } = host(200, 100);
    paintUnknown(plain, 100, 50, [{ x: 50, y: 25, rid: 'A' }], [], {});
    expect(only(plain, 'drawImage')[0].alpha).toBe(1);
  });
});

describe('unknownMask default radius', () => {
  it('uses max(14, 8.5% of the width) when no radius is given', () => {
    const unrated = [{ x: 200, y: 100, rid: 'A' }];
    const wide = unknownMask(unrated, [], 400, 200);
    const wideExplicit = unknownMask(unrated, [], 400, 200, { radius: 34 });
    expect(Array.from(wide.alpha)).toEqual(Array.from(wideExplicit.alpha));
    const narrow = unknownMask([{ x: 50, y: 50, rid: 'A' }], [], 100, 100);
    const narrowExplicit = unknownMask([{ x: 50, y: 50, rid: 'A' }], [], 100, 100, { radius: 14 });
    expect(Array.from(narrow.alpha)).toEqual(Array.from(narrowExplicit.alpha));
  });
});

describe('the mask is white', () => {
  it('writes full white into every pixel of the hatch mask', () => {
    const { ctx } = host(100, 100);
    paintUnknown(ctx, 100, 100, [{ x: 50, y: 50, rid: 'A' }], [], {});
    const { data } = puts[0];
    expect(Array.from(data).every((v, k) => k % 4 === 3 || v === 255)).toBe(true);
  });
});

describe('paintStandDown', () => {
  it('veils with a dark fill and rules 8px, 1.2px cool-grey lines', () => {
    const { ctx } = host(100, 100);
    paintStandDown(ctx, 100, 100, [{ x: 50, y: 50, rid: 'A' }], [], {});
    const tc = internals(ctx)[1];
    expect(only(tc, 'fillRect')[0].fillStyle).toBe('rgba(16,15,19,.66)');
    expect(only(tc, 'moveTo')).toHaveLength(Math.ceil(200 / 8));
    const stroke = only(tc, 'stroke')[0];
    expect(stroke.lineWidth).toBe(1.2);
    expect(stroke.strokeStyle).toBe('rgba(184,197,220,.5)');
  });

  it('sizes its disc from standRadius and keeps every other dial', () => {
    const stood = [{ x: 60, y: 60, rid: 'A' }];
    const { ctx } = host(120, 120);
    paintStandDown(ctx, 120, 120, stood, [], { standRadius: 40, grid: 8 });
    const viaStand = puts[0];
    puts.length = 0;
    const { ctx: ref } = host(120, 120);
    paintUnknown(ref, 120, 120, stood, [], { radius: 40, grid: 8 });
    const viaUnknown = puts[0];
    expect(viaStand.width).toBe(viaUnknown.width);
    expect(Array.from(viaStand.data)).toEqual(Array.from(viaUnknown.data));
    expect(viaStand.width).toBe(Math.ceil(120 / 8) + 1);
  });

  it('keeps the field radius when no standRadius is given', () => {
    const stood = [{ x: 60, y: 60, rid: 'A' }];
    const { ctx } = host(120, 120);
    paintStandDown(ctx, 120, 120, stood, [], { radius: 40 });
    const viaStand = puts[0];
    puts.length = 0;
    const { ctx: ref } = host(120, 120);
    paintUnknown(ref, 120, 120, stood, [], { radius: 40 });
    expect(Array.from(viaStand.data)).toEqual(Array.from(puts[0].data));
  });
});

describe('which places count as rated for the hatch masks', () => {
  const unrated = [{ lat: 60, lng: 100, rid: 'A' }];
  const cellAt = (put) => maskAlpha(put, Math.round(100 / 4), Math.round(60 / 4));

  const hatchOver = (neighbour) => {
    puts.length = 0;
    const { cv } = host(200, 120);
    drawTiles(cv, fakeMap(200, 120), [neighbour], 0, { unrated });
    return puts[0];
  };

  it('keeps the hatch beside a place with no score, a NaN score or an infinite one', () => {
    expect(cellAt(hatchOver({ lat: 60, lng: 104, r: [null], rid: 'A' }))).toBeGreaterThan(200);
    expect(cellAt(hatchOver({ lat: 60, lng: 104, r: [Number.NaN], rid: 'A' }))).toBeGreaterThan(200);
    expect(cellAt(hatchOver({ lat: 60, lng: 104, r: [undefined], rid: 'A' }))).toBeGreaterThan(200);
  });

  it('lets a finite score suppress the hatch where it stands', () => {
    expect(cellAt(hatchOver({ lat: 60, lng: 104, r: [4], rid: 'A' }))).toBe(0);
  });

  it('puts the unrated hatch where the unrated place is on a Leaflet map', () => {
    const put = hatchOver({ lat: 5, lng: 5, r: [4], rid: 'A' });
    expect(cellAt(put)).toBeGreaterThan(200);
    expect(maskAlpha(put, 5, 5)).toBe(0);
  });

  it('puts the stand-down hatch where the stood place is on a Leaflet map', () => {
    const { cv } = host(200, 120);
    drawTiles(cv, fakeMap(200, 120), [{ lat: 5, lng: 5, r: [4], rid: 'A' }], 0, {
      stood: [{ lat: 60, lng: 100, rid: 'A' }],
    });
    expect(cellAt(puts[0])).toBeGreaterThan(200);
  });
});

describe('drawGeo layers', () => {
  const geoSpot = { lat: 55.5, lng: -2.5, r: [4], rid: 'A' };

  it('hatches the ground around an unrated place at its projected position', () => {
    const { cv } = host(400, 400);
    const projection = drawGeo(cv, 400, 400, [], 0, {
      unrated: [{ lat: 54, lng: -2, rid: 'A' }],
    });
    const [x, y] = projection([-2, 54]);
    expect(maskAlpha(puts[0], Math.round(x / 4), Math.round(y / 4))).toBeGreaterThan(200);
    expect(maskAlpha(puts[0], 0, 0)).toBe(0);
  });

  it('hatches the ground around a stood place, and clips it to the coastline', () => {
    const { cv, ctx } = host(400, 400);
    const projection = drawGeo(cv, 400, 400, [geoSpot], 0, {
      stood: [{ lat: 55.5, lng: -2, rid: 'A' }],
    });
    const [x, y] = projection([-2, 55.5]);
    expect(maskAlpha(puts[0], Math.round(x / 4), Math.round(y / 4))).toBeGreaterThan(200);
    // the stood layer and the field each clip once
    expect(only(ctx, 'clip')).toHaveLength(2);
  });

  it('veils outside places, yields to allowed ones, outlines the picks, and clips all three', () => {
    const { cv, ctx } = host(400, 400);
    const opts = {
      radius: 20,
      outside: [{ lat: 55, lng: -3, rid: 'A' }],
      allowed: [{ lat: 55, lng: -2.8, rid: 'A' }],
    };
    const projection = drawGeo(cv, 400, 400, [geoSpot], 0, opts);
    const [ox, oy] = projection([-3, 55]);
    const [ax, ay] = projection([-2.8, 55]);
    const apart = Math.hypot(ox - ax, oy - ay);
    expect(apart).toBeGreaterThan(5);
    expect(apart).toBeLessThan(25);
    const veil = puts[0];
    const gp = 5; // max(4, min(radius / 4, 12))
    expect(maskAlpha(veil, Math.round(ox / gp), Math.round(oy / gp))).toBeGreaterThan(0);
    expect(maskAlpha(veil, Math.round(ax / gp), Math.round(ay / gp))).toBe(0);
    expect(only(ctx, 'setLineDash')[0].args[0]).toEqual([5, 3]);
    // veil, field, outline
    expect(only(ctx, 'clip')).toHaveLength(3);
  });

  it('draws the outside layers when no allowed list is passed', () => {
    const { cv, ctx } = host(400, 400);
    expect(() => drawGeo(cv, 400, 400, [geoSpot], 0, {
      outside: [{ lat: 54, lng: -2, rid: 'A' }],
    })).not.toThrow();
    expect(only(ctx, 'setLineDash')).toHaveLength(0);
    expect(puts[0].data.every((v, k) => k % 4 !== 3 || v === 255)).toBe(true);
  });
});

describe('coverageGrid', () => {
  it('has one more column and row than the surface holds whole pitches', () => {
    const g = coverageGrid([], 100, 50, 20, 10);
    expect([g.cols, g.rows, g.empty]).toEqual([11, 6, true]);
    expect(g.values).toHaveLength(66);
  });

  it('gives the field clamp 1 - exp(-1/1.15) on a lone place, with no focus', () => {
    const g = coverageGrid([{ x: 20, y: 20, rid: 'A' }], 40, 40, 10, 10);
    expect(g.values[2 * g.cols + 2]).toBeCloseTo(1 - Math.exp(-1 / 1.15), 5);
  });

  it('fades other regions only when a focus is set', () => {
    const points = [{ x: 20, y: 20, rid: 'A' }];
    const focused = coverageGrid(points, 40, 40, 10, 10, 'B');
    const own = coverageGrid(points, 40, 40, 10, 10, 'A');
    expect(focused.values[2 * focused.cols + 2]).toBeLessThan(0.001);
    expect(own.values[2 * own.cols + 2]).toBeCloseTo(1 - Math.exp(-1 / 1.15), 5);
  });
});

describe('contourLines', () => {
  /** Checks every vertex is a true linear crossing of {@code level} on a grid edge. */
  function expectTrueCrossings(lines, values, cols, level) {
    expect(lines.length).toBeGreaterThan(0);
    lines.forEach((line) => {
      line.forEach(([x, y]) => {
        const xi = Math.floor(x);
        const yi = Math.floor(y);
        const horizontal = Number.isInteger(y);
        const a = values[yi * cols + xi];
        const b = horizontal ? values[yi * cols + xi + 1] : values[(yi + 1) * cols + xi];
        expect(Math.min(a, b)).toBeLessThan(level);
        expect(Math.max(a, b)).toBeGreaterThanOrEqual(level);
        const t = (level - a) / (b - a);
        if (horizontal) expect(x).toBeCloseTo(xi + t, 9);
        else expect(y).toBeCloseTo(yi + t, 9);
      });
    });
  }

  const hash = (n) => {
    const s = Math.sin(n * 12.9898) * 43758.5453;
    return s - Math.floor(s);
  };

  it('puts every vertex on a true edge crossing for an irregular field', () => {
    const cols = 9;
    const rows = 7;
    const values = Float32Array.from({ length: cols * rows }, (_, n) => hash(n + 1));
    expectTrueCrossings(contourLines(values, cols, rows, 0.5), values, cols, 0.5);
  });

  it('keeps vertical and horizontal edges apart on a tall narrow grid', () => {
    const cols = 2;
    const rows = 9;
    const values = Float32Array.from({ length: cols * rows }, (_, n) => hash(n + 40));
    expectTrueCrossings(contourLines(values, cols, rows, 0.5), values, cols, 0.5);
  });

  it('keeps every edge distinct when all of them cross the level (checkerboards)', () => {
    [[2, 9], [3, 7], [9, 3], [5, 5]].forEach(([cols, rows]) => {
      const values = Float32Array.from({ length: cols * rows }, (_, n) => ((n % cols) + Math.floor(n / cols)) % 2);
      expectTrueCrossings(contourLines(values, cols, rows, 0.5), values, cols, 0.5);
      const crossings = new Set(contourLines(values, cols, rows, 0.5).flat().map((p) => p.join(',')));
      // every internal edge of the grid is crossed once
      expect(crossings.size).toBe(cols * (rows - 1) + rows * (cols - 1));
    });
  });

  it('keeps vertical and horizontal edges apart on a short wide grid', () => {
    const cols = 9;
    const rows = 3;
    const values = Float32Array.from({ length: cols * rows }, (_, n) => hash(n + 7));
    expectTrueCrossings(contourLines(values, cols, rows, 0.5), values, cols, 0.5);
  });

  it('traces a hot block in one closed loop through its eight edge crossings', () => {
    const cols = 6;
    const values = new Float32Array(36);
    [[2, 2], [3, 2], [2, 3], [3, 3]].forEach(([i, j]) => { values[j * cols + i] = 1; });
    const lines = contourLines(values, cols, 6, 0.5);
    expect(lines).toHaveLength(1);
    const loop = lines[0];
    expect(loop).toHaveLength(9);
    expect(loop[0]).toEqual(loop[8]);
    expect(loop.slice(0, 8).map((p) => p.join(',')).sort()).toEqual(
      ['1.5,2', '1.5,3', '2,1.5', '2,3.5', '3,1.5', '3,3.5', '3.5,2', '3.5,3'].sort(),
    );
  });

  it('keeps a hot cell on the top edge as one open line through three crossings', () => {
    const values = new Float32Array(12);
    values[2] = 1;
    const lines = contourLines(values, 4, 3, 0.5);
    expect(lines).toHaveLength(1);
    const key = lines[0].map((p) => p.join(',')).join(' ');
    expect(['1.5,0 2,0.5 2.5,0', '2.5,0 2,0.5 1.5,0']).toContain(key);
  });

  it('counts a cell exactly at the level as above it, at every corner', () => {
    const values = new Float32Array(9);
    values[4] = 0.5;
    const lines = contourLines(values, 3, 3, 0.5);
    expect(lines).toHaveLength(1);
    expect(lines[0]).toHaveLength(5);
    expect(lines[0][0]).toEqual(lines[0][4]);
  });

  it('connects a saddle whose centre mean is exactly the level as above', () => {
    // tl and br high, tr and bl low; mean 0.5 === level.
    const lines = contourLines(new Float32Array([1, 0, 0, 1]), 2, 2, 0.5);
    const ends = lines.map((l) => l.map((p) => p.join(',')).sort().join(' ')).sort();
    expect(ends).toEqual(['0,0.5 0.5,1', '0.5,0 1,0.5']);
  });

  /** Each line as its sorted, 4-decimal vertex keys, the lines sorted: orientation-free. */
  const shape = (lines) => lines
    .map((l) => l.map((p) => p.map((v) => v.toFixed(4)).join(',')).sort().join(' '))
    .sort();

  it('joins a saddle whose centre mean is above the level across it (top to right, left to bottom)', () => {
    // centre mean (0.9 + 0 + 0.9 + 0) / 4 = 0.45 >= 0.42
    const lines = contourLines(new Float32Array([0.9, 0, 0, 0.9]), 2, 2, 0.42);
    expect(shape(lines)).toEqual(['0.5333,0.0000 1.0000,0.4667', '0.0000,0.5333 0.4667,1.0000'].sort());
  });

  it('separates a saddle whose centre mean is below the level (top to left, bottom to right)', () => {
    // centre mean (0.6 + 0 + 0.6 + 0) / 4 = 0.3 < 0.42
    const lines = contourLines(new Float32Array([0.6, 0, 0, 0.6]), 2, 2, 0.42);
    expect(shape(lines)).toEqual(['0.0000,0.3000 0.3000,0.0000', '0.7000,1.0000 1.0000,0.7000'].sort());
  });
});

describe('planOutsidePicks', () => {
  it('uses the default field radius when none is given', () => {
    const outside = [{ x: 120, y: 100, rid: 'A' }];
    const allowed = [{ x: 280, y: 100, rid: 'A' }];
    const plain = planOutsidePicks(outside, allowed, 400, 200);
    const explicit = planOutsidePicks(outside, allowed, 400, 200, { radius: 34 });
    expect(plain.gp).toBe(explicit.gp);
    expect(Array.from(plain.veil.alpha)).toEqual(Array.from(explicit.veil.alpha));
    expect(plain.lines).toEqual(explicit.lines);

    const small = planOutsidePicks([{ x: 30, y: 50, rid: 'A' }], [{ x: 70, y: 50, rid: 'A' }], 100, 100);
    const smallExplicit = planOutsidePicks(
      [{ x: 30, y: 50, rid: 'A' }], [{ x: 70, y: 50, rid: 'A' }], 100, 100, { radius: 14 },
    );
    expect(Array.from(small.veil.alpha)).toEqual(Array.from(smallExplicit.veil.alpha));
  });

  it('reaches 1.15 radii for the gated places and yields as allowed coverage rises', () => {
    const outside = [{ x: 100, y: 100, rid: 'A' }];
    const allowed = [{ x: 130, y: 100, rid: 'A' }];
    const plan = planOutsidePicks(outside, allowed, 200, 200, { radius: 30 });
    const gp = 7.5;
    expect(plan.gp).toBe(gp);
    const permitted = coverageGrid(allowed, 200, 200, 30 * 0.9, gp);
    const gated = coverageGrid(outside, 200, 200, 30 * 1.15, gp);
    const clamp01 = (v) => Math.min(1, Math.max(0, v));
    const expected = Array.from(gated.values, (g, k) => clamp01(g / 0.35) * clamp01(1 - permitted.values[k] / 0.5));
    expect(Array.from(plan.veil.alpha)).toEqual(Array.from(Float32Array.from(expected)));
    const lines = contourLines(permitted.values, permitted.cols, permitted.rows, 0.42)
      .map((line) => line.map(([x, y]) => [x * gp, y * gp]));
    expect(plan.lines).toEqual(lines);
    expect(plan.lines.length).toBeGreaterThan(0);
  });

  it('covers all the land when nothing is allowed, and draws no outline', () => {
    const plan = planOutsidePicks([{ x: 100, y: 100, rid: 'A' }], [], 200, 200, { radius: 30 });
    expect(plan.lines).toEqual([]);
    expect(plan.veil.alpha.every((v) => v === 1)).toBe(true);
  });
});

describe('paintVeil', () => {
  const plan = { veil: { cols: 5, rows: 5, gp: 4, alpha: new Float32Array(25).fill(1) } };

  it('declines without a plan or without a veil', () => {
    const { ctx } = host(100, 100);
    expect(paintVeil(ctx, 100, 100, null)).toBe(false);
    expect(paintVeil(ctx, 100, 100, { veil: null })).toBe(false);
    expect(ctx.calls).toHaveLength(0);
  });

  it('works with no options: a flat grey wash blurred 1px more than the hatches', () => {
    const { ctx } = host(100, 100);
    expect(paintVeil(ctx, 100, 100, plan)).toBe(true);
    const [, tc, mc] = internals(ctx);
    expect(only(tc, 'fillRect')[0].fillStyle).toBe('rgba(156,158,160,.18)');
    expect(mc.filter).toBe('blur(4px)');
  });

  it('takes the caller blur (plus 1px) and opacity', () => {
    const { ctx } = host(100, 100);
    paintVeil(ctx, 100, 100, plan, { blur: 5, opacity: 0.5 });
    const [, , mc] = internals(ctx);
    expect(mc.filter).toBe('blur(6px)');
    expect(only(ctx, 'drawImage')[0].alpha).toBe(0.5);
  });
});

describe('paintOutline', () => {
  const plan = { lines: [[[1, 2], [3, 4], [5, 6]], [[7, 8], [9, 10]]] };

  it('declines without a plan or without lines', () => {
    const { ctx } = host(100, 100);
    expect(paintOutline(ctx, null)).toBe(false);
    expect(paintOutline(ctx, { lines: [] })).toBe(false);
    expect(ctx.calls).toHaveLength(0);
  });

  it('strokes every polyline dashed, inside the hard clip, then restores', () => {
    const { ctx } = host(100, 100);
    const clipPath = { id: 'land' };
    expect(paintOutline(ctx, plan, { clipPath, clipDx: 2, clipDy: 3, opacity: 0.4, outlineWidth: 2 })).toBe(true);
    expect(names(ctx)).toEqual([
      'save', 'translate', 'clip', 'translate', 'setLineDash', 'beginPath',
      'moveTo', 'lineTo', 'lineTo', 'moveTo', 'lineTo', 'stroke', 'restore',
    ]);
    expect(ctx.calls[1].args).toEqual([2, 3]);
    expect(ctx.calls[2].args).toEqual([clipPath]);
    expect(ctx.calls[3].args).toEqual([-2, -3]);
    expect(ctx.calls.filter((c) => c.name === 'moveTo').map((c) => c.args)).toEqual([[1, 2], [7, 8]]);
    expect(ctx.calls.filter((c) => c.name === 'lineTo').map((c) => c.args)).toEqual([[3, 4], [5, 6], [9, 10]]);
    const stroke = only(ctx, 'stroke')[0];
    expect(stroke).toMatchObject({
      alpha: 0.4, lineWidth: 2, strokeStyle: 'rgba(242,231,211,.85)', dash: [5, 3],
    });
  });

  it('defaults to full opacity and a 1.4px line', () => {
    const { ctx } = host(100, 100);
    paintOutline(ctx, plan);
    const stroke = only(ctx, 'stroke')[0];
    expect(stroke).toMatchObject({ alpha: 1, lineWidth: 1.4, dash: [5, 3] });
  });
});
