import { describe, it, expect } from 'vitest';
import { auroraRampRgb } from '../utils/auroraRamp.js';

describe('auroraRamp edges', () => {
  it.each([
    [1, [56, 66, 98]],
    [2, [44, 84, 112]],
    [3, [31, 127, 120]],
    [4, [58, 200, 132]],
    [5, [118, 255, 160]],
  ])('hits stop %s exactly', (score, rgb) => {
    expect(auroraRampRgb(score)).toEqual(rgb);
  });

  it('interpolates halfway between each pair of stops', () => {
    expect(auroraRampRgb(1.5)).toEqual([50, 75, 105]);
    expect(auroraRampRgb(2.5)).toEqual([38, 106, 116]);
    expect(auroraRampRgb(3.5)).toEqual([45, 164, 126]);
    expect(auroraRampRgb(4.5)).toEqual([88, 228, 146]);
  });

  it('clamps outside the range and reads a non-finite score as the bottom stop', () => {
    expect(auroraRampRgb(0)).toEqual([56, 66, 98]);
    expect(auroraRampRgb(9)).toEqual([118, 255, 160]);
    expect(auroraRampRgb(NaN)).toEqual([56, 66, 98]);
    expect(auroraRampRgb(Infinity)).toEqual([56, 66, 98]);
  });
});
