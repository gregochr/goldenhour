import { describe, it, expect } from 'vitest';
import {
  buildSpread, hasSpreadSample, poolWithinReach, spreadBars, spreadRowState, spreadTitle, stoodSpreadTitle,
  unratedBar,
} from '../utils/windowFirstSpread.js';

describe('windowFirstSpread with absent spread or pool', () => {
  it('buildSpread of a non-array is an empty spread', () => {
    expect(buildSpread(null)).toEqual({ total: 0, rated: 0, unrated: 0, counts: [0, 0, 0, 0, 0], max: 0 });
    expect(buildSpread([null, { rating: 3 }]).counts).toEqual([0, 0, 1, 0, 0]);
  });

  it('hasSpreadSample is false for no spread, or one with no fields', () => {
    expect(hasSpreadSample(undefined)).toBe(false);
    expect(hasSpreadSample(null)).toBe(false);
    expect(hasSpreadSample({})).toBe(false);
  });

  it('spreadRowState draws bars for a missing spread (an empty pool)', () => {
    expect(spreadRowState(undefined)).toEqual({ bars: true, text: null, spoken: null });
    expect(spreadRowState({})).toEqual({ bars: true, text: null, spoken: null });
  });

  it('spreadRowState says none rated yet, or N/M, below the gate', () => {
    expect(spreadRowState({ rated: 0, total: 4 })).toEqual({ bars: false, text: 'none rated yet', spoken: 'none rated yet' });
    expect(spreadRowState({ rated: 2, total: 4 })).toEqual({ bars: false, text: '2/4 rated', spoken: '2 of 4 rated' });
  });

  it('spreadBars draws five hairlines for no spread', () => {
    const bars = spreadBars(undefined);
    expect(bars.map((b) => [b.star, b.count, b.filled, b.heightPx])).toEqual([
      [1, 0, false, 1], [2, 0, false, 1], [3, 0, false, 1], [4, 0, false, 1], [5, 0, false, 1],
    ]);
  });

  it('spreadBars scales against the unrated remainder when it is larger than any band', () => {
    const bars = spreadBars({ counts: [1, 0, 0, 0, 0], max: 1, unrated: 26 });
    expect(bars[0].heightPx).toBe(2);
    const wide = spreadBars({ counts: [26, 0, 0, 0, 0], max: 26, unrated: 0 });
    expect(wide[0].heightPx).toBe(13);
  });

  it('spreadBars falls back to a scale of 1 when every count is zero', () => {
    expect(spreadBars({ counts: [0, 0, 0, 0, 0], max: 0, unrated: 0 }).every((b) => b.heightPx === 1)).toBe(true);
    expect(spreadBars({}).length).toBe(5);
  });

  it('unratedBar is null for no spread and sized against the shared scale otherwise', () => {
    expect(unratedBar(undefined)).toBeNull();
    expect(unratedBar({ unrated: 0 })).toBeNull();
    expect(unratedBar({ unrated: 1, max: 100 })).toEqual({ count: 1, heightPx: 4 });
    expect(unratedBar({ unrated: 50, max: 100 })).toEqual({ count: 50, heightPx: 7 });
  });

  it('poolWithinReach is true for a non-array and for an empty pool', () => {
    expect(poolWithinReach(undefined)).toBe(true);
    expect(poolWithinReach([])).toBe(true);
    expect(poolWithinReach([{ driveMinutes: 10 }, null])).toBe(false);
    expect(poolWithinReach([{ driveMinutes: 10 }, { driveMinutes: 0 }])).toBe(true);
  });

  it('spreadTitle words an empty pool by whether reach can be claimed', () => {
    expect(spreadTitle(undefined, true)).toBe('Nothing within reach for this one.');
    expect(spreadTitle({ total: 0 }, false)).toBe('Nothing to show for this one.');
  });

  it('spreadTitle says none rated yet when rated is missing', () => {
    expect(spreadTitle({ total: 1 }, true)).toBe('1 location within reach — none rated yet.');
    expect(spreadTitle({ total: 3, rated: 0 }, false)).toBe('3 locations — none rated yet.');
  });

  it('spreadTitle words a partial sample with its remainder', () => {
    expect(spreadTitle({ total: 6, rated: 2, unrated: 4, counts: [0, 0, 2, 0, 0] }, true))
      .toBe('6 locations within reach — 2 rated · 4 not yet rated');
  });

  it('spreadTitle lists the bands highest first once the sample is big enough', () => {
    expect(spreadTitle({ total: 10, rated: 10, unrated: 0, counts: [1, 2, 3, 2, 2] }, false))
      .toBe('10 locations — 2 at 5★, 2 at 4★, 3 at 3★, 2 at 2★, 1 at 1★');
  });

  it('stoodSpreadTitle works for a missing spread', () => {
    expect(stoodSpreadTitle(undefined, true)).toBe('0 locations within reach — stood down by the weather.');
    expect(stoodSpreadTitle({ total: 1 }, false)).toBe('1 location — stood down by the weather.');
  });
});
