import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  bbox, drawGeo, drawTiles, load, paintStandDown, paintUnknown,
} from '../utils/heatField.js';

/**
 * The stand-down layer (big-seas-plan.md F3; docs/design/big-seas/README.md "Stand-down hatch"):
 * the same projection-independent paint as the "not rated yet" hatch, with a dark veil and a cool
 * rule, drawn UNDER the field with the rated places' mask so colour wins. The context is a local
 * recording stub, as in heatFieldUnknown.test.js (jsdom has no 2d context).
 */

const PATH_METHODS = ['moveTo', 'lineTo', 'closePath', 'arc'];
const STAND_FILL = 'rgba(16,15,19,.66)';
const STAND_INK = 'rgba(184,197,220,.5)';
const UNKNOWN_INK = 'rgba(242,231,211,.18)';

function makeContext() {
  const calls = [];
  const record = (name) => (...args) => {
    calls.push({
      name,
      args,
      fillStyle: ctx.fillStyle,
      strokeStyle: ctx.strokeStyle,
      lineWidth: ctx.lineWidth,
      globalAlpha: ctx.globalAlpha,
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

function canvasWithContext(w = 100, h = 40) {
  const cv = document.createElement('canvas');
  cv.width = w;
  cv.height = h;
  return { cv, ctx: cv.getContext('2d') };
}

const names = (ctx) => ctx.calls.map((c) => c.name).filter((n) => !PATH_METHODS.includes(n));

/** The mask the layer built: the alpha channel of the one `putImageData` on its small canvas. */
function maskAlphaAt(x, y, gp = 4) {
  const put = allContexts.flatMap((c) => c.calls).find((c) => c.name === 'putImageData');
  const img = put.args[0];
  const cols = img.width;
  return img.data[((Math.round(y / gp)) * cols + Math.round(x / gp)) * 4 + 3];
}

/** Which layer each host `drawImage` carries, read off the source canvas's own recorded calls. */
function layerOf(sourceCanvas) {
  const calls = contexts.get(sourceCanvas)?.calls ?? [];
  if (calls.some((c) => c.name === 'fillRect' && c.fillStyle === STAND_FILL)) return 'stood';
  if (calls.some((c) => c.name === 'stroke' && c.strokeStyle === UNKNOWN_INK)) return 'unknown';
  return 'field';
}

describe('paintStandDown', () => {
  const STOOD = [{ x: 50, y: 20, rid: 'A' }];

  it('reports whether it drew', () => {
    const { ctx } = canvasWithContext();
    expect(paintStandDown(ctx, 100, 40, [], [], { radius: 20 })).toBe(false);
    expect(paintStandDown(ctx, 100, 40, STOOD, [], { radius: 20 })).toBe(true);
  });

  it('is solid at a lone stood place', () => {
    const { ctx } = canvasWithContext();
    paintStandDown(ctx, 100, 40, STOOD, [], { radius: 20, grid: 4 });
    expect(maskAlphaAt(48, 20)).toBe(255);
  });

  it('yields to a rated place on the same ground: rated colour wins in a mixed region', () => {
    const { ctx } = canvasWithContext(200, 40);
    paintStandDown(
      ctx, 200, 40,
      [{ x: 30, y: 20, rid: 'A' }, { x: 120, y: 20, rid: 'A' }],
      [{ x: 120, y: 20, rid: 'A' }],
      { radius: 20, grid: 4 },
    );
    expect(maskAlphaAt(28, 20)).toBe(255);
    expect(maskAlphaAt(120, 20)).toBe(0);
  });

  it('takes its disc radius from standRadius, not the field radius', () => {
    const { ctx } = canvasWithContext(300, 40);
    paintStandDown(ctx, 300, 40, [{ x: 20, y: 20, rid: 'A' }], [], { radius: 90, standRadius: 20, grid: 4 });
    // 2.45 x 20 = 49px of reach: 100px away is bare ground, though the field radius (90) would reach it.
    expect(maskAlphaAt(120, 20)).toBe(0);
    expect(maskAlphaAt(20, 20)).toBe(255);
  });

  it('rules a dark veil, then an 8px pitch of 1.2px lines in the cool ink', () => {
    const { ctx } = canvasWithContext();
    paintStandDown(ctx, 100, 40, STOOD, [], { radius: 20 });
    const veil = allContexts.flatMap((c) => c.calls).find((c) => c.name === 'fillRect');
    expect(veil.fillStyle).toBe(STAND_FILL);
    expect(veil.args).toEqual([0, 0, 100, 40]);
    const rules = allContexts.find((c) => c.calls.some((k) => k.name === 'stroke'));
    const stroke = rules.calls.find((k) => k.name === 'stroke');
    expect(stroke.strokeStyle).toBe(STAND_INK);
    expect(stroke.lineWidth).toBe(1.2);
    const starts = rules.calls.filter((k) => k.name === 'moveTo').map((k) => k.args[0]);
    expect(new Set(starts.slice(1).map((x, i) => x - starts[i]))).toEqual(new Set([8]));
  });

  it('is textured differently from the thin-sample hatch (7px, 0.6px, bone, no veil)', () => {
    const { ctx } = canvasWithContext();
    paintUnknown(ctx, 100, 40, STOOD, [], { radius: 20 });
    const unknown = allContexts.flatMap((c) => c.calls);
    expect(unknown.some((c) => c.name === 'fillRect')).toBe(false);
    expect(unknown.find((c) => c.name === 'stroke').strokeStyle).toBe(UNKNOWN_INK);
  });

  it('draws the finished layer at the opacity it is given, full strength when none is', () => {
    const alphaAtFinalDraw = (opacity) => {
      const { ctx } = canvasWithContext();
      let alpha = null;
      const draw = ctx.drawImage;
      ctx.drawImage = (...args) => {
        alpha = ctx.globalAlpha;
        return draw(...args);
      };
      paintStandDown(ctx, 100, 40, STOOD, [], { radius: 20, opacity });
      return alpha;
    };
    expect(alphaAtFinalDraw(undefined)).toBe(1);
    expect(alphaAtFinalDraw(0.35)).toBeCloseTo(0.35, 5);
  });

  it('takes the soft land clip when the host asks for it, a hard one otherwise', () => {
    const soft = canvasWithContext();
    paintStandDown(soft.ctx, 100, 40, STOOD, [], { radius: 20, clipPath: {}, clipSoft: 4 });
    expect(names(soft.ctx)).not.toContain('clip');
    const hard = canvasWithContext();
    paintStandDown(hard.ctx, 100, 40, STOOD, [], { radius: 20, clip: () => {} });
    expect(names(hard.ctx)).toContain('clip');
  });
});

describe('drawGeo — the stood option', () => {
  const SPOTS = [
    { lat: 54.5, lng: -2.5, r: [4], rid: 'north' },
    { lat: 55.2, lng: -1.6, r: [5], rid: 'north' },
  ];
  const UNRATED = [{ lat: 54.9, lng: -2.1, r: [0], rid: 'north' }];
  const STOOD = [{ lat: 54.7, lng: -2.3, rid: 'north' }];
  const FIT = bbox([...SPOTS, ...UNRATED, ...STOOD]);

  beforeEach(async () => {
    await load();
  });

  it('draws exactly what it drew before when the option is absent, empty or undefined', () => {
    const a = canvasWithContext(200, 140);
    const b = canvasWithContext(200, 140);
    const c = canvasWithContext(200, 140);
    drawGeo(a.cv, 200, 140, SPOTS, 0, { fit: FIT });
    drawGeo(b.cv, 200, 140, SPOTS, 0, { fit: FIT, stood: [] });
    drawGeo(c.cv, 200, 140, SPOTS, 0, { fit: FIT, stood: undefined });
    expect(names(b.ctx)).toEqual(names(a.ctx));
    expect(names(c.ctx)).toEqual(names(a.ctx));
  });

  it('paints unknown, then stood, then the field, then the coast', () => {
    const { cv, ctx } = canvasWithContext(200, 140);
    drawGeo(cv, 200, 140, SPOTS, 0, { fit: FIT, unrated: UNRATED, stood: STOOD, standRadius: 20 });
    const layers = ctx.calls.filter((c) => c.name === 'drawImage').map((c) => layerOf(c.args[0]));
    expect(layers).toEqual(['unknown', 'stood', 'field']);
    expect(names(ctx).at(-1)).toBe('stroke'); // coastline last
    expect(names(ctx).indexOf('stroke')).toBe(names(ctx).length - 1);
  });

  it('lets a rated place at the same spot suppress the stood layer there', () => {
    const over = [{ lat: 54.5, lng: -2.5, rid: 'north' }];
    const peak = (spots) => {
      allContexts.length = 0;
      const { cv } = canvasWithContext(200, 140);
      drawGeo(cv, 200, 140, spots, 0, { fit: FIT, stood: over, standRadius: 20 });
      const put = allContexts.flatMap((c) => c.calls).find((c) => c.name === 'putImageData').args[0];
      return Math.max(...Array.from({ length: put.width * put.height }, (_, i) => put.data[i * 4 + 3]));
    };
    expect(peak([])).toBe(255);
    expect(peak(SPOTS)).toBeLessThan(255);
  });
});

describe('drawTiles — the stood option', () => {
  const SPOTS = [
    { lat: 54.5, lng: -2.5, r: [4], rid: 'north' },
    { lat: 55.2, lng: -1.6, r: [5], rid: 'north' },
  ];
  const UNRATED = [{ lat: 54.9, lng: -2.1, r: [0], rid: 'north' }];
  const STOOD = [{ lat: 54.7, lng: -2.3, rid: 'north' }];
  const fakeMap = (w, h) => ({
    getSize: () => ({ x: w, y: h }),
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
  });

  it('is unchanged without the option, with an empty one, or an undefined one', () => {
    const a = canvasWithContext(300, 200);
    const b = canvasWithContext(300, 200);
    drawTiles(a.cv, fakeMap(300, 200), SPOTS, 0, { radius: 40, grid: 6 });
    drawTiles(b.cv, fakeMap(300, 200), SPOTS, 0, { radius: 40, grid: 6, stood: [] });
    expect(names(b.ctx)).toEqual(names(a.ctx));
  });

  it('paints unknown, then stood, then the field, projecting stood places through the map', () => {
    const { cv, ctx } = canvasWithContext(300, 200);
    const map = fakeMap(300, 200);
    const seen = [];
    const real = map.latLngToContainerPoint;
    map.latLngToContainerPoint = (ll) => {
      seen.push(ll);
      return real(ll);
    };
    drawTiles(cv, map, SPOTS, 0, {
      radius: 40, grid: 6, unrated: UNRATED, stood: STOOD, standRadius: 20,
    });
    expect(seen).toContainEqual([54.7, -2.3]);
    const layers = ctx.calls.filter((c) => c.name === 'drawImage').map((c) => layerOf(c.args[0]));
    expect(layers).toEqual(['unknown', 'stood', 'field']);
  });

  it('draws the stood layer even when no place is rated: a whole-map stand-down still hatches', () => {
    const { cv, ctx } = canvasWithContext(300, 200);
    drawTiles(cv, fakeMap(300, 200), [], 0, { radius: 40, grid: 6, stood: STOOD, standRadius: 20 });
    const layers = ctx.calls.filter((c) => c.name === 'drawImage').map((c) => layerOf(c.args[0]));
    expect(layers).toContain('stood');
  });
});
