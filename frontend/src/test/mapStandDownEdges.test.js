import { describe, it, expect } from 'vitest';
import { standDownFooterCount, standDownRegions, stoodPointSetsOf } from '../utils/mapStandDown.js';

const stoodPlace = (rid, reason, key) => ({ rid, reason, key, lat: 55, lng: -1.5 });

describe('stoodPointSetsOf — the evidence gate and entry guard', () => {
  it('draws nothing for a solar window whose places are all unrated, even with a stood list', () => {
    const windowStates = new Map([[
      '2026-10-24:SUNSET',
      { stood: [stoodPlace('North', 'HIGH_CLOUD', '#1')], states: new Map([['#1', 'unrated'], ['#2', 'na']]) },
    ]]);
    expect(stoodPointSetsOf(windowStates).size).toBe(0);
  });

  it('keeps a solar window once a place is stood, and one once a place is rated', () => {
    const stood = [stoodPlace('North', 'HIGH_CLOUD', '#1')];
    const windowStates = new Map([
      ['2026-10-24:SUNSET', { stood, states: new Map([['#1', 'stood']]) }],
      ['2026-10-25:SUNRISE', { stood, states: new Map([['#2', 'rated']]) }],
    ]);
    const out = stoodPointSetsOf(windowStates);
    expect([...out.keys()]).toEqual(['2026-10-24:SUNSET', '2026-10-25:SUNRISE']);
    expect(out.get('2026-10-24:SUNSET')).toEqual(stood);
  });

  it('skips a solar window whose entry is null or undefined without throwing', () => {
    const windowStates = new Map([['2026-10-24:SUNSET', null], ['2026-10-25:SUNRISE', undefined]]);
    expect(() => stoodPointSetsOf(windowStates)).not.toThrow();
    expect(stoodPointSetsOf(windowStates).size).toBe(0);
  });

  it('skips an entry that has no states map', () => {
    const windowStates = new Map([['2026-10-24:SUNSET', { stood: [stoodPlace('North', 'HIGH_CLOUD', '#1')] }]]);
    expect(stoodPointSetsOf(windowStates).size).toBe(0);
  });
});

describe('standDownRegions — places with no region', () => {
  it('ignores a stood place that has no rid and returns no region for it', () => {
    const out = standDownRegions({
      stood: [{ rid: undefined, reason: 'HIGH_CLOUD', key: '#1' }, { rid: '', reason: 'HIGH_CLOUD', key: '#2' }],
      spots: [],
      states: new Map(),
      served: false,
      regionVerdictIndex: null,
      date: '2026-10-24',
      targetType: 'SUNSET',
    });
    expect(out).toEqual([]);
  });

  it('labels only the region that has a rid when some places have none', () => {
    const out = standDownRegions({
      stood: [{ rid: undefined, reason: 'PRECIPITATION' }, stoodPlace('North', 'HIGH_CLOUD', '#1')],
      spots: [],
      states: new Map(),
      served: false,
      regionVerdictIndex: null,
      date: '2026-10-24',
      targetType: 'SUNSET',
    });
    expect(out.map((r) => r.rid)).toEqual(['North']);
    expect(out[0].points).toHaveLength(1);
  });
});

describe('standDownFooterCount — a missing pool', () => {
  it('counts nothing for a null pool, even when a stood key looks like a nameless, id-less spot', () => {
    // heatSpotKey of a bare string reads "@undefined": a pool fallback holding any element would join it.
    expect(standDownFooterCount(null, [{ key: '@undefined', reason: 'HIGH_CLOUD' }])).toBe(0);
    expect(standDownFooterCount(undefined, [{ key: '@undefined', reason: 'HIGH_CLOUD' }])).toBe(0);
  });
});
