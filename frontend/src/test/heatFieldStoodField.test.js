import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { drawGeo, drawTiles, bbox, load } from '../utils/heatField.js';
import { rampRgb } from '../utils/scoreRamp.js';
import { STOOD_FIELD_SCORE } from '../utils/heatSpots.js';

/**
 * The stood-down field (stood-down-field-plan.md D2): {@code opts.stoodField} blends weather-stood
 * places into the colour at one star, in the list {@code paint} receives and nowhere else. The
 * regression the plan review caught is pinned here: the places must NOT enter the rated mask, or the
 * stand-down hatch would vanish at every stood place.
 *
 * The context is a local recording stub (jsdom has no 2d context), as in heatFieldStandDown.test.js.
 */

const PATH_METHODS = ['moveTo', 'lineTo', 'closePath', 'arc'];
const STAND_FILL = 'rgba(16,15,19,.66)';

function makeContext() {
  const calls = [];
  const record = (name) => (...args) => {
    calls.push({ name, args, fillStyle: ctx.fillStyle });
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
    createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4), width: w, height: h }),
  };
  ['putImageData', 'clearRect', 'fillRect', 'save', 'restore', 'drawImage', 'beginPath', 'fill',
    'stroke', 'clip', 'setTransform', 'translate', ...PATH_METHODS].forEach((name) => {
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

function canvasWithContext(w = 300, h = 200) {
  const cv = document.createElement('canvas');
  cv.width = w;
  cv.height = h;
  return { cv, ctx: cv.getContext('2d') };
}

const fakeMap = (w, h) => ({
  getSize: () => ({ x: w, y: h }),
  latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
});

/** A place at container pixel (x, y) under {@link fakeMap}. */
const at = (x, y, extra = {}) => ({ lat: 56 - y / 100, lng: x / 100 - 3, rid: 'north', ...extra });

const rgbAt = (f, x, y, gp = 6) => {
  const k = (Math.round(y / gp) * f.cols + Math.round(x / gp)) * 4;
  return [f.img.data[k], f.img.data[k + 1], f.img.data[k + 2]];
};
const distance = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);

describe('STOOD_FIELD_SCORE', () => {
  it('is one star', () => {
    expect(STOOD_FIELD_SCORE).toBe(1);
  });
});

describe('the stood field colours the field (the Sunday 15 November case)', () => {
  // Six rated places (one 4 star, five 3 star) and nineteen weather-stood ones around the 3 stars.
  const FOUR = at(15, 100, { r: [4] });
  const THREES = [150, 160, 170, 180, 190].map((x) => at(x, 100, { r: [3] }));
  const RATED = [FOUR, ...THREES];
  const STOOD = Array.from({ length: 19 }, (_, i) => at(110 + (i % 10) * 13, 70 + Math.floor(i / 10) * 60));
  const OPTS = { radius: 40, grid: 6 };

  it('has the fixture the plan describes: 6 rated, 19 stood', () => {
    expect(RATED).toHaveLength(6);
    expect(STOOD).toHaveLength(19);
  });

  it('paints the rated cover warm without the option, and cool with it', () => {
    const without = drawTiles(canvasWithContext().cv, fakeMap(300, 200), RATED, 0, OPTS);
    const withField = drawTiles(canvasWithContext().cv, fakeMap(300, 200), RATED, 0, { ...OPTS, stoodField: STOOD });
    const before = rgbAt(without, 170, 100);
    const after = rgbAt(withField, 170, 100);
    expect(distance(before, rampRgb(3))).toBeLessThan(distance(before, rampRgb(1)));
    expect(distance(after, rampRgb(1))).toBeLessThan(distance(after, rampRgb(3)));
  });

  it('leaves the ground beside the 4 star warm', () => {
    const withField = drawTiles(canvasWithContext().cv, fakeMap(300, 200), RATED, 0, { ...OPTS, stoodField: STOOD });
    const near = rgbAt(withField, 15, 100);
    expect(distance(near, rampRgb(4))).toBeLessThan(distance(near, rampRgb(1)));
  });

  it('is byte-identical to today with the option absent, empty or undefined', () => {
    const bytes = (opts) => Array.from(drawTiles(canvasWithContext().cv, fakeMap(300, 200), RATED, 0, opts).img.data);
    const base = bytes(OPTS);
    expect(bytes({ ...OPTS, stoodField: [] })).toEqual(base);
    expect(bytes({ ...OPTS, stoodField: undefined })).toEqual(base);
    expect(bytes({ ...OPTS, stoodField: null })).toEqual(base);
  });

  it('gives a window where every place stood down a field, not null', () => {
    expect(drawTiles(canvasWithContext().cv, fakeMap(300, 200), [], 0, OPTS)).toBeNull();
    const f = drawTiles(canvasWithContext().cv, fakeMap(300, 200), [], 0, { ...OPTS, stoodField: STOOD });
    expect(f).not.toBeNull();
    const cell = rgbAt(f, 150, 100);
    expect(distance(cell, rampRgb(1))).toBeLessThan(1);
  });

  it('does not mutate the input list or the spots', () => {
    const stood = STOOD.map((s) => ({ ...s }));
    const rated = RATED.map((s) => ({ ...s, r: [...s.r] }));
    drawTiles(canvasWithContext().cv, fakeMap(300, 200), rated, 0, { ...OPTS, stoodField: stood });
    expect(stood).toEqual(STOOD);
    expect(rated).toEqual(RATED);
  });

  it('focus weighting applies to a stood place by its region', () => {
    const other = STOOD.map((s) => ({ ...s, rid: 'south' }));
    const f = drawTiles(canvasWithContext().cv, fakeMap(300, 200), RATED, 0, {
      ...OPTS, stoodField: other, focus: 'north',
    });
    const cell = rgbAt(f, 170, 100);
    // The stood places are outside the focused region, so they barely weigh: the rated colour stands.
    expect(distance(cell, rampRgb(3))).toBeLessThan(distance(cell, rampRgb(1)));
  });
});

describe('the stand-down mask still paints with stoodField given (regression)', () => {
  const layerOf = (sourceCanvas) => {
    const calls = contexts.get(sourceCanvas)?.calls ?? [];
    return calls.some((c) => c.name === 'fillRect' && c.fillStyle === STAND_FILL) ? 'stood' : 'other';
  };
  const firstPutImage = () => allContexts.flatMap((c) => c.calls).find((c) => c.name === 'putImageData').args[0];

  describe('drawTiles', () => {
    const RATED = [at(30, 100, { r: [4] })];
    const STOOD = [at(220, 100)];
    const OPTS = { radius: 40, grid: 6, stood: STOOD, standRadius: 20 };

    it('draws the stood layer and builds an identical mask, so the hatch is not erased', () => {
      const a = canvasWithContext();
      drawTiles(a.cv, fakeMap(300, 200), RATED, 0, OPTS);
      const plain = Array.from(firstPutImage().data);
      allContexts.length = 0;
      const b = canvasWithContext();
      drawTiles(b.cv, fakeMap(300, 200), RATED, 0, { ...OPTS, stoodField: STOOD });
      const layers = b.ctx.calls.filter((c) => c.name === 'drawImage').map((c) => layerOf(c.args[0]));
      expect(layers).toContain('stood');
      expect(Array.from(firstPutImage().data)).toEqual(plain);
      expect(Math.max(...plain.filter((_, i) => i % 4 === 3))).toBe(255);
    });
  });

  describe('drawGeo', () => {
    const SPOTS = [{ lat: 54.5, lng: -2.5, r: [4], rid: 'north' }];
    const STOOD = [{ lat: 55.2, lng: -1.6, rid: 'north' }];
    const FIT = bbox([...SPOTS, ...STOOD]);

    beforeEach(async () => {
      await load();
    });

    it('still paints the stood layer, with an identical mask', () => {
      const a = canvasWithContext(200, 140);
      drawGeo(a.cv, 200, 140, SPOTS, 0, { fit: FIT, stood: STOOD, standRadius: 20 });
      const plain = Array.from(firstPutImage().data);
      allContexts.length = 0;
      const b = canvasWithContext(200, 140);
      drawGeo(b.cv, 200, 140, SPOTS, 0, { fit: FIT, stood: STOOD, standRadius: 20, stoodField: STOOD });
      const layers = b.ctx.calls.filter((c) => c.name === 'drawImage').map((c) => layerOf(c.args[0]));
      expect(layers).toEqual(['stood', 'other']);
      expect(Array.from(firstPutImage().data)).toEqual(plain);
    });

    it('draws exactly what it drew before when the option is absent, empty or undefined', () => {
      const names = (ctx) => ctx.calls.map((c) => c.name).filter((n) => !PATH_METHODS.includes(n));
      const a = canvasWithContext(200, 140);
      const b = canvasWithContext(200, 140);
      const c = canvasWithContext(200, 140);
      drawGeo(a.cv, 200, 140, SPOTS, 0, { fit: FIT });
      drawGeo(b.cv, 200, 140, SPOTS, 0, { fit: FIT, stoodField: [] });
      drawGeo(c.cv, 200, 140, SPOTS, 0, { fit: FIT, stoodField: undefined });
      expect(names(b.ctx)).toEqual(names(a.ctx));
      expect(names(c.ctx)).toEqual(names(a.ctx));
    });
  });
});
