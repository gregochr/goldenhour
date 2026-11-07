import { describe, it, expect } from 'vitest';
import { buildThinCounts, thinRatedCount } from '../utils/mapThinSample.js';
import { heatSpotKey } from '../utils/heatSpots.js';

const sky = (id) => ({ id, name: `P${id}`, skySubject: true });
const poolOf = (n) => Array.from({ length: n }, (_, i) => sky(i + 1));
const statesOf = (pool, ratedCount) => new Map(
  pool.map((s, i) => [heatSpotKey(s), i < ratedCount ? 'rated' : 'unrated']),
);
const WORTH = { tier: 'WORTH_IT' };

describe('thinRatedCount — holes in the scope pool', () => {
  it('skips a null or undefined spot instead of throwing', () => {
    const pool = poolOf(6);
    const states = statesOf(pool, 4);
    expect(thinRatedCount({ verdict: WORTH, states, scopePool: [null, undefined, ...pool] })).toBe(4);
  });

  it('is silent for a pool that is not an array', () => {
    expect(thinRatedCount({ verdict: WORTH, states: new Map(), scopePool: null })).toBeNull();
  });
});

describe('buildThinCounts — holes in the inputs', () => {
  const row = (id, extra = {}) => ({
    id, kind: 'solar', date: '2026-11-02', eventType: 'SUNSET', ...extra,
  });
  const windowStatesFor = (pool, rated) => new Map([['2026-11-02:SUNSET', { states: statesOf(pool, rated) }]]);

  it('skips a null event instead of throwing, and still counts the others', () => {
    const pool = poolOf(6);
    const out = buildThinCounts({
      events: [null, undefined, row('a')],
      verdicts: new Map([['a', WORTH]]),
      windowStates: windowStatesFor(pool, 4),
      scopePool: pool,
    });
    expect([...out.entries()]).toEqual([['a', 4]]);
  });

  it('counts nothing when there are no verdicts at all', () => {
    const pool = poolOf(6);
    const out = buildThinCounts({
      events: [row('a')], verdicts: null, windowStates: windowStatesFor(pool, 4), scopePool: pool,
    });
    expect(out.size).toBe(0);
  });

  it('counts nothing for events that are not an array', () => {
    const pool = poolOf(6);
    const out = buildThinCounts({
      events: null, verdicts: new Map(), windowStates: windowStatesFor(pool, 4), scopePool: pool,
    });
    expect(out.size).toBe(0);
  });

  it('skips night, unserved and past rows', () => {
    const pool = poolOf(6);
    const verdicts = new Map([['n', WORTH], ['u', WORTH], ['p', WORTH], ['s', WORTH]]);
    const out = buildThinCounts({
      events: [row('n', { kind: 'night' }), row('u', { served: false }), row('p', { past: true }), row('s')],
      verdicts,
      windowStates: windowStatesFor(pool, 4),
      scopePool: pool,
    });
    expect([...out.keys()]).toEqual(['s']);
  });
});
