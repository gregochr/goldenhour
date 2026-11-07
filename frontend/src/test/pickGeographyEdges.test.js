import { describe, it, expect } from 'vitest';
import { regionPickSummaries } from '../utils/pickGeography.js';

describe('pickGeography edges', () => {
  it('never counts a place with no id as allowed, even if the served list holds a null', () => {
    const spots = [
      { id: null, name: 'Nameless', regionName: 'Lakes', subRegionId: 1, subRegionName: 'North' },
      { id: undefined, name: 'Other', regionName: 'Lakes', subRegionId: 1, subRegionName: 'North' },
    ];
    const out = regionPickSummaries(spots, [null, undefined]);
    const lakes = out.get('Lakes');
    expect(lakes.places).toBe(2);
    expect(lakes.allowedPlaces).toBe(0);
    expect(lakes.skyOutside).toBe(2);
    expect(lakes.outsidePlaces.map((p) => p.name)).toEqual(['Nameless', 'Other']);
    expect(lakes.pickedNames).toEqual([]);
  });

  it('is empty for a shaped body with no spots array', () => {
    expect(regionPickSummaries(null, [1]).size).toBe(0);
    expect(regionPickSummaries(undefined, []).size).toBe(0);
  });

  it('is null for an unshaped body', () => {
    expect(regionPickSummaries([], null)).toBeNull();
  });
});
