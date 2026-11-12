import { describe, it, expect } from 'vitest';
import { stoodFieldPointsFor } from '../utils/mapStandDown.js';
import { STOOD_FIELD_SCORE, buildHeatSpots, stoodPointsFor } from '../utils/heatSpots.js';
import { pastWindowBest } from '../utils/mapEvents.js';

describe('stoodFieldPointsFor (stood-down-field plan D1)', () => {
  const place = (reason) => ({ lat: 55, lng: -1.5, rid: 'North', reason, key: `#${reason}` });

  it('keeps every weather stand-down, GENERIC and null-reason included, and drops the tide one', () => {
    const list = [place('HIGH_CLOUD'), place('PRECIPITATION'), place('LOW_VISIBILITY'), place('GENERIC'),
      place(null), place('TIDE_MISALIGNED')];
    const out = stoodFieldPointsFor(list);
    expect(out.map((p) => p.reason)).toEqual(['HIGH_CLOUD', 'PRECIPITATION', 'LOW_VISIBILITY', 'GENERIC', null]);
  });

  it('does not mutate its input, and tolerates a non-array', () => {
    const list = [place('HIGH_CLOUD'), place('TIDE_MISALIGNED')];
    const copy = list.map((p) => ({ ...p }));
    stoodFieldPointsFor(list);
    expect(list).toEqual(copy);
    expect(stoodFieldPointsFor(undefined)).toEqual([]);
    expect(stoodFieldPointsFor(null)).toEqual([]);
  });

  it('is one star', () => {
    expect(STOOD_FIELD_SCORE).toBe(1);
  });
});

describe('a stood place is never a count (stood-down-field plan D4)', () => {
  const YESTERDAY = '2026-09-01';
  const EVENTS = [{ date: YESTERDAY, targetType: 'SUNSET' }];
  const loc = (id) => ({
    id, name: `P${id}`, lat: 55 + id / 10, lon: -1.5, regionName: 'North East', locationType: ['LANDSCAPE'],
  });
  const stoodRow = (id) => ({
    locationId: id, locationName: `P${id}`, date: YESTERDAY, targetType: 'SUNSET', rating: null,
    triageReason: 'HIGH_CLOUD',
  });

  it('pastWindowBest on an all-weather-stood past window is still null', () => {
    const spots = buildHeatSpots([loc(1), loc(2), loc(3)], [stoodRow(1), stoodRow(2), stoodRow(3)], EVENTS);
    expect(pastWindowBest(spots, 0)).toBeNull();
    // The places are stood, so the field would blend them, yet they remain out of every point set.
    expect(stoodPointsFor(spots, 0)).toHaveLength(3);
    expect(stoodFieldPointsFor(stoodPointsFor(spots, 0))).toHaveLength(3);
  });
});
