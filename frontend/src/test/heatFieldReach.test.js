import { beforeEach, describe, it, expect } from 'vitest';
import {
  REACH_DESAT, REACH_FADE, bbox, drawGeo, field, load,
} from '../utils/heatField.js';
import { installCanvasStub, PATH_METHODS } from './heatFieldCanvasStub.js';

/**
 * The reach split's drawing half (reach-field plan): {@code drawGeo}'s opt-in {@code beyond} layer and
 * {@code field()}'s opt-in {@code desat}. Absent, neither may change a single call or pixel.
 */

const stub = installCanvasStub((c) => ({ fillStyle: c.fillStyle, globalAlpha: c.globalAlpha }));

function canvasWithContext(w = 200, h = 140) {
  const cv = document.createElement('canvas');
  cv.width = w;
  cv.height = h;
  return { cv, ctx: cv.getContext('2d') };
}

const names = (ctx) => ctx.calls.map((c) => c.name).filter((n) => !PATH_METHODS.includes(n));
const maxAlpha = (img) => {
  let m = 0;
  for (let k = 3; k < img.data.length; k += 4) m = Math.max(m, img.data[k]);
  return m;
};
const putImages = () => stub.allContexts.flatMap((c) => c.calls).filter((c) => c.name === 'putImageData');
const drawnMaxAlpha = (draw) => maxAlpha(stub.contexts.get(draw.args[0]).calls
  .find((c) => c.name === 'putImageData').args[0]);

const WITHIN = { lat: 54.5, lng: -2.5, r: [4], rid: 'north', id: 1 };
const BEYOND = { lat: 55.2, lng: -1.6, r: [5], rid: 'north', id: 2 };
const SPOTS = [WITHIN];
const FIT = bbox([WITHIN, BEYOND]);

describe('field() desat', () => {
  const PTS = [{ x: 40, y: 40, sc: 4, rid: 'a' }, { x: 60, y: 50, sc: 2, rid: 'a' }];
  const rgbSpread = (f) => {
    let best = 0;
    let spread = 0;
    for (let k = 0; k < f.img.data.length; k += 4) {
      if (f.img.data[k + 3] > best) {
        best = f.img.data[k + 3];
        spread = Math.max(f.img.data[k], f.img.data[k + 1], f.img.data[k + 2])
          - Math.min(f.img.data[k], f.img.data[k + 1], f.img.data[k + 2]);
      }
    }
    return spread;
  };

  it('is identical to today when absent or zero', () => {
    const base = field(PTS, 100, 100, { radius: 30, grid: 6 });
    const zero = field(PTS, 100, 100, { radius: 30, grid: 6, desat: 0 });
    expect(Array.from(zero.img.data)).toEqual(Array.from(base.img.data));
  });

  it('greys the colour and leaves alpha alone', () => {
    const base = field(PTS, 100, 100, { radius: 30, grid: 6 });
    const grey = field(PTS, 100, 100, { radius: 30, grid: 6, desat: REACH_DESAT });
    expect(rgbSpread(grey)).toBeLessThan(rgbSpread(base));
    expect(maxAlpha(grey.img)).toBe(maxAlpha(base.img));
  });
});

describe('drawGeo beyond layer', () => {
  beforeEach(async () => {
    await load();
  });

  it('makes exactly today\'s calls when beyond is absent or undefined', () => {
    const a = canvasWithContext();
    const b = canvasWithContext();
    drawGeo(a.cv, 200, 140, SPOTS, 0, { fit: FIT });
    drawGeo(b.cv, 200, 140, SPOTS, 0, { fit: FIT, beyond: undefined });
    expect(names(b.ctx)).toEqual(names(a.ctx));
  });

  it('paints the faded field first, at alpha x 0.3, and blits it at the default .92', () => {
    drawGeo(canvasWithContext().cv, 200, 140, SPOTS, 0, { fit: FIT });
    const plainMax = maxAlpha(putImages()[0].args[0]);
    stub.allContexts.length = 0;
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, { fit: FIT, beyond: { points: [BEYOND] } });
    const draws = ctx.calls.filter((c) => c.name === 'drawImage');
    expect(draws).toHaveLength(2);
    const [faded, full] = draws.map(drawnMaxAlpha);
    expect(faded).toBeGreaterThan(0);
    expect(faded).toBeLessThanOrEqual(Math.ceil(206 * REACH_FADE));
    expect(full).toBe(plainMax);
    // The fade is in the alpha, not stacked on the blit's opacity.
    expect(draws[0].globalAlpha).toBeCloseTo(0.92, 5);
    expect(draws[1].globalAlpha).toBeCloseTo(0.92, 5);
  });

  it('does not carry a host opacity into the beyond field\'s blit', () => {
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, { fit: FIT, opacity: 0.8, beyond: { points: [BEYOND] } });
    const draws = ctx.calls.filter((c) => c.name === 'drawImage');
    expect(draws[0].globalAlpha).toBeCloseTo(0.92, 5);
  });

  it('gives the beyond field no bloom', () => {
    const bloom = { bloom: true, bloomFrom: 3, bloomA: 190, bloomBlur: 2.4 };
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, { fit: FIT, ...bloom, beyond: { points: [BEYOND] } });
    // within: field + bloom, beyond: field alone
    expect(ctx.calls.filter((c) => c.name === 'drawImage')).toHaveLength(3);
  });

  it('fades the beyond hatches by 0.3 and leaves the within hatches at full strength', () => {
    const U_WITHIN = { lat: 54.0, lng: -3.2, rid: 'north', id: 3 };
    const U_BEYOND = { lat: 55.6, lng: -1.2, rid: 'north', id: 4 };
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, {
      fit: FIT, radius: 20, unrated: [U_WITHIN], beyond: { points: [], unrated: [U_BEYOND] },
    });
    const alphas = ctx.calls.filter((c) => c.name === 'drawImage').map((c) => c.globalAlpha);
    expect(alphas[0]).toBeCloseTo(REACH_FADE, 5);
    expect(alphas[1]).toBe(1);
  });

  it('multiplies the hatch fade into a host opacity and fades the beyond stood hatch too', () => {
    const S_BEYOND = { lat: 55.6, lng: -1.2, rid: 'north', id: 4 };
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, {
      fit: FIT, radius: 20, opacity: 0.5, beyond: { points: [], stood: [S_BEYOND] },
    });
    const alphas = ctx.calls.filter((c) => c.name === 'drawImage').map((c) => c.globalAlpha);
    expect(alphas[0]).toBeCloseTo(0.5 * REACH_FADE, 5);
  });

  it('masks the within hatch by within rated points only, the beyond hatch by all rated points', () => {
    const U = { lat: 55.2, lng: -1.6, rid: 'north', id: 9 };
    const firstMask = () => maxAlpha(putImages()[0].args[0]);
    // Within unrated place sitting on a rated BEYOND place: the beyond place must not hide the hatch.
    drawGeo(canvasWithContext().cv, 200, 140, [], 0, {
      fit: FIT, radius: 20, unrated: [U], beyond: { points: [BEYOND] },
    });
    const withBeyondRated = firstMask();
    stub.allContexts.length = 0;
    drawGeo(canvasWithContext().cv, 200, 140, [BEYOND], 0, { fit: FIT, radius: 20, unrated: [U] });
    const withWithinRated = firstMask();
    expect(withBeyondRated).toBeGreaterThan(withWithinRated);
    stub.allContexts.length = 0;
    // Beyond unrated place on a rated WITHIN place: all rated points hide it.
    drawGeo(canvasWithContext().cv, 200, 140, [BEYOND], 0, {
      fit: FIT, radius: 20, beyond: { points: [], unrated: [U] },
    });
    const hiddenByWithin = firstMask();
    stub.allContexts.length = 0;
    drawGeo(canvasWithContext().cv, 200, 140, [], 0, {
      fit: FIT, radius: 20, beyond: { points: [], unrated: [U] },
    });
    expect(firstMask()).toBeGreaterThan(hiddenByWithin);
  });

  it('hides a beyond hatch behind a rated BEYOND point too', () => {
    const U = { lat: 55.2, lng: -1.6, rid: 'north', id: 9 };
    drawGeo(canvasWithContext().cv, 200, 140, [], 0, {
      fit: FIT, radius: 20, beyond: { points: [BEYOND], unrated: [U] },
    });
    const hidden = maxAlpha(putImages()[0].args[0]);
    stub.allContexts.length = 0;
    drawGeo(canvasWithContext().cv, 200, 140, [], 0, {
      fit: FIT, radius: 20, beyond: { points: [], unrated: [U] },
    });
    expect(maxAlpha(putImages()[0].args[0])).toBeGreaterThan(hidden);
  });

  it('shows a focused all-beyond region in the faded layer while the within pass goes blank', () => {
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, [{ ...WITHIN, rid: 'south' }], 0, {
      fit: FIT, focus: 'north', beyond: { points: [BEYOND] },
    });
    const [faded, within] = ctx.calls.filter((c) => c.name === 'drawImage').map(drawnMaxAlpha);
    expect(faded).toBeGreaterThan(0);
    expect(faded).toBeLessThanOrEqual(Math.ceil(238 * REACH_FADE));
    expect(within).toBeLessThan(faded);
  });

  it('blends the beyond stoodField into the faded layer only', () => {
    const { cv, ctx } = canvasWithContext();
    drawGeo(cv, 200, 140, SPOTS, 0, {
      fit: FIT,
      beyond: { points: [BEYOND], stoodField: [{ lat: 55.0, lng: -2.0, rid: 'north' }] },
    });
    expect(ctx.calls.filter((c) => c.name === 'drawImage')).toHaveLength(2);
  });
});
