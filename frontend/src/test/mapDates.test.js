/**
 * Tests for the map's two date questions — "what day is it" (the UK calendar) and "which night are
 * we in" (not a calendar question at all).
 *
 * ⚠️ TIMEZONE IS PINNED, DELIBERATELY. Nothing in this repo pins TZ: this Mac runs Europe/London
 * and GitHub's runners run UTC, so an unpinned date test is two different tests. Half the
 * assertions here turn on a UTC-vs-UK disagreement that exists only under BST, so on a UTC runner
 * they would silently pass while proving nothing. Verified to survive `TZ=UTC` in the environment.
 * Node re-reads this on assignment, and nothing imported here reads the zone at import time, so the
 * ES-module hoisting of the imports below does not defeat it.
 *
 * This file pins the UK zone, so it can show the UTC defect but NOT the browser-zone one — a
 * UK-pinned file cannot tell "the UK calendar" from "the local calendar". `mapDatesAbroad.test.js`
 * is the counterpart that separates them.
 */
process.env.TZ = 'Europe/London';

import { describe, it, expect, vi, afterEach } from 'vitest';
import { ukDateStr, ukDateStrOffset, ukDayOffset, ukHour, resolveAuroraNight } from '../utils/mapDates.js';

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

describe('ukDateStr', () => {
  it('reads the UK date, not the UTC date, in the hour after UK midnight under BST', () => {
    // The original defect: App.jsx and DateStrip used toISOString().slice(0, 10), which at this
    // instant returns 2026-08-13 — so the strip labelled yesterday's chip "Today".
    freeze(BST_SMALL_HOURS);

    expect(ukDateStr()).toBe('2026-08-14');
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-08-13');
  });

  it('agrees with UTC in GMT, which is why the UTC basis looked correct for half the year', () => {
    freeze(GMT_SMALL_HOURS);

    expect(ukDateStr()).toBe('2026-01-13');
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-01-13');
  });

  it('reads midday, where no calendar boundary is in play', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(ukDateStr()).toBe('2026-08-13');
  });

  it('takes an injected instant in preference to the clock', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(ukDateStr(new Date('2026-12-25T12:00:00Z'))).toBe('2026-12-25');
  });
});

describe('ukHour', () => {
  it('returns 0 at UK midnight, not 24', () => {
    // The `hourCycle: 'h23'` claim, which is the whole reason that option is spelled out. Under
    // `hour12: false` some implementations render midnight as "24", and every caller compares the
    // result with `>=` against a daytime threshold — so a 24 would read as "the event has passed"
    // for the one hour of the day when it certainly has not.
    freeze(BST_SMALL_HOURS); // 00:30 BST on the 14th

    expect(ukHour()).toBe(0);
  });

  it('reads the UK wall clock, an hour ahead of the UTC hour under BST', () => {
    freeze(BST_SMALL_HOURS);

    expect(ukHour()).toBe(0);
    expect(new Date().getUTCHours()).toBe(23);
  });

  it('agrees with the UTC hour in GMT, which is why the UTC basis looked correct for half the year', () => {
    freeze(GMT_SMALL_HOURS);

    expect(ukHour()).toBe(23);
    expect(new Date().getUTCHours()).toBe(23);
  });

  it('returns a finite integer rather than NaN, whatever the formatter emits', () => {
    // Guards the `formatToParts` read. Parsing the formatted STRING is what fails silently: a
    // locale literal beside the number gives NaN, and `NaN >= 12` is false, so every caller would
    // quietly decide nothing had passed instead of throwing.
    freeze('2026-08-13T14:00:00Z');

    expect(Number.isInteger(ukHour())).toBe(true);
    expect(ukHour()).toBe(15);
  });

  it('reads 23 at the last hour of a UK day', () => {
    freeze('2026-08-13T22:30:00Z'); // 23:30 BST

    expect(ukHour()).toBe(23);
  });

  it('takes an injected instant in preference to the clock', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(ukHour(new Date('2026-08-13T20:00:00Z'))).toBe(21);
  });
});

describe('ukDateStrOffset', () => {
  it('steps forward one day', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(ukDateStrOffset(1)).toBe('2026-08-14');
  });

  it('steps backward one day', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(ukDateStrOffset(-1)).toBe('2026-08-12');
  });

  it('returns today for an offset of zero', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(ukDateStrOffset(0)).toBe('2026-08-13');
  });

  it('crosses a month boundary', () => {
    freeze('2026-08-31T12:00:00Z');

    expect(ukDateStrOffset(1)).toBe('2026-09-01');
  });

  it('crosses a year boundary', () => {
    freeze('2026-12-31T12:00:00Z');

    expect(ukDateStrOffset(1)).toBe('2027-01-01');
  });

  it('steps a whole day across the 25-hour day the clocks go back', () => {
    // 2026-10-25 is the last Sunday in October: BST ends and the local day is 25 hours long. Adding
    // 24h of milliseconds to the instant lands at 23:30 on the SAME date — measured, not assumed.
    freeze('2026-10-24T23:30:00Z'); // 00:30 BST on the 25th

    expect(ukDateStr()).toBe('2026-10-25');
    expect(ukDateStrOffset(1)).toBe('2026-10-26');
  });

  it('steps a whole day across the 23-hour day the clocks go forward', () => {
    // 2026-03-29, the last Sunday in March: BST begins and the local day is 23 hours long.
    freeze('2026-03-28T23:30:00Z');

    expect(ukDateStr()).toBe('2026-03-28');
    expect(ukDateStrOffset(1)).toBe('2026-03-29');
  });
});

describe('ukDayOffset', () => {
  it('reads zero for today, one for tomorrow, minus one for yesterday', () => {
    freeze('2026-08-13T12:00:00Z');

    expect(ukDayOffset('2026-08-13')).toBe(0);
    expect(ukDayOffset('2026-08-14')).toBe(1);
    expect(ukDayOffset('2026-08-12')).toBe(-1);
  });

  it('measures against the UK date in the hour after UK midnight, not the UTC one', () => {
    // The label defect this closes: on the UTC basis the 13th read as "Today" while the UK was
    // already on the 14th, so a chip and a topic card could both claim today.
    freeze(BST_SMALL_HOURS);

    expect(ukDayOffset('2026-08-14')).toBe(0);
    expect(ukDayOffset('2026-08-13')).toBe(-1);
  });

  it('counts whole days across a DST boundary rather than 24-hour blocks', () => {
    freeze('2026-10-24T23:30:00Z'); // 00:30 BST on the 25th, a 25-hour day

    expect(ukDayOffset('2026-10-26')).toBe(1);
  });

  it('spans a month boundary', () => {
    freeze('2026-08-31T12:00:00Z');

    expect(ukDayOffset('2026-09-02')).toBe(2);
  });
});

describe('resolveAuroraNight', () => {
  it('serves the backend night even when it is not today — the whole point of the field', () => {
    // 02:00 UK. The night in progress began at dusk on the 13th, so its results are stored under
    // the 13th while the calendar says the 14th. A resolver that answered "today" here is exactly
    // what opened the map on a date the run never scored.
    freeze('2026-08-14T01:00:00Z');

    expect(ukDateStr()).toBe('2026-08-14');
    expect(resolveAuroraNight({ currentNightDate: '2026-08-13' })).toBe('2026-08-13');
  });

  it('serves the backend night when it does equal today, after dusk', () => {
    freeze('2026-08-13T21:00:00Z');

    expect(resolveAuroraNight({ currentNightDate: '2026-08-13' })).toBe('2026-08-13');
  });

  it('falls back to the UK date when there is no status at all', () => {
    // A LITE user gets null from the status endpoint, and so does a failed fetch. A calendar date is
    // the wrong answer for a night, but it is the same wrong answer the map gave before the field
    // existed, so the degrade is "no worse than before" rather than a guess.
    freeze(BST_SMALL_HOURS);

    expect(resolveAuroraNight(null)).toBe('2026-08-14');
  });

  it('falls back to the UK date when the payload predates the field', () => {
    // A browser holding a cached bundle against an older backend: the key is simply absent.
    freeze(BST_SMALL_HOURS);

    expect(resolveAuroraNight({ level: 'MODERATE' })).toBe('2026-08-14');
  });

  it('falls back to the UK date when the field is present but null', () => {
    freeze(BST_SMALL_HOURS);

    expect(resolveAuroraNight({ level: 'MODERATE', currentNightDate: null })).toBe('2026-08-14');
  });

  it('does not fall back to the UTC date, which would be a day behind under BST', () => {
    // Guards the fallback specifically: swapping ukDateStr for toISOString here would return
    // 2026-08-13 and reintroduce the two-calendar split on the aurora path.
    freeze(BST_SMALL_HOURS);

    expect(resolveAuroraNight(null)).not.toBe('2026-08-13');
  });
});
