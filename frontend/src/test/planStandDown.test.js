import { describe, it, expect } from 'vitest';
import { buildHeatSpots, buildHeatWindowStates } from '../utils/heatSpots.js';
import { stoodPhrase, stoodPointSetsOf } from '../utils/mapStandDown.js';

/**
 * The Plan tab's stood ground (big-seas-plan.md F4): which windows hand their weather-stood
 * places to the thumbnails and the row map. Pure filters over the windows' states: the same gates
 * the Map applies (solar only, the evidence gate, tide excluded).
 */

const DATE = '2026-10-24';

const loc = (id, name, regionName, lat = 55 + id / 10) => ({
  id, name, lat, lon: -1.5, regionName, locationType: ['LANDSCAPE'],
});
const rowOf = (id, name, fields, targetType = 'SUNSET') => ({
  locationId: id, locationName: name, date: DATE, targetType, rating: null, ...fields,
});

const LOCATIONS = [loc(1, 'A', 'North'), loc(2, 'B', 'North'), loc(3, 'C', 'South')];

const setsFor = (rows, targetType = 'SUNSET') => {
  const events = [{ date: DATE, targetType }];
  return stoodPointSetsOf(buildHeatWindowStates(buildHeatSpots(LOCATIONS, rows, events), events));
};

describe('stoodPointSetsOf — the Plan tab\'s stood ground', () => {
  it('hands a window its weather-stood places and leaves a rated neighbour out of the list', () => {
    const sets = setsFor([
      rowOf(1, 'A', { triageReason: 'HIGH_CLOUD' }),
      rowOf(2, 'B', { rating: 4 }),
    ]);
    expect(sets.get(`${DATE}:SUNSET`).map((p) => [p.key, p.reason])).toEqual([['#1', 'HIGH_CLOUD']]);
  });

  it('still hatches a WHOLE-window stand-down: evidence is a stood place, never a rating', () => {
    const sets = setsFor([
      rowOf(1, 'A', { triageReason: 'PRECIPITATION' }),
      rowOf(2, 'B', { triageReason: 'HIGH_CLOUD' }),
      rowOf(3, 'C', { triageReason: 'LOW_VISIBILITY' }),
    ]);
    expect(sets.get(`${DATE}:SUNSET`)).toHaveLength(3);
  });

  it('leaves TIDE_MISALIGNED out, and the window with it when that was the only reason', () => {
    const mixed = setsFor([
      rowOf(1, 'A', { triageReason: 'TIDE_MISALIGNED' }),
      rowOf(2, 'B', { triageReason: 'HIGH_CLOUD' }),
    ]);
    expect(mixed.get(`${DATE}:SUNSET`).map((p) => p.key)).toEqual(['#2']);

    const tideOnly = setsFor([
      rowOf(1, 'A', { triageReason: 'TIDE_MISALIGNED' }),
      rowOf(3, 'C', { rating: 3 }),
    ]);
    expect(tideOnly.has(`${DATE}:SUNSET`)).toBe(false);
  });

  it.each(['ASTRO', 'AURORA', 'NIGHT'])('draws nothing on a %s window', (targetType) => {
    const sets = setsFor([rowOf(1, 'A', { triageReason: 'HIGH_CLOUD' }, targetType)], targetType);
    expect(sets.size).toBe(0);
  });

  it('draws nothing without evidence (no rows at all, or only unrated places)', () => {
    expect(setsFor([]).size).toBe(0);
    expect(setsFor([rowOf(1, 'A', {})]).size).toBe(0);
  });

  it('tolerates a missing map', () => {
    expect(stoodPointSetsOf(null).size).toBe(0);
    expect(stoodPointSetsOf(undefined).size).toBe(0);
  });
});

describe('stoodPhrase — the thumbnail\'s spoken clause', () => {
  it('says N places, singular for one, nothing for none or nonsense', () => {
    expect(stoodPhrase(3)).toEqual(['3 locations stood down by the weather']);
    expect(stoodPhrase(1)).toEqual(['1 location stood down by the weather']);
    expect(stoodPhrase(0)).toEqual([]);
    expect(stoodPhrase(undefined)).toEqual([]);
    expect(stoodPhrase(-2)).toEqual([]);
  });
});
