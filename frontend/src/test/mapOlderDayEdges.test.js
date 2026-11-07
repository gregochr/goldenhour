import { describe, it, expect } from 'vitest';
import { MAX_PAST_DAYS, emptyOlderDay, olderDayState } from '../utils/mapOlderDay.js';

const TODAY = '2026-11-10';

describe('olderDayState — when a next older day is offered', () => {
  it('offers the day before the oldest known date', () => {
    expect(olderDayState({ dates: ['2026-11-09', '2026-11-10', '2026-11-11'], todayStr: TODAY }))
      .toEqual({ canLoadOlder: true, nextOlderDate: '2026-11-08' });
  });

  it('counts already-loaded older days, empty ones included', () => {
    expect(olderDayState({ dates: ['2026-11-09'], loadedPastDays: ['2026-11-08', '2026-11-07'], todayStr: TODAY }))
      .toEqual({ canLoadOlder: true, nextOlderDate: '2026-11-06' });
    expect(olderDayState({ dates: ['2026-11-09'], loadedPastDays: new Set(['2026-11-08']), todayStr: TODAY }).nextOlderDate)
      .toBe('2026-11-07');
  });

  it('steps back from today, never to it, when the forecast starts after today', () => {
    expect(olderDayState({ dates: ['2026-11-10', '2026-11-12'], todayStr: TODAY }).nextOlderDate).toBe('2026-11-09');
    expect(olderDayState({ dates: ['2026-11-15'], todayStr: TODAY }).nextOlderDate).toBe('2026-11-09');
  });

  it('stops at the floor: the last allowed day is offered, the one beyond is not', () => {
    expect(MAX_PAST_DAYS).toBe(4);
    expect(olderDayState({ dates: ['2026-11-07'], todayStr: TODAY }))
      .toEqual({ canLoadOlder: true, nextOlderDate: '2026-11-06' });
    expect(olderDayState({ dates: ['2026-11-06'], todayStr: TODAY }))
      .toEqual({ canLoadOlder: false, nextOlderDate: null });
  });

  it('offers nothing while rewound, without a today, or with no date to step back from', () => {
    const none = { canLoadOlder: false, nextOlderDate: null };
    expect(olderDayState({ dates: ['2026-11-09'], todayStr: TODAY, rewound: true })).toEqual(none);
    expect(olderDayState({ dates: ['2026-11-09'], todayStr: '' })).toEqual(none);
    expect(olderDayState({ dates: [], todayStr: TODAY })).toEqual(none);
    expect(olderDayState({ dates: null, todayStr: TODAY })).toEqual(none);
    expect(olderDayState({ dates: [null, ''], todayStr: TODAY })).toEqual(none);
  });
});

describe('emptyOlderDay — only the oldest loaded day speaks', () => {
  it('names the oldest loaded day when it came back empty', () => {
    expect(emptyOlderDay({ loadedPastDays: ['2026-11-08', '2026-11-07'], emptyPastDays: ['2026-11-07'] }))
      .toBe('2026-11-07');
  });

  it('is null when only a newer loaded day was empty, or the oldest had rows', () => {
    expect(emptyOlderDay({ loadedPastDays: ['2026-11-08', '2026-11-07'], emptyPastDays: ['2026-11-08'] })).toBeNull();
    expect(emptyOlderDay({ loadedPastDays: ['2026-11-08'], emptyPastDays: [] })).toBeNull();
  });

  it('is null with nothing loaded, and tolerates omitted lists', () => {
    expect(emptyOlderDay({ loadedPastDays: [], emptyPastDays: ['2026-11-07'] })).toBeNull();
    expect(emptyOlderDay({})).toBeNull();
    expect(emptyOlderDay({ loadedPastDays: ['2026-11-08'] })).toBeNull();
  });
});
