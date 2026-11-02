import { describe, it, expect } from 'vitest';
import {
  binWidth, ceil5, clampOffset, durationAxis, durationTickStep, formatMinutes, histogram, kdeCurve, niceScale,
  replayPlanner, rollingAverage, rollingMedian,
} from '../utils/runsTiming.js';

// The Timing and Cost charts' licensed client derivations (runs-deep-dive-plan.md DD-1): bins, the KDE curve, the
// rolling lines, the planner replay. Pure functions over served numbers.

describe('binWidth', () => {
  it.each([
    [0, 3], [72, 3], [73, 6], [144, 6], [145, 15], [360, 15], [361, 30], [720, 30], [721, 60], [1440, 60],
    [1441, 120], [2880, 120], [2881, 300], [7200, 300], [7201, 600], [14_400, 600], [14_401, 900],
    [21_600, 900], [21_601, 1200], [28_800, 1200], [28_801, 1800], [43_200, 1800], [43_201, 3600],
    [1_000_000, 3600],
  ])('a span of %is gets a %is bin (at most 24 bins)', (span, width) => {
    expect(binWidth(span)).toBe(width);
  });
});

describe('histogram', () => {
  it('has no bins for no values', () => {
    expect(histogram([]).bins).toEqual([]);
  });

  it('puts one value in one bin, aligned to the width', () => {
    const { width, start, bins } = histogram([100]);
    expect(width).toBe(3);
    expect(start).toBe(99);
    expect(bins).toHaveLength(1);
    expect(bins.reduce((n, b) => n + b.count, 0)).toBe(1);
  });

  it('counts every value exactly once, the maximum included', () => {
    const values = [440, 700, 900, 1000, 1100, 1180, 1250, 1400, 2250, 3170];
    const { width, bins } = histogram(values);
    expect(width).toBe(120);
    expect(bins.reduce((n, b) => n + b.count, 0)).toBe(values.length);
    expect(bins.every((b) => b.to - b.from === width)).toBe(true);
    expect(bins[0].from).toBe(360);
  });

  it('keeps a value on a bin boundary in the bin that starts there', () => {
    const { bins } = histogram([0, 3, 6]);
    expect(bins.map((b) => b.count)).toEqual([1, 1, 1]);
  });

  it('never makes more than 25 bins for the widest span it handles well', () => {
    const { bins } = histogram([60, 60 + 72 * 24]);
    expect(bins.length).toBeLessThanOrEqual(25);
  });
});

describe('kdeCurve', () => {
  it('draws no curve for fewer than two values (one has no spread to smooth)', () => {
    expect(kdeCurve([], 0, 100, 3)).toEqual([]);
    expect(kdeCurve([50], 0, 100, 3)).toEqual([]);
  });

  it('is a smooth bump over the values, scaled to counts per bin', () => {
    const values = [100, 110, 120, 130, 140];
    const curve = kdeCurve(values, 40, 200, 10);
    expect(curve).toHaveLength(141);
    expect(curve[0].x).toBe(40);
    expect(curve[140].x).toBe(200);
    const peak = curve.reduce((a, b) => (b.y > a.y ? b : a));
    expect(peak.x).toBeGreaterThan(105);
    expect(peak.x).toBeLessThan(135);
    // The area under it, in counts, is about the number of values.
    const area = curve.reduce((n, p) => n + p.y, 0) * ((200 - 40) / 140) / 10;
    expect(area).toBeGreaterThan(4.5);
    expect(area).toBeLessThan(5.5);
  });

  it('copes with identical values (no spread): a curve, not NaN', () => {
    const curve = kdeCurve([60, 60, 60], 0, 120, 6);
    expect(curve.every((p) => Number.isFinite(p.y))).toBe(true);
    expect(Math.max(...curve.map((p) => p.y))).toBeGreaterThan(0);
  });
});

describe('rollingMedian and rollingAverage', () => {
  it('uses a shrinking window at the start and 7 values after', () => {
    const values = [10, 50, 20, 40, 30, 60, 5, 100];
    expect(rollingMedian(values)).toEqual([10, 30, 20, 30, 30, 35, 30, 40]);
  });

  it('takes the mean of the two middle values for an even window', () => {
    expect(rollingMedian([1, 3])).toEqual([1, 2]);
  });

  it('is empty for nothing', () => {
    expect(rollingMedian([])).toEqual([]);
    expect(rollingAverage([])).toEqual([]);
  });

  it('averages the last 7 values (the cost chart\'s line)', () => {
    const values = [7, 7, 7, 7, 7, 7, 7, 14];
    const avg = rollingAverage(values);
    expect(avg[6]).toBe(7);
    expect(avg[7]).toBe(8);
    expect(rollingAverage([2, 4])).toEqual([2, 3]);
  });
});

describe('axes', () => {
  it('niceScale makes the top a whole number of nice steps', () => {
    expect(niceScale(7.3)).toEqual({ max: 8, step: 2 });
    expect(niceScale(9.2)).toEqual({ max: 10, step: 2.5 });
    expect(niceScale(0)).toEqual({ max: 4, step: 1 });
    expect(niceScale(13)).toEqual({ max: 20, step: 5 });
    expect(niceScale(0.9)).toEqual({ max: 1, step: 0.25 });
  });

  it('durationAxis uses readable steps: seconds under a minute, then minutes', () => {
    expect(durationAxis(40)).toEqual({ max: 40, step: 10 });
    expect(durationAxis(3300).step).toBe(900);
    expect(durationAxis(3300).max).toBe(3600);
  });

  it('durationTickStep keeps the ticks few', () => {
    expect(durationTickStep(3600)).toBe(600);
    expect(durationTickStep(100_000)).toBe(14_400);
  });
});

describe('ceil5, clampOffset, formatMinutes', () => {
  it('rounds up to a multiple of five', () => {
    expect([0, 1, 5, 5.1, 67.5].map(ceil5)).toEqual([0, 5, 5, 10, 70]);
  });

  it('puts a served trigger on the slider', () => {
    expect(clampOffset(250)).toBe(250);
    expect(clampOffset(10)).toBe(20);
    expect(clampOffset(999)).toBe(300);
    expect(clampOffset(252)).toBe(250);
  });

  it('words minutes', () => {
    expect([250, 70, 60, 45, 0].map(formatMinutes)).toEqual(['4h 10m', '1h 10m', '1h', '45m', '0m']);
  });
});

describe('replayPlanner', () => {
  const runs = [600, 1200, 1800, 2400].map((durationSeconds) => ({ durationSeconds }));
  const base = {
    runs, offsetMinutes: 60, bufferMinutes: 30, p90Seconds: 2250, medianSeconds: 1500, todaySunset: '2026-11-02T16:49:00Z',
  };

  it('puts each run at -offset + duration from sunset and counts those in time', () => {
    const r = replayPlanner(base);
    expect(r.points.map((p) => p.offsetFromSunset)).toEqual([-50, -40, -30, -20]);
    // In time means at or before -30: 10, 20 and 30 minutes long are; the 40-minute one is not.
    expect(r.points.map((p) => p.inTime)).toEqual([true, true, true, false]);
    expect(r.inTime).toBe(3);
    expect(r.total).toBe(4);
    expect(r.share).toBe(0.75);
  });

  it('suggests ceil5(buffer + served p90) and never recomputes the percentile', () => {
    expect(replayPlanner(base).suggestionMinutes).toBe(70);
    expect(replayPlanner({ ...base, bufferMinutes: 60 }).suggestionMinutes).toBe(100);
    expect(replayPlanner({ ...base, bufferMinutes: 0 }).suggestionMinutes).toBe(40);
  });

  it('gives today\'s clock times from the served sunset, offset, median and p90', () => {
    const r = replayPlanner({ ...base, offsetMinutes: 250 });
    expect(r.start.toISOString()).toBe('2026-11-02T12:39:00.000Z');
    expect(r.sunset.toISOString()).toBe('2026-11-02T16:49:00.000Z');
    expect(r.typical.toISOString()).toBe('2026-11-02T13:04:00.000Z');
    expect(r.nineInTen.toISOString()).toBe('2026-11-02T13:16:30.000Z');
  });

  it('copes with no runs: no share, no points, and a suggestion only if a p90 was served', () => {
    const r = replayPlanner({ ...base, runs: [], p90Seconds: null, medianSeconds: null });
    expect(r.total).toBe(0);
    expect(r.share).toBeNull();
    expect(r.suggestionMinutes).toBeNull();
    expect(r.typical).toBeNull();
    expect(r.nineInTen).toBeNull();
  });

  it('has no suggestion for one run (the server serves no p90)', () => {
    const r = replayPlanner({ ...base, runs: [{ durationSeconds: 900 }], p90Seconds: null });
    expect(r.suggestionMinutes).toBeNull();
    expect(r.nineInTen).toBeNull();
    expect(r.total).toBe(1);
  });

  it('counts every run late when the start is too close to sunset', () => {
    const r = replayPlanner({ ...base, offsetMinutes: 20, bufferMinutes: 30 });
    expect(r.inTime).toBe(0);
    expect(r.share).toBe(0);
  });

  it('counts a run that lands exactly on the buffer as in time (buffer = sunset: ready at sunset)', () => {
    const r = replayPlanner({ ...base, runs: [{ durationSeconds: 3600 }], offsetMinutes: 60, bufferMinutes: 0 });
    expect(r.points[0].offsetFromSunset).toBe(0);
    expect(r.points[0].inTime).toBe(true);
  });

  it('serves no clock times without a sunset', () => {
    const r = replayPlanner({ ...base, todaySunset: null });
    expect(r.start).toBeNull();
    expect(r.sunset).toBeNull();
    expect(r.nineInTen).toBeNull();
  });
});
