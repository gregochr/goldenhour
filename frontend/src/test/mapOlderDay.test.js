/**
 * `utils/mapOlderDay.js` — whether the Map tab may fetch one more stored past day, and which
 * (outcome-rating-plan.md §2 #2, §3 O3b step 2). Today is 2026-10-16 throughout, so T-1 is the 15th,
 * T-3 the 13th and the T-4 floor the 12th.
 */
import { describe, it, expect } from 'vitest';
import { MAX_PAST_DAYS, emptyOlderDay, olderDayState } from '../utils/mapOlderDay.js';
import { shiftDateStr } from '../utils/mapDates.js';

const TODAY = '2026-10-16';
const T1 = '2026-10-15';
const T2 = '2026-10-14';
const T3 = '2026-10-13';
const T4 = '2026-10-12';
const FUTURE = ['2026-10-16', '2026-10-17', '2026-10-18'];

describe('shiftDateStr', () => {
  it('steps a calendar date by whole days, across a month and a year', () => {
    expect(shiftDateStr('2026-10-01', -1)).toBe('2026-09-30');
    expect(shiftDateStr('2026-01-01', -1)).toBe('2025-12-31');
    expect(shiftDateStr('2026-02-27', 2)).toBe('2026-03-01');
  });

  it('does not lose or gain a day across the clocks changing (a date, not an instant)', () => {
    expect(shiftDateStr('2026-10-26', -1)).toBe('2026-10-25');
    expect(shiftDateStr('2026-10-25', -1)).toBe('2026-10-24');
    expect(shiftDateStr('2026-03-30', -1)).toBe('2026-03-29');
    expect(shiftDateStr('2026-03-29', -1)).toBe('2026-03-28');
  });
});

describe('olderDayState', () => {
  it('mirrors the server\'s T-4 reach', () => {
    expect(MAX_PAST_DAYS).toBe(4);
  });

  it('offers the day before the oldest date held: T-3 when the list carries T-2 onward', () => {
    expect(olderDayState({ dates: [T2, T1, ...FUTURE], todayStr: TODAY }))
      .toEqual({ canLoadOlder: true, nextOlderDate: T3 });
  });

  it('offers T-1 when only today is held', () => {
    expect(olderDayState({ dates: FUTURE, todayStr: TODAY }))
      .toEqual({ canLoadOlder: true, nextOlderDate: T1 });
  });

  it('never offers today or a future day, even when every held date is in the future', () => {
    const future = ['2026-10-17', '2026-10-18'];
    expect(olderDayState({ dates: future, todayStr: TODAY }))
      .toEqual({ canLoadOlder: true, nextOlderDate: T1 });
  });

  it('steps one day older for each day loaded: T-3 loaded offers T-4', () => {
    expect(olderDayState({ dates: [T2, T1, ...FUTURE], loadedPastDays: [T3], todayStr: TODAY }))
      .toEqual({ canLoadOlder: true, nextOlderDate: T4 });
  });

  it('offers nothing once T-4 is loaded', () => {
    expect(olderDayState({ dates: [T2, T1, ...FUTURE], loadedPastDays: [T3, T4], todayStr: TODAY }))
      .toEqual({ canLoadOlder: false, nextOlderDate: null });
  });

  it('counts a LOADED-EMPTY day: it is in loadedPastDays but in no date list, and still moves the floor', () => {
    // T-3 came back empty, so `dates` still starts at T-2 — without the loaded set the Load row would
    // offer T-3 again forever.
    expect(olderDayState({ dates: [T2, T1, ...FUTURE], loadedPastDays: [T3], todayStr: TODAY }).nextOlderDate)
      .toBe(T4);
    expect(olderDayState({ dates: [T2, T1, ...FUTURE], loadedPastDays: [T3, T4], todayStr: TODAY }).canLoadOlder)
      .toBe(false);
  });

  it('offers nothing older than T-4 whatever is held (a held date older than the floor included)', () => {
    expect(olderDayState({ dates: ['2026-10-10', ...FUTURE], todayStr: TODAY }).canLoadOlder).toBe(false);
    expect(olderDayState({ dates: [T4, ...FUTURE], todayStr: TODAY }).canLoadOlder).toBe(false);
  });

  it('never returns a date older than the floor, for any held set within it', () => {
    for (let held = 0; held <= 6; held += 1) {
      const dates = [shiftDateStr(TODAY, -held), ...FUTURE];
      const { canLoadOlder, nextOlderDate } = olderDayState({ dates, todayStr: TODAY });
      if (canLoadOlder) expect(nextOlderDate >= shiftDateStr(TODAY, -MAX_PAST_DAYS)).toBe(true);
      else expect(nextOlderDate).toBeNull();
    }
  });

  it('stands down while the clock is rewound', () => {
    expect(olderDayState({ dates: [T2, T1, ...FUTURE], todayStr: TODAY, rewound: true }))
      .toEqual({ canLoadOlder: false, nextOlderDate: null });
  });

  it('offers nothing with no date to step back from, or no today', () => {
    expect(olderDayState({ dates: [], todayStr: TODAY }).canLoadOlder).toBe(false);
    expect(olderDayState({ dates: [T2, T1], todayStr: '' }).canLoadOlder).toBe(false);
    expect(olderDayState({ dates: undefined, todayStr: TODAY }).canLoadOlder).toBe(false);
  });
});

describe('emptyOlderDay (the day the menus say has no stored forecast)', () => {
  it('names the most recently loaded day when it came back empty', () => {
    expect(emptyOlderDay({ loadedPastDays: [T3], emptyPastDays: [T3] })).toBe(T3);
  });

  it('is the OLDEST loaded day, because the Load row steps newest to oldest', () => {
    expect(emptyOlderDay({ loadedPastDays: [T3, T4], emptyPastDays: [T4] })).toBe(T4);
    expect(emptyOlderDay({ loadedPastDays: [T4, T3], emptyPastDays: [T3, T4] })).toBe(T4);
  });

  it('goes quiet once a later (older) day loads WITH rows, even if an earlier one was empty', () => {
    expect(emptyOlderDay({ loadedPastDays: [T3, T4], emptyPastDays: [T3] })).toBeNull();
  });

  it('is null when nothing is loaded, when the loaded day had rows, or with no lists at all', () => {
    expect(emptyOlderDay({ loadedPastDays: [], emptyPastDays: [] })).toBeNull();
    expect(emptyOlderDay({ loadedPastDays: [T3], emptyPastDays: [] })).toBeNull();
    expect(emptyOlderDay({})).toBeNull();
  });

  it('ignores an empty day that is not among the loaded ones', () => {
    expect(emptyOlderDay({ loadedPastDays: [T3], emptyPastDays: [T4] })).toBeNull();
  });
});
