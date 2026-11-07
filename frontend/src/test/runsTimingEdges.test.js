import { describe, it, expect } from 'vitest';
import {
  alignedBinWidth, durationAxis, histogram, kdeCurve, niceScale, rollingMedian,
} from '../utils/runsTiming.js';

// Edge pins for the Timing chart geometry: bin alignment, the KDE curve's exact figures (checked by hand against
// Silverman's rule narrowed by 0.8), the rolling median's empty window and the axis helpers' unit boundaries.

describe('alignedBinWidth and histogram', () => {
  it('falls back to the widest bin for a span no width can hold in 24 bins', () => {
    expect(alignedBinWidth(0, 1_000_000)).toBe(3600);
  });

  it('gives a huge span a histogram of the widest bins', () => {
    const result = histogram([0, 1_000_000]);
    expect(result.width).toBe(3600);
    expect(result.start).toBe(0);
    expect(result.bins).toHaveLength(278);
    expect(result.bins[0].count).toBe(1);
    expect(result.bins[277].count).toBe(1);
    expect(result.bins.reduce((sum, bin) => sum + bin.count, 0)).toBe(2);
  });

  it('places each value in the bin it falls in, counting from the aligned start rather than from zero', () => {
    const { width, start, bins } = histogram([100, 101, 130, 160]);
    expect(width).toBe(3);
    expect(start).toBe(99);
    expect(bins).toHaveLength(21);
    const counts = bins.map((bin) => bin.count);
    expect(counts[0]).toBe(2);
    expect(counts[10]).toBe(1);
    expect(counts[20]).toBe(1);
    expect(counts.reduce((a, b) => a + b, 0)).toBe(4);
    expect(bins[0]).toEqual({ from: 99, to: 102, count: 2 });
    expect(bins[20]).toEqual({ from: 159, to: 162, count: 1 });
  });

  it('puts a value that sits exactly on a bin boundary in the bin that starts there', () => {
    const { bins } = histogram([60, 63, 66]);
    expect(bins.map((bin) => bin.count)).toEqual([1, 1, 1]);
    expect(bins[0].from).toBe(60);
  });
});

describe('kdeCurve', () => {
  it('draws no curve for fewer than two values, but a curve for exactly two', () => {
    expect(kdeCurve([], 0, 10, 3)).toEqual([]);
    expect(kdeCurve([5], 0, 10, 3)).toEqual([]);
    expect(kdeCurve([5, 15], 0, 20, 3)).toHaveLength(141);
  });

  it('is exact for three spread values (mean, spread and bandwidth all matter)', () => {
    const curve = kdeCurve([100, 160, 220], 100, 220, 30);
    expect(curve).toHaveLength(141);
    expect(curve[0].x).toBe(100);
    expect(curve[70].x).toBe(160);
    expect(curve[140].x).toBe(220);
    expect(curve[0].y).toBeCloseTo(0.4305656008, 8);
    expect(curve[70].y).toBeCloseTo(0.5011397431, 8);
    expect(curve[140].y).toBeCloseTo(0.4305656008, 8);
  });

  it('is symmetric about the mean of a symmetric list', () => {
    const curve = kdeCurve([100, 160, 220], 100, 220, 30);
    expect(curve[10].y).toBeCloseTo(curve[130].y, 10);
    expect(curve[35].y).toBeCloseTo(curve[105].y, 10);
  });

  it('uses the bin width as the spread when every value is the same', () => {
    const curve = kdeCurve([100, 100], 90, 110, 30);
    expect(curve[0].y).toBeCloseTo(0.9760631274, 8);
    expect(curve[70].y).toBeCloseTo(1.0808121256, 8);
    expect(curve[140].y).toBeCloseTo(0.9760631274, 8);
  });

  it('scales with the number of values and the bin width, so the area matches the histogram', () => {
    const narrow = kdeCurve([100, 160, 220], 100, 220, 30);
    const wide = kdeCurve([100, 160, 220], 100, 220, 60);
    expect(wide[70].y / narrow[70].y).toBeCloseTo(2, 10);
  });
});

describe('rollingMedian', () => {
  it('takes the median of the last N values, fewer at the start', () => {
    expect(rollingMedian([5, 1, 9, 3], 3)).toEqual([5, 3, 5, 3]);
  });

  it('gives null, not a number, for an empty window', () => {
    expect(rollingMedian([4, 8, 6], 0)).toEqual([null, null, null]);
  });

  it('gives nothing for no values', () => {
    expect(rollingMedian([])).toEqual([]);
  });
});

describe('niceScale and durationAxis', () => {
  it('keeps a step when the fraction lands exactly on a nice factor, within the 1e-9 tolerance', () => {
    const raw = 1 + 1e-9;
    expect(niceScale(raw * 4, 4)).toEqual({ max: 4, step: 1 });
  });

  it('steps up to the next nice factor once the fraction exceeds the tolerance', () => {
    expect(niceScale(4 * 1.001, 4).step).toBe(2);
  });

  it('uses the nice step under a minute and the readable tick steps from a minute', () => {
    expect(durationAxis(59)).toEqual({ max: 60, step: 20 });
    expect(durationAxis(60)).toEqual({ max: 60, step: 15 });
  });

  it('never gives a step under a second, or a max under one step', () => {
    expect(durationAxis(1)).toEqual({ max: 1, step: 1 });
    expect(durationAxis(0.2)).toEqual({ max: 1, step: 1 });
  });
});
