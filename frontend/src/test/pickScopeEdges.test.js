import { describe, it, expect } from 'vitest';
import { buildGroundSets, splitGround } from '../utils/pickScope.js';

describe('pickScope edges', () => {
  it('splitGround with a non-array pool yields empty grounds, never a phantom place', () => {
    // A string stand-in would key as "@undefined"; the states hold exactly that key.
    const states = new Map([['@undefined', 'rated']]);
    expect(splitGround({ shaped: true, spots: null, states })).toEqual({ outside: [], allowed: [] });
  });

  it('buildGroundSets with a non-array pool builds no grounds', () => {
    const events = [{ date: '2026-11-08', targetType: 'SUNRISE' }];
    expect(buildGroundSets(null, events, true).size).toBe(0);
  });

  it('buildGroundSets with non-array events builds no grounds', () => {
    const spots = [{ id: 1, name: 'A', lat: 1, lng: 2, rid: 'r', states: ['rated'] }];
    expect(buildGroundSets(spots, null, true).size).toBe(0);
  });

  it('skips a null event, an event with no date and an event with no target type', () => {
    const spots = [{ id: 1, name: 'A', lat: 1, lng: 2, rid: 'r', states: ['rated', 'rated', 'rated', 'rated'] }];
    const events = [
      null,
      { targetType: 'SUNRISE' },
      { date: '2026-11-08' },
      { date: '2026-11-09', targetType: 'SUNSET' },
    ];
    const out = buildGroundSets(spots, events, true);
    expect([...out.keys()]).toEqual(['2026-11-09:SUNSET']);
  });

  it('tolerates a null spot and a spot with no states in the pool', () => {
    const spots = [
      null,
      { id: 2, name: 'Bare', lat: 0, lng: 0, rid: 'r' },
      { id: 3, name: 'C', lat: 3, lng: 4, rid: 'r', states: ['gated'] },
    ];
    const out = buildGroundSets(spots, [{ date: '2026-11-08', targetType: 'SUNRISE' }], true);
    const ground = out.get('2026-11-08:SUNRISE');
    expect(ground.outside.map((p) => p.id)).toEqual([3]);
    expect(ground.allowed).toEqual([]);
  });
});
