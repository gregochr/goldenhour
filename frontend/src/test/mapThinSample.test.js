import { describe, it, expect } from 'vitest';
import { buildThinCounts, thinRatedCount } from '../utils/mapThinSample.js';
import { buildHeatSpots, buildHeatWindowStates, heatSpotKey } from '../utils/heatSpots.js';
import { SPREAD_MIN_RATED_COUNT } from '../utils/windowFirstSpread.js';

const sky = (id) => ({ id, name: `P${id}`, skySubject: true });
const poolOf = (n) => Array.from({ length: n }, (_, i) => sky(i + 1));
const statesOf = (pool, ratedCount) => new Map(
  pool.map((s, i) => [heatSpotKey(s), i < ratedCount ? 'rated' : 'unrated']),
);
const WORTH = { tier: 'WORTH_IT' };

describe('thinRatedCount — the Map pill\'s count over the SCOPE pool', () => {
  it('counts the rated sky places when the pool fails the spread floors', () => {
    const pool = poolOf(83);
    expect(thinRatedCount({ verdict: WORTH, states: statesOf(pool, 6), scopePool: pool })).toBe(6);
  });

  it('is silent when the pool clears both floors', () => {
    const pool = poolOf(10);
    expect(thinRatedCount({ verdict: WORTH, states: statesOf(pool, 5), scopePool: pool })).toBeNull();
  });

  it('fails on the count floor alone, and on the coverage floor alone', () => {
    const small = poolOf(6);
    // 4 of 6 is over half but under the absolute floor.
    expect(SPREAD_MIN_RATED_COUNT).toBeGreaterThan(4);
    expect(thinRatedCount({ verdict: WORTH, states: statesOf(small, 4), scopePool: small })).toBe(4);
    const wide = poolOf(40);
    // 6 of 40 clears the absolute floor and fails the half.
    expect(thinRatedCount({ verdict: WORTH, states: statesOf(wide, 6), scopePool: wide })).toBe(6);
  });

  it('never counts a wood or hide, in the numerator or the denominator', () => {
    // 5 rated of 5 sky places clears the gate; ten woods beside them must not reopen it.
    const pool = [...poolOf(5), ...Array.from({ length: 10 }, (_, i) => ({ id: 100 + i, name: `W${i}`, skySubject: false }))];
    expect(thinRatedCount({ verdict: WORTH, states: statesOf(pool, 5), scopePool: pool })).toBeNull();
  });

  it('is silent for every word that is not a rated one', () => {
    const pool = poolOf(83);
    const states = statesOf(pool, 6);
    ['UNRATED', 'STAND_DOWN', 'AWAITING'].forEach((tier) => {
      expect(thinRatedCount({ verdict: { tier }, states, scopePool: pool })).toBeNull();
    });
    expect(thinRatedCount({ verdict: { tier: 'MAYBE' }, states, scopePool: pool })).toBe(6);
    expect(thinRatedCount({ verdict: null, states, scopePool: pool })).toBeNull();
  });

  it('is silent with no rated place — no "0 rated" beside a verdict from further away', () => {
    const pool = poolOf(83);
    expect(thinRatedCount({ verdict: WORTH, states: statesOf(pool, 0), scopePool: pool })).toBeNull();
  });

  it('is silent with no states for the window', () => {
    expect(thinRatedCount({ verdict: WORTH, states: null, scopePool: poolOf(8) })).toBeNull();
  });
});

describe('buildThinCounts — one count per served solar row', () => {
  const pool = poolOf(83);
  const windowStates = new Map([['2026-10-24:SUNRISE', { states: statesOf(pool, 6) }]]);
  const verdicts = new Map([['solar:2026-10-24:SUNRISE', WORTH]]);
  const events = [{
    id: 'solar:2026-10-24:SUNRISE', kind: 'solar', date: '2026-10-24', eventType: 'SUNRISE', served: true,
  }];

  it('keys the count by row id', () => {
    expect([...buildThinCounts({
      events, verdicts, windowStates, scopePool: pool,
    })]).toEqual([['solar:2026-10-24:SUNRISE', 6]]);
  });

  it('skips night rows, filler rows and past rows', () => {
    const rows = [
      { ...events[0], kind: 'astro' },
      { ...events[0], served: false },
      { ...events[0], past: true },
    ];
    expect(buildThinCounts({
      events: rows, verdicts, windowStates, scopePool: pool,
    }).size).toBe(0);
  });

  it('is empty without window states', () => {
    expect(buildThinCounts({
      events, verdicts, windowStates: null, scopePool: pool,
    }).size).toBe(0);
  });
});

describe('buildHeatWindowStates — what each window knows, keyed like the point sets', () => {
  const locations = [
    { id: 1, name: 'Rated', lat: 55, lon: -1, regionName: 'NE', locationType: ['LANDSCAPE'] },
    { id: 2, name: 'Stood', lat: 55.1, lon: -1, regionName: 'NE', locationType: ['LANDSCAPE'] },
    { id: 3, name: 'Gap', lat: 55.2, lon: -1, regionName: 'NE', locationType: ['LANDSCAPE'] },
    { id: 4, name: 'Wood', lat: 55.3, lon: -1, regionName: 'NE', locationType: ['WOODLAND'] },
  ];
  const events = [{ date: '2026-10-24', targetType: 'SUNRISE' }];
  const rows = [
    { locationId: 1, locationName: 'Rated', date: '2026-10-24', targetType: 'SUNRISE', rating: 4 },
    {
      locationId: 2, locationName: 'Stood', date: '2026-10-24', targetType: 'SUNRISE', rating: null, triageReason: 'rain',
    },
    { locationId: 4, locationName: 'Wood', date: '2026-10-24', targetType: 'SUNRISE', rating: 5 },
  ];
  const entry = buildHeatWindowStates(buildHeatSpots(locations, rows, events), events)
    .get('2026-10-24:SUNRISE');

  it('hatches only a sky place nobody looked at', () => {
    expect(entry.unrated.map((p) => p.name)).toEqual(['Gap']);
  });

  it('records each place\'s state by join key, a wood as not applicable', () => {
    expect(Object.fromEntries(entry.states)).toEqual({
      '#1': 'rated', '#2': 'stood', '#3': 'unrated', '#4': 'na',
    });
  });
});
