import { describe, it, expect } from 'vitest';
import {
  AURORA_STOPS, AURORA_BLOOM, AURORA_TOGGLE_SWATCH, AURORA_RAMP,
  auroraRampRgb, auroraRampHex, auroraRampGradientCss,
} from '../utils/auroraRamp.js';

/**
 * The aurora ramp is ported byte for byte from the design bundle, so these tests pin the
 * literals themselves (a mistyped channel is invisible on a blurred field) and the interpolation
 * between them.
 */
describe('auroraRamp', () => {
  const SPEC = [
    [1, [56, 66, 98], '#384262'],
    [2, [44, 84, 112], '#2C5470'],
    [3, [34, 134, 126], '#22867E'],
    [4, [58, 200, 132], '#3AC884'],
    [5, [118, 255, 160], '#76FFA0'],
  ];

  it.each(SPEC)('stop %i returns its exact RGB', (score, rgb) => {
    expect(auroraRampRgb(score)).toEqual(rgb);
  });

  it.each(SPEC)('stop %i has the spec hex', (score, rgb, hex) => {
    expect(auroraRampHex(score)).toBe(hex);
  });

  it('keeps the exported stop table equal to the spec', () => {
    expect(AURORA_STOPS).toEqual(SPEC.map(([s, c]) => [s, c]));
  });

  it('interpolates 2.5 to the midpoint of stops 2 and 3', () => {
    expect(auroraRampRgb(2.5)).toEqual([39, 109, 119]);
  });

  it('clamps below 1 and above 5', () => {
    expect(auroraRampRgb(0)).toEqual([56, 66, 98]);
    expect(auroraRampRgb(7)).toEqual([118, 255, 160]);
  });

  it('resolves a non-finite score to the bottom stop, never the top', () => {
    expect(auroraRampRgb(NaN)).toEqual([56, 66, 98]);
    expect(auroraRampRgb(undefined)).toEqual([56, 66, 98]);
  });

  it('returns a fresh array, never the module storage', () => {
    const c = auroraRampRgb(1);
    c[0] = 0;
    expect(AURORA_STOPS[0][1][0]).toBe(56);
    expect(auroraRampRgb(1)[0]).toBe(56);
  });

  it('writes the gradient byte for byte', () => {
    expect(auroraRampGradientCss())
      .toBe('linear-gradient(90deg,#384262,#2C5470 25%,#22867E 50%,#3AC884 75%,#76FFA0)');
  });

  it('keeps the gradient string in step with AURORA_STOPS', () => {
    const hexes = auroraRampGradientCss().match(/#[0-9A-F]{6}/g);
    expect(hexes).toEqual([1, 2, 3, 4, 5].map((s) => auroraRampHex(s)));
  });

  it('carries the bloom, the toggle swatch and the one-prop object', () => {
    expect(AURORA_BLOOM).toEqual({ rgb: [120, 255, 170], from: 3.4, a: 150 });
    expect(AURORA_TOGGLE_SWATCH).toBe('linear-gradient(90deg,#22867E,#76FFA0)');
    expect(AURORA_RAMP.rgb).toBe(auroraRampRgb);
    expect(AURORA_RAMP.hex).toBe(auroraRampHex);
    expect(AURORA_RAMP.gradientCss).toBe(auroraRampGradientCss);
  });
});
