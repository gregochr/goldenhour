import { describe, it, expect } from 'vitest';
import {
  AURORA_STOPS, AURORA_BLOOM, AURORA_RAMP,
  auroraRampRgb, auroraRampHex, auroraRampGradientCss,
} from '../utils/auroraRamp.js';

/**
 * The aurora ramp is ported from the design bundle (stop 3 deliberately darkened for AA, plan §4 #9),
 * so these tests pin the
 * literals themselves (a mistyped channel is invisible on a blurred field) and the interpolation
 * between them.
 */
describe('auroraRamp', () => {
  const SPEC = [
    [1, [56, 66, 98], '#384262'],
    [2, [44, 84, 112], '#2C5470'],
    [3, [31, 127, 120], '#1F7F78'],
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
    expect(auroraRampRgb(2.5)).toEqual([38, 106, 116]);
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
      .toBe('linear-gradient(90deg,#384262,#2C5470 25%,#1F7F78 50%,#3AC884 75%,#76FFA0)');
  });

  it('keeps the gradient string in step with AURORA_STOPS', () => {
    const hexes = auroraRampGradientCss().match(/#[0-9A-F]{6}/g);
    expect(hexes).toEqual([1, 2, 3, 4, 5].map((s) => auroraRampHex(s)));
  });

  // The repo's contrast helper is private (windowFirstSpots.js), so the ratio is computed here with
  // the standard WCAG 2.2 formula. The pin numeral picks white or the dark badge ink per fill
  // (`readableInkOn`); the dark ink is #0F172A.
  const lum = (rgb) => {
    const [r, g, b] = rgb.map((v) => {
      const c = v / 255;
      return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const whiteOn = (rgb) => 1.05 / (lum(rgb) + 0.05);
  const darkOn = (rgb) => (lum(rgb) + 0.05) / (lum([15, 23, 42]) + 0.05);

  it('white ink clears AA 4.5:1 on the 3\u2605 stop with margin (was 4.39 on the spec teal)', () => {
    expect(whiteOn([34, 134, 126])).toBeLessThan(4.5);
    expect(whiteOn(auroraRampRgb(3))).toBeGreaterThanOrEqual(4.7);
  });

  it('white ink clears AA on the dark stops, and the best of white/dark ink clears it on every stop and the 2.5/3.5/4.5 midpoints', () => {
    for (const s of [1, 2, 3]) expect(whiteOn(auroraRampRgb(s))).toBeGreaterThanOrEqual(4.5);
    for (const s of [1, 2, 2.5, 3, 3.5, 4, 4.5, 5]) {
      const rgb = auroraRampRgb(s);
      expect(Math.max(whiteOn(rgb), darkOn(rgb))).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('keeps luminance strictly rising with score', () => {
    const l = [1, 2, 3, 4, 5].map((s) => lum(auroraRampRgb(s)));
    for (let i = 1; i < l.length; i += 1) expect(l[i]).toBeGreaterThan(l[i - 1]);
  });

  it('carries the bloom and the one-prop object', () => {
    expect(AURORA_BLOOM).toEqual({ rgb: [120, 255, 170], from: 3.4, a: 150 });
    expect(AURORA_RAMP.rgb).toBe(auroraRampRgb);
    expect(AURORA_RAMP.hex).toBe(auroraRampHex);
    expect(AURORA_RAMP.gradientCss).toBe(auroraRampGradientCss);
  });
});
