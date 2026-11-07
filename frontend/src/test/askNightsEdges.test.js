import { describe, it, expect } from 'vitest';
import { nightRequestsOf, nightRowFor } from '../utils/askNights.js';

describe('nightRowFor — holes in a night\'s rows', () => {
  it('skips a null or undefined row and still finds the place', () => {
    const row = { locationId: 3, name: 'Kielder' };
    const byDate = new Map([['2026-11-02', [null, undefined, { locationId: 2 }, row]]]);
    expect(nightRowFor(byDate, '2026-11-02', 3)).toBe(row);
  });

  it('is null for a missing night, a night that is not a list, a place not on it, or no map', () => {
    const byDate = new Map([['2026-11-02', [{ locationId: 2 }]], ['2026-11-03', 'nope']]);
    expect(nightRowFor(byDate, '2026-11-04', 2)).toBeNull();
    expect(nightRowFor(byDate, '2026-11-03', 2)).toBeNull();
    expect(nightRowFor(byDate, '2026-11-02', 9)).toBeNull();
    expect(nightRowFor(null, '2026-11-02', 2)).toBeNull();
    expect(nightRowFor({}, '2026-11-02', 2)).toBeNull();
  });

  it('matches the location id strictly', () => {
    const byDate = new Map([['2026-11-02', [{ locationId: 3 }]]]);
    expect(nightRowFor(byDate, '2026-11-02', '3')).toBeNull();
  });
});

describe('nightRequestsOf', () => {
  it('names each night date once, sorted, split by kind, ignoring solar and dateless picks', () => {
    expect(nightRequestsOf([
      { date: '2026-11-04', targetType: 'ASTRO' },
      { date: '2026-11-02', targetType: 'ASTRO' },
      { date: '2026-11-04', targetType: 'ASTRO' },
      { date: '2026-11-03', targetType: 'AURORA' },
      { date: '2026-11-03', targetType: 'SUNSET' },
      { date: '', targetType: 'ASTRO' },
      { targetType: 'AURORA' },
      null,
    ])).toEqual({ astro: ['2026-11-02', '2026-11-04'], aurora: ['2026-11-03'] });
  });

  it('asks for nothing from a list that is not a list', () => {
    expect(nightRequestsOf(null)).toEqual({ astro: [], aurora: [] });
  });
});
