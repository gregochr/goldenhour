import { describe, it, expect } from 'vitest';
import {
  isNightType, NIGHT_TYPE, nightRequestsOf, nightRowFor,
} from '../utils/askNights.js';
import { astroRow, auroraRow, nightPick, pick } from './askFixtures.js';

/**
 * Ask's night kinds on the client (`docs/engineering/ask-night-plan.md` §2.1, §2.6): the kind is a pick's
 * `targetType`, the payloads a card joins to are asked for by the DATES the picks name, and nothing here
 * works out which night is "tonight".
 */

describe('isNightType — Ask\'s own window kinds', () => {
  it('is true for ASTRO and AURORA and for nothing else', () => {
    expect(isNightType('ASTRO')).toBe(true);
    expect(isNightType('AURORA')).toBe(true);
    expect(isNightType('SUNRISE')).toBe(false);
    expect(isNightType('SUNSET')).toBe(false);
    expect(isNightType('astro')).toBe(false);
    expect(isNightType(null)).toBe(false);
    expect(isNightType(undefined)).toBe(false);
  });

  it('exposes the two kinds under the names the wire uses', () => {
    expect(NIGHT_TYPE).toEqual({ ASTRO: 'ASTRO', AURORA: 'AURORA' });
  });
});

describe('nightRequestsOf — the dates the live answer\'s picks name', () => {
  it('names each night date once, sorted, per kind', () => {
    const requests = nightRequestsOf([
      nightPick({ rank: 1, date: '2026-10-06', targetType: 'ASTRO' }),
      nightPick({ rank: 2, date: '2026-10-05', targetType: 'ASTRO' }),
      nightPick({ rank: 3, date: '2026-10-06', targetType: 'ASTRO', locationId: 131 }),
      nightPick({ rank: 4, date: '2026-10-06', targetType: 'AURORA' }),
    ]);

    expect(requests).toEqual({ astro: ['2026-10-05', '2026-10-06'], aurora: ['2026-10-06'] });
  });

  it('asks for nothing for an answer of solar picks only', () => {
    expect(nightRequestsOf([pick(), pick({ rank: 2, targetType: 'SUNRISE' })]))
      .toEqual({ astro: [], aurora: [] });
  });

  it('reads the date off the pick, not off a window id — it never computes "tonight"', () => {
    // The running night before dawn is YESTERDAY's date: whatever the pick says is what is asked for.
    expect(nightRequestsOf([nightPick({ date: '2026-10-04', windowId: '2026-10-04_astro' })]))
      .toEqual({ astro: ['2026-10-04'], aurora: [] });
  });

  it('ignores a pick with no usable date, and a malformed list', () => {
    expect(nightRequestsOf([nightPick({ date: undefined }), nightPick({ date: '' }), null]))
      .toEqual({ astro: [], aurora: [] });
    expect(nightRequestsOf(null)).toEqual({ astro: [], aurora: [] });
    expect(nightRequestsOf(undefined)).toEqual({ astro: [], aurora: [] });
  });
});

describe('nightRowFor — a pick\'s row in one night\'s payload', () => {
  const byDate = new Map([
    ['2026-10-05', [astroRow(), astroRow({ locationId: 131, locationName: 'Elsewhere' })]],
    ['2026-10-06', [auroraRow()]],
  ]);

  it('finds the location on the pick\'s own date', () => {
    expect(nightRowFor(byDate, '2026-10-05', 131).locationName).toBe('Elsewhere');
  });

  it('is null for a night that was not fetched, a place not on the night, and a payload that is not a Map', () => {
    expect(nightRowFor(byDate, '2026-10-07', 130)).toBeNull();
    expect(nightRowFor(byDate, '2026-10-05', 999)).toBeNull();
    expect(nightRowFor(null, '2026-10-05', 130)).toBeNull();
    expect(nightRowFor({}, '2026-10-05', 130)).toBeNull();
  });

  it('is null when the night\'s entry is not a list', () => {
    expect(nightRowFor(new Map([['2026-10-05', null]]), '2026-10-05', 130)).toBeNull();
  });
});
