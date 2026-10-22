import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  bbox, drawGeo, drawTiles, load, paintUnknown, unknownMask,
} from '../utils/heatField.js';

/**
 * The unknown-ground layer (thin-sample-plan.md F1; docs/design/thin-sample/README.md): a hatch
 * around unrated places, masked so a rated place's colour wins where it overlaps.
 *
 * The context is a local recording stub, as in heatField.test.js (jsdom has no 2d context and no
 * global polyfill goes into setup.js).
 */

const PATH_METHODS = ['moveTo', 'lineTo', 'closePath', 'arc'];

function makeContext() {
  const calls = [];
  const record = (name) => (...args) => {
    calls.push({
      name, args, filter: ctx.filter, globalCompositeOperation: ctx.globalCompositeOperation,
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

function canvasWithContext() {
  const cv = document.createElement('canvas');
  return { cv, ctx: cv.getContext('2d') };
}

const names = (ctx) => ctx.calls.map((c) => c.name).filter((n) => !PATH_METHODS.includes(n));

/** The mask's alpha at one grid cell. */
function at(m, i, j) {
  return m.alpha[j * m.cols + i];
}

/** The mask the plain way: every cell against every place, no buckets. */
function bruteMask(unrated, rated, w, h, { radius, grid, focus }) {
  const R2 = radius * radius;
  const cols = Math.ceil(w / grid) + 1;
  const rows = Math.ceil(h / grid) + 1;
  const alpha = new Float32Array(cols * rows);
  const sum = (pts, px, py) => {
    let s = 0;
    for (const p of pts) {
      const d2 = (px - p.x) ** 2 + (py - p.y) ** 2;
      if (d2 > R2 * 6) continue;
      let wt = Math.exp(-d2 / R2);
      if (focus && p.rid !== focus) wt *= 1e-4;
      s += wt;
    }
    return s;
  };
  const clamp01 = (v) => Math.min(1, Math.max(0, v));
  for (let j = 0; j < rows; j += 1) {
    for (let i = 0; i < cols; i += 1) {
      const cu = 1 - Math.exp(-sum(unrated, i * grid, j * grid) / 1.15);
      const cr = 1 - Math.exp(-sum(rated, i * grid, j * grid) / 1.15);
      alpha[j * cols + i] = clamp01(cu / 0.45) * clamp01(1 - cr / 0.45);
    }
  }
  return { cols, rows, alpha };
}

describe('unknownMask', () => {
  const OPTS = { radius: 20, grid: 1 };

  it('is solid at the centre of a lone unrated place', () => {
    const m = unknownMask([{ x: 50, y: 20, rid: 'A' }], [], 100, 40, OPTS);
    expect(at(m, 50, 20)).toBe(1);
  });

  it('fades to nothing beyond the kernel\'s reach', () => {
    const m = unknownMask([{ x: 20, y: 20, rid: 'A' }], [], 200, 40, OPTS);
    // 2.45 R = 49 px; 150 px away is empty ground
    expect(at(m, 170, 20)).toBe(0);
  });

  it('is zero under a rated place co-located with an unrated one — colour wins', () => {
    const m = unknownMask(
      [{ x: 50, y: 20, rid: 'A' }], [{ x: 50, y: 20, rid: 'A' }], 100, 40, OPTS,
    );
    expect(at(m, 50, 20)).toBe(0);
  });

  it('lets a rated place suppress only its own neighbourhood', () => {
    const m = unknownMask(
      [{ x: 30, y: 20, rid: 'A' }, { x: 120, y: 20, rid: 'A' }],
      [{ x: 120, y: 20, rid: 'A' }],
      160, 40, OPTS,
    );
    expect(at(m, 30, 20)).toBe(1);
    expect(at(m, 120, 20)).toBe(0);
  });

  it('is null when there is no unrated place to hatch around', () => {
    expect(unknownMask([], [{ x: 50, y: 20, rid: 'A' }], 100, 40, OPTS)).toBeNull();
  });

  it('is null when every unrated place is outside the frame and its reach', () => {
    expect(unknownMask([{ x: 900, y: 900, rid: 'A' }], [], 100, 40, OPTS)).toBeNull();
  });

  it('draws nothing on empty ground away from every place', () => {
    const m = unknownMask([{ x: 10, y: 10, rid: 'A' }], [], 300, 40, OPTS);
    expect(at(m, 290, 30)).toBe(0);
  });

  it('fades an unfocused region\'s hatch the way the field fades its colour', () => {
    const unrated = [{ x: 50, y: 20, rid: 'A' }, { x: 150, y: 20, rid: 'B' }];
    const plain = unknownMask(unrated, [], 200, 40, OPTS);
    const focused = unknownMask(unrated, [], 200, 40, { ...OPTS, focus: 'A' });
    expect(at(plain, 150, 20)).toBe(1);
    expect(at(focused, 50, 20)).toBe(1);
    expect(at(focused, 150, 20)).toBeLessThan(0.001);
  });

  describe('bucketing equals brute force', () => {
    // A deterministic scatter so a failure reproduces; 60 places over a frame several buckets wide.
    let seed = 7;
    const rnd = () => {
      seed = (seed * 1664525 + 1013904223) % 4294967296;
      return seed / 4294967296;
    };
    const pts = Array.from({ length: 60 }, (_, n) => ({
      x: -30 + rnd() * 360,
      y: -30 + rnd() * 260,
      rid: n % 3 === 0 ? 'A' : 'B',
    }));
    const unrated = pts.filter((_, n) => n % 2 === 0);
    const rated = pts.filter((_, n) => n % 2 === 1);

    [undefined, 'A'].forEach((focus) => {
      it(`agrees cell by cell${focus ? ' with a focused region' : ''}`, () => {
        const opts = { radius: 22, grid: 4, focus };
        const fast = unknownMask(unrated, rated, 300, 200, opts);
        const slow = bruteMask(unrated, rated, 300, 200, opts);
        expect(fast.cols).toBe(slow.cols);
        expect(fast.rows).toBe(slow.rows);
        for (let k = 0; k < slow.alpha.length; k += 1) {
          expect(fast.alpha[k]).toBeCloseTo(slow.alpha[k], 5);
        }
      });
    });
  });
});

describe('drawGeo — the unrated option', () => {
  const SPOTS = [
    { lat: 54.5, lng: -2.5, r: [4], rid: 'north' },
    { lat: 55.2, lng: -1.6, r: [5], rid: 'north' },
  ];
  const UNRATED = [{ lat: 54.9, lng: -2.1, r: [0], rid: 'north' }];
  const FIT = bbox([...SPOTS, ...UNRATED]);

  beforeEach(async () => {
    await load();
  });

  it('draws exactly what it drew before when the option is absent, empty or undefined', () => {
    const a = canvasWithContext();
    const b = canvasWithContext();
    const c = canvasWithContext();
    drawGeo(a.cv, 200, 140, SPOTS, 0, { fit: FIT });
    drawGeo(b.cv, 200, 140, SPOTS, 0, { fit: FIT, unrated: [] });
    drawGeo(c.cv, 200, 140, SPOTS, 0, { fit: FIT, unrated: undefined });
    expect(names(b.ctx)).toEqual(names(a.ctx));
    expect(names(c.ctx)).toEqual(names(a.ctx));
  });

  it('lays the hatch above the plate and under the field, each behind a hard land clip', () => {
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, { fit: FIT, unrated: UNRATED });
    expect(names(ctx)).toEqual([
      'setTransform',
      'fillRect', // sea
      'beginPath',
      'fill', // plate
      'save',
      'beginPath',
      'clip', // unknown layer, clipped to land
      'drawImage',
      'restore',
      'save',
      'beginPath',
      'clip', // the field
      'drawImage',
      'restore',
      'beginPath',
      'stroke', // coastline
    ]);
  });

  it('masks the hatch with one destination-in on its own surface', () => {
    const { cv } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, { fit: FIT, unrated: UNRATED });
    const masked = allContexts.filter((c) => c.calls.some(
      (k) => k.name === 'drawImage' && k.globalCompositeOperation === 'destination-in',
    ));
    expect(masked).toHaveLength(1);
    expect(masked[0].calls.filter((k) => k.globalCompositeOperation === 'destination-in'))
      .toHaveLength(1);
  });

  it('skips the layer when the window has nothing unrated to show', () => {
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, { fit: FIT, unrated: [] });
    expect(names(ctx).filter((n) => n === 'save')).toHaveLength(1);
  });

  it('draws the layer even when no place is rated — the host decides whether a window is unscored', () => {
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, [], 0, { fit: FIT, unrated: UNRATED });
    expect(names(ctx).filter((n) => n === 'save')).toHaveLength(1);
  });
});

describe('drawTiles — the unrated option', () => {
  const SPOTS = [
    { lat: 54.5, lng: -2.5, r: [4], rid: 'north' },
    { lat: 55.2, lng: -1.6, r: [5], rid: 'north' },
  ];
  const UNRATED = [{ lat: 54.9, lng: -2.1, r: [0], rid: 'north' }];
  const fakeMap = (w, h) => ({
    getSize: () => ({ x: w, y: h }),
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
  });

  it('is unchanged without the option', () => {
    const a = canvasWithContext();
    const b = canvasWithContext();
    drawTiles(a.cv, fakeMap(300, 200), SPOTS, 0, { radius: 40, grid: 6 });
    drawTiles(b.cv, fakeMap(300, 200), SPOTS, 0, { radius: 40, grid: 6, unrated: [] });
    expect(names(b.ctx)).toEqual(['setTransform', 'clearRect', 'save', 'drawImage', 'restore']);
    expect(names(a.ctx)).toEqual(names(b.ctx));
  });

  it('paints the hatch before the field, projecting unrated places through the map', () => {
    const { cv, ctx } = canvasWithContext();
    const map = fakeMap(300, 200);
    const seen = [];
    const real = map.latLngToContainerPoint;
    map.latLngToContainerPoint = (ll) => {
      seen.push(ll);
      return real(ll);
    };
    drawTiles(cv, map, SPOTS, 0, { radius: 40, grid: 6, unrated: UNRATED });
    expect(seen).toContainEqual([54.9, -2.1]);
    expect(names(ctx)).toEqual([
      'setTransform', 'clearRect',
      'save', 'drawImage', 'restore', // unknown layer
      'save', 'drawImage', 'restore', // field
    ]);
  });

  it('takes the soft land clip when the host asks for it, as the field does', () => {
    const { cv, ctx } = canvasWithContext();
    drawTiles(cv, fakeMap(300, 200), SPOTS, 0, {
      radius: 40, grid: 6, unrated: UNRATED, clipPath: {}, clipSoft: 4,
    });
    // soft route: no ctx.clip on the host context, one flat draw per layer
    expect(names(ctx).filter((n) => n === 'clip')).toHaveLength(0);
    expect(names(ctx).filter((n) => n === 'drawImage')).toHaveLength(2);
  });
});

describe('paintUnknown', () => {
  it('reports whether it drew', () => {
    const { cv, ctx } = canvasWithContext();
    cv.width = 100;
    cv.height = 40;
    expect(paintUnknown(ctx, 100, 40, [], [], { radius: 20 })).toBe(false);
    expect(paintUnknown(ctx, 100, 40, [{ x: 50, y: 20, rid: 'A' }], [], { radius: 20 })).toBe(true);
  });

  it('draws the finished hatch at the opacity it is given, full strength when none is', () => {
    const alphaAtFinalDraw = (opacity) => {
      const { cv, ctx } = canvasWithContext();
      cv.width = 100;
      cv.height = 40;
      let alpha = null;
      const draw = ctx.drawImage;
      ctx.drawImage = (...args) => {
        alpha = ctx.globalAlpha;
        return draw(...args);
      };
      paintUnknown(ctx, 100, 40, [{ x: 50, y: 20, rid: 'A' }], [], { radius: 20, opacity });
      return alpha;
    };
    expect(alphaAtFinalDraw(undefined)).toBe(1);
    expect(alphaAtFinalDraw(0.35)).toBeCloseTo(0.35, 5);
  });

  it('upscales the mask with the field\'s own blur, widened by (1 - conf) * 2.6', () => {
    const blurs = (conf) => {
      const { cv, ctx } = canvasWithContext();
      cv.width = 100;
      cv.height = 40;
      const seen = [];
      const create = document.createElement.bind(document);
      const orig = document.createElement;
      document.createElement = (tag) => {
        const el = create(tag);
        if (tag === 'canvas') {
          const c = el.getContext('2d');
          const draw = c.drawImage;
          c.drawImage = (...args) => {
            seen.push(c.filter);
            return draw(...args);
          };
        }
        return el;
      };
      try {
        paintUnknown(ctx, 100, 40, [{ x: 50, y: 20, rid: 'A' }], [], { radius: 20, blur: 2.4, conf });
      } finally {
        document.createElement = orig;
      }
      return seen.find((f) => f.startsWith('blur'));
    };
    expect(blurs(1)).toBe('blur(2.4px)');
    expect(blurs(0.5)).toBe(`blur(${2.4 + 0.5 * 2.6}px)`);
  });
});
