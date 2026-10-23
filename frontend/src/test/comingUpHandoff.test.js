import { describe, it, expect } from 'vitest';
import {
  laneDates, lastPlanDateStr, PLAN_OWNED_DAYS,
} from '../utils/comingUpHandoff.js';

const TODAY = '2026-08-08';

describe('lastPlanDateStr', () => {
  it('is three days ahead of today — a four-day window', () => {
    expect(lastPlanDateStr(TODAY)).toBe('2026-08-11');
  });

  it('agrees with PLAN_OWNED_DAYS', () => {
    expect(PLAN_OWNED_DAYS).toBe(4);
  });

  it('crosses a month boundary correctly', () => {
    expect(lastPlanDateStr('2026-08-30')).toBe('2026-09-02');
  });
});

describe('laneDates — the one helper the handoff\'s type set and the lanes share', () => {
  it('gives one item per window key, in the order topicWindowKeys produced them', () => {
    expect(laneDates({ type: 'AURORA', date: '2026-08-08', eventType: 'NIGHT' })).toEqual([
      { date: '2026-08-08', window: 'SUNSET', key: '2026-08-08:SUNSET' },
      { date: '2026-08-09', window: 'SUNRISE', key: '2026-08-09:SUNRISE' },
    ]);
  });

  it('gives a day-scoped tide both windows whatever its eventType', () => {
    expect(laneDates({ type: 'SPRING_TIDE', date: '2026-08-08', eventType: null }).map((i) => i.key))
      .toEqual(['2026-08-08:SUNRISE', '2026-08-08:SUNSET']);
  });

  it('gives an unanchored topic its own date with no window and no key', () => {
    expect(laneDates({ type: 'STORM_SURGE', date: '2026-08-08', eventType: null })).toEqual([
      { date: '2026-08-08', window: null, key: null },
    ]);
  });

  it('gives an undated topic nothing', () => {
    expect(laneDates({ type: 'DUST' })).toEqual([]);
    expect(laneDates(null)).toEqual([]);
  });
});
