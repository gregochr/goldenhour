/**
 * Tests for the map's two date questions — "what day is it" and "which night are we in".
 *
 * ⚠️ TIMEZONE IS PINNED, DELIBERATELY. Nothing in this repo pins TZ: this Mac runs Europe/London
 * and GitHub's runners run UTC, so an unpinned date test is two different tests. Half the
 * assertions here turn on a UTC-vs-local disagreement that exists only under BST, so on a UTC
 * runner they would silently pass while proving nothing. Verified to survive `TZ=UTC` in the
 * environment. Node re-reads this on assignment, and nothing imported here reads the zone at
 * import time, so the ES-module hoisting of the imports below does not defeat it.
 */
process.env.TZ = 'Europe/London';

import { describe, it, expect, vi, afterEach } from 'vitest';
import { localDateStr, localDateStrOffset, resolveAuroraNight } from '../utils/mapDates.js';

/** The hour after UK midnight in BST — UTC still says the 13th, the UK says the 14th. */
const BST_SMALL_HOURS = '2026-08-13T23:30:00Z';
/** The same clock reading in GMT, where the two calendars agree. */
const GMT_SMALL_HOURS = '2026-01-13T23:30:00Z';

function freeze(iso) {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}

afterEach(() => {
  vi.useRealTimers();
});

describe('localDateStr', () => {
  it('reads the UK date, not the UTC date, in the hour after UK midnight under BST', () => {
    // The defect this replaced: App.jsx and DateStrip used toISOString().slice(0, 10), which at
    // this instant returns 2026-08-13 — so the strip labelled yesterday's chip "Today" while
    // MapView and computeAutoSelection, already on the local basis, had moved to the 14th.
    freeze(BST_SMALL_HOURS);

    expect(localDateStr()).toBe('2026-08-14');
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-08-13');
  });

  it('agrees with UTC in GMT, which is why the old basis looked correct for half the year', () => {
    freeze(GMT_SMALL_HOURS);

    expect(localDateStr()).toBe('2026-01-13');
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-01-13');
  });

  it('reads midday, where no calendar boundary is in play', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(localDateStr()).toBe('2026-08-13');
  });

  it('takes an injected instant in preference to the clock', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(localDateStr(new Date('2026-12-25T12:00:00Z'))).toBe('2026-12-25');
  });
});

describe('localDateStrOffset', () => {
  it('steps forward one day', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(localDateStrOffset(1)).toBe('2026-08-14');
  });

  it('steps backward one day', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(localDateStrOffset(-1)).toBe('2026-08-12');
  });

  it('crosses a month boundary', () => {
    freeze('2026-08-31T12:00:00Z');

    expect(localDateStrOffset(1)).toBe('2026-09-01');
  });

  it('steps a whole day across the 25-hour day the clocks go back', () => {
    // 2026-10-25 is the last Sunday in October: BST ends and the local day is 25 hours long. A
    // naive `now.getTime() + 24h` lands at 23:30 on the SAME date — measured, not assumed. Stepping
    // the local calendar fields is what makes this correct.
    freeze('2026-10-24T23:30:00Z'); // 00:30 BST on the 25th

    expect(localDateStr()).toBe('2026-10-25');
    expect(localDateStrOffset(1)).toBe('2026-10-26');
  });

  it('steps a whole day across the 23-hour day the clocks go forward', () => {
    // 2026-03-29, the last Sunday in March: BST begins and the local day is 23 hours long.
    freeze('2026-03-28T23:30:00Z');

    expect(localDateStr()).toBe('2026-03-28');
    expect(localDateStrOffset(1)).toBe('2026-03-29');
  });
});

describe('resolveAuroraNight', () => {
  it('serves the backend night even when it is not today — the whole point of the field', () => {
    // 02:00 UK. The night in progress began at dusk on the 13th, so its results are stored under
    // the 13th while the calendar says the 14th. A resolver that answered "today" here is exactly
    // what opened the map on a date the run never scored.
    freeze('2026-08-14T01:00:00Z');

    expect(localDateStr()).toBe('2026-08-14');
    expect(resolveAuroraNight({ currentNightDate: '2026-08-13' })).toBe('2026-08-13');
  });

  it('serves the backend night when it does equal today, after dusk', () => {
    freeze('2026-08-13T21:00:00Z');

    expect(resolveAuroraNight({ currentNightDate: '2026-08-13' })).toBe('2026-08-13');
  });

  it('falls back to the local date when there is no status at all', () => {
    // A LITE user gets null from the status endpoint, and so does a failed fetch. The fallback is
    // the behaviour this replaced, so the degrade is "no worse than before" rather than a guess.
    freeze(BST_SMALL_HOURS);

    expect(resolveAuroraNight(null)).toBe('2026-08-14');
  });

  it('falls back to the local date when the payload predates the field', () => {
    // A browser holding a cached bundle against an older backend: the key is simply absent.
    freeze(BST_SMALL_HOURS);

    expect(resolveAuroraNight({ level: 'MODERATE' })).toBe('2026-08-14');
  });

  it('falls back to the local date when the field is present but null', () => {
    freeze(BST_SMALL_HOURS);

    expect(resolveAuroraNight({ level: 'MODERATE', currentNightDate: null })).toBe('2026-08-14');
  });

  it('does not fall back to the UTC date, which would be a day behind under BST', () => {
    // Guards the fallback specifically: swapping localDateStr for toISOString here would return
    // 2026-08-13 and reintroduce the two-calendar split on the aurora path.
    freeze(BST_SMALL_HOURS);

    expect(resolveAuroraNight(null)).not.toBe('2026-08-13');
  });
});
