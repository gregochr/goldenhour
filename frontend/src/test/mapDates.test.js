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
import {
  ukDateStr, ukDateStrOffset, ukDayOffset, ukHour, resolveAuroraNight, resolveMapDate,
} from '../utils/mapDates.js';

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

/**
 * `resolveMapDate` — which date the map is showing.
 *
 * <p>Every case turns on one rule: a date already over is never the answer, on ANY branch. The
 * shape this replaced put that rule on the LAST branch only, which is what made it nearly
 * unreachable — the two preferred branches were guarded by a bare membership test that a past date
 * passes, and `GET /api/forecast` serves `today-2` onward. Reached, it put a stale run's stars on a
 * map whose own window control said "No forecast" (2026-09-07).
 */
describe('resolveMapDate', () => {
  const TWO_DAYS_AGO = '2026-08-12';
  const YESTERDAY = '2026-08-13';
  const TODAY = '2026-08-14';
  const TOMORROW = '2026-08-15';

  const call = (over = {}) => resolveMapDate({
    selectedDate: null, autoDate: null, allDates: [YESTERDAY, TODAY, TOMORROW], todayStr: TODAY,
    ...over,
  });

  describe('the reader\'s own choice', () => {
    it('wins when it is today-forward and in the domain', () => {
      expect(call({ selectedDate: TOMORROW })).toBe(TOMORROW);
    });

    it('is REFUSED when it has gone past — a choice made yesterday is not a choice for today', () => {
      // The tab left open overnight: `selectedDate` is still yesterday and passes the membership
      // test the old shape relied on. Falls through to today-forward instead.
      expect(call({ selectedDate: YESTERDAY })).toBe(TODAY);
    });

    it('is refused when it is not in the forecast domain at all', () => {
      expect(call({ selectedDate: '2026-08-20' })).toBe(TODAY);
    });

    it('is honoured ON the boundary — today is not "past"', () => {
      // ⚠️ The threshold's own edge, and the auto-selection is set to something ELSE so the
      // assertion can only pass through the selected branch. With `>` instead of `>=` the reader's
      // explicit choice of today loses to the auto-selection, which no test caught until this one.
      expect(call({ selectedDate: TODAY, autoDate: TOMORROW })).toBe(TODAY);
    });
  });

  describe('the night in progress', () => {
    // ⚠️ A night runs dusk-to-dawn, so between UK midnight and dawn the night in progress is
    // YESTERDAY's date, and `App.handleAuroraViewOnMap` sets it deliberately so the viewline lands
    // on the night the banner is about. The first cut of the never-past clamp refused it and
    // silently undid that fix; `MapView`'s auto-jump cannot recover it.
    it('is honoured when the selection NAMED a night, even though it is yesterday', () => {
      expect(call({ selectedDate: YESTERDAY, selectedIsNight: true, nightDate: YESTERDAY }))
        .toBe(YESTERDAY);
    });

    // ⚠️ The exemption keys on PROVENANCE, never on the value — the second defect Codex found.
    it('is REFUSED for a solar selection that merely lands on the same date', () => {
      // The overnight case, and it is not a corner: a reader picks yesterday evening's SUNSET and
      // leaves the tab open past UK midnight. `currentNightDate` is still yesterday until dawn, so
      // the stale solar pick equals it exactly. Matching on value held the map on a day that was
      // over — and with the solar-row gate live that is a persistent "No forecast" blank.
      expect(call({ selectedDate: YESTERDAY, selectedIsNight: false, nightDate: YESTERDAY }))
        .toBe(TODAY);
    });

    it('defaults to refusing when provenance was never supplied', () => {
      // Every caller that is not the aurora route omits the flag; the safe answer must be the
      // default rather than something each of them has to remember.
      expect(call({ selectedDate: YESTERDAY, nightDate: YESTERDAY })).toBe(TODAY);
    });

    it('does not license any OTHER past date, even for a night selection', () => {
      expect(call({ selectedDate: TWO_DAYS_AGO, selectedIsNight: true, nightDate: YESTERDAY }))
        .toBe(TODAY);
    });

    it('must still be in the forecast domain', () => {
      expect(call({
        selectedDate: YESTERDAY, selectedIsNight: true, nightDate: YESTERDAY, allDates: [TODAY],
      })).toBe(TODAY);
    });

    it('is NOT an escape hatch for the auto-selection — that is a calendar answer', () => {
      // Scoped to the explicit choice on purpose: `computeAutoSelection` names a solar event on a
      // calendar day and has no business naming a night.
      expect(call({ autoDate: YESTERDAY, nightDate: YESTERDAY })).toBe(TODAY);
    });
  });

  describe('the auto-selection', () => {
    it('wins over the fallback when it is today-forward and in the domain', () => {
      expect(call({ autoDate: TOMORROW })).toBe(TOMORROW);
    });

    it('is REFUSED when it has gone past — it is frozen at mount, so this is the common route', () => {
      // ⚠️ The reachable one. `computeAutoSelection` reads the clock inside a memo keyed on the
      // location roster, and `useForecasts` fetches once on mount — so across UK midnight this
      // holds yesterday's answer for the rest of the session and never self-corrects.
      expect(call({ autoDate: YESTERDAY })).toBe(TODAY);
    });

    it('loses to a usable explicit choice', () => {
      expect(call({ selectedDate: TOMORROW, autoDate: TODAY })).toBe(TOMORROW);
    });

    it('is consulted when the explicit choice is the stale one', () => {
      expect(call({ selectedDate: YESTERDAY, autoDate: TOMORROW })).toBe(TOMORROW);
    });
  });

  describe('the fallback', () => {
    it('takes today when the forecast covers it', () => {
      expect(call()).toBe(TODAY);
    });

    it('takes the nearest FUTURE date when today itself is missing', () => {
      expect(call({ allDates: [YESTERDAY, TOMORROW] })).toBe(TOMORROW);
    });

    it('never returns a past date, even when every date it was given is past', () => {
      // Exactly the list a reader gets on a day nothing ran. The old fallback returned YESTERDAY.
      const chosen = call({ allDates: [TWO_DAYS_AGO, YESTERDAY] });
      expect(chosen).toBe(TODAY);
      expect(chosen).not.toBe(YESTERDAY);
    });

    it('falls back to today even when the only date is a single past one', () => {
      expect(call({ allDates: [YESTERDAY] })).toBe(TODAY);
    });

    it('is null for an empty list — a domain is not invented out of nothing', () => {
      // `App` withholds the Map pane entirely on an empty list; returning a date would let a
      // caller mount a map over no forecast domain at all.
      expect(call({ allDates: [] })).toBeNull();
      expect(call({ allDates: undefined })).toBeNull();
    });

    it('is null, not undefined, on an empty list with a stale choice standing', () => {
      // The JSDoc promises `?string`; `MapView`'s `if (!date …)` early return tolerates either,
      // so only a test keeps the contract honest.
      expect(call({ allDates: [], selectedDate: YESTERDAY, autoDate: YESTERDAY })).toBeNull();
    });
  });

  it('reads today off the UK calendar when App supplies it — the two must agree', () => {
    // Not a restatement of `ukDateStr`'s own tests: it pins that a caller passing the UK civil
    // date gets that same string back, so the map cannot open on a date the masthead disagrees
    // with in the BST small hours.
    vi.useFakeTimers();
    vi.setSystemTime(new Date(BST_SMALL_HOURS));
    const uk = ukDateStr();
    expect(uk).toBe('2026-08-14');
    expect(resolveMapDate({
      selectedDate: null, autoDate: null, allDates: ['2026-08-12', '2026-08-13'], todayStr: uk,
    })).toBe('2026-08-14');
    vi.useRealTimers();
  });
});
