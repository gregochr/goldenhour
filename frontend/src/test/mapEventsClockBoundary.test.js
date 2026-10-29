/**
 * `mapEvents.nextClockBoundary` (performance-review-2026-10 P4e): the next instant the event list's
 * clock-dependent answers can change.
 */
import { describe, it, expect } from 'vitest';
import { nextClockBoundary } from '../utils/mapEvents.js';

const AFTERGLOW = 30 * 60 * 1000;
const at = (iso) => Date.parse(iso);

describe('nextClockBoundary', () => {
  const base = { afterglowMs: AFTERGLOW };

  it('is the earliest future afterglow expiry among lent tides and past windows', () => {
    const tideByWindow = new Map([['a', { eventTime: '2026-01-15T16:12:00' }]]);
    const pastSolarWindows = [{ earliestEventTime: '2026-01-15T08:24:00' }];
    expect(nextClockBoundary({
      ...base, tideByWindow, pastSolarWindows, nowMs: at('2026-01-15T07:00:00Z'),
    })).toBe(at('2026-01-15T08:54:00Z'));
    expect(nextClockBoundary({
      ...base, tideByWindow, pastSolarWindows, nowMs: at('2026-01-15T09:00:00Z'),
    })).toBe(at('2026-01-15T16:42:00Z'));
  });

  it('includes a Big seas window end exactly', () => {
    expect(nextClockBoundary({
      ...base, bigSeas: [{ windowEnd: '2026-01-15T13:00:00Z' }], nowMs: at('2026-01-15T12:00:00Z'),
    })).toBe(at('2026-01-15T13:00:00Z'));
  });

  it('falls back to the next UK civil midnight, in winter and in summer (BST)', () => {
    expect(nextClockBoundary({ ...base, nowMs: at('2026-01-15T12:00:00Z') }))
      .toBeGreaterThanOrEqual(at('2026-01-16T00:00:00Z'));
    expect(nextClockBoundary({ ...base, nowMs: at('2026-01-15T12:00:00Z') }))
      .toBeLessThanOrEqual(at('2026-01-16T00:00:00Z') + 1);
    // 23:30Z on 15 July is 00:30 BST on the 16th: the next midnight is 23:00Z on the 16th.
    const summer = nextClockBoundary({ ...base, nowMs: at('2026-07-15T23:30:00Z') });
    expect(summer).toBeGreaterThanOrEqual(at('2026-07-16T23:00:00Z'));
    expect(summer).toBeLessThanOrEqual(at('2026-07-16T23:00:00Z') + 1);
  });

  it('ignores instants already behind the clock and malformed ones', () => {
    expect(nextClockBoundary({
      ...base,
      pastSolarWindows: [{ earliestEventTime: 'nonsense' }, { earliestEventTime: null }],
      bigSeas: [{ windowEnd: '2026-01-15T01:00:00Z' }],
      nowMs: at('2026-01-15T12:00:00Z'),
    })).toBeGreaterThan(at('2026-01-15T12:00:00Z'));
  });
});
