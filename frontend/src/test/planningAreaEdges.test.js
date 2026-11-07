import { describe, it, expect } from 'vitest';
import {
  areaRegions,
  beyondRegions,
  regionDriveMinutes,
} from '../utils/planningArea.js';

describe('regionDriveMinutes — input that is not clean', () => {
  it('answers an empty map for a reach source that cannot be asked', () => {
    const spots = [{ id: 1, regionName: 'A' }];
    expect(regionDriveMinutes(spots, {}).size).toBe(0);
    expect(regionDriveMinutes(spots, null).size).toBe(0);
    expect(regionDriveMinutes(spots, { get: 'not a function' }).size).toBe(0);
  });

  it('skips empty slots and spots with no region or no id', () => {
    const reach = new Map([[1, { driveMinutes: 30 }], [2, { driveMinutes: 40 }], [3, { driveMinutes: 50 }]]);
    const spots = [null, undefined, { id: 1 }, { id: 2, regionName: '' }, { regionName: 'A' }, { id: 3, regionName: 'B' }];
    const minutes = regionDriveMinutes(spots, reach);
    expect([...minutes.entries()]).toEqual([['B', 50]]);
  });

  it('does not record a drive time that is not a finite number', () => {
    const reach = new Map([
      [1, { driveMinutes: NaN }],
      [2, { driveMinutes: Infinity }],
      [3, { driveMinutes: '30' }],
      [4, { driveMinutes: null }],
      [5, { driveMinutes: 25 }],
    ]);
    const spots = [1, 2, 3, 4, 5].map((id) => ({ id, regionName: `R${id}` }));
    expect([...regionDriveMinutes(spots, reach).entries()]).toEqual([['R5', 25]]);
  });

  it('keeps the shortest drive among a region\'s places', () => {
    const reach = new Map([[1, { driveMinutes: 90 }], [2, { driveMinutes: 40 }], [3, { driveMinutes: 60 }]]);
    const spots = [1, 2, 3].map((id) => ({ id, regionName: 'A' }));
    expect(regionDriveMinutes(spots, reach).get('A')).toBe(40);
  });

  it('answers an empty map for spots that are not a list', () => {
    expect(regionDriveMinutes('spots', new Map()).size).toBe(0);
  });
});

describe('areaRegions and beyondRegions — the region list itself', () => {
  it('lists each named region once and ignores empty slots and unnamed spots', () => {
    const spots = [null, { id: 1, regionName: 'A' }, { id: 2 }, { id: 3, regionName: 'A' }, { id: 4, regionName: '' }, { id: 5, regionName: 'B' }];
    expect(areaRegions(spots, null)).toEqual(['A', 'B']);
  });

  it('answers empty lists for spots that are not a list', () => {
    expect(areaRegions('spots', null)).toEqual([]);
    expect(beyondRegions('spots', null)).toEqual([]);
    expect(areaRegions(null, null)).toEqual([]);
  });

  it('works with a plain object as the reach source, treating every region as unmeasured', () => {
    const spots = [{ id: 1, regionName: 'B' }, { id: 2, regionName: 'A' }];
    expect(areaRegions(spots, {})).toEqual(['A', 'B']);
    expect(beyondRegions(spots, {})).toEqual([]);
  });

  it('puts a region exactly at the glance limit inside the area and one minute over it beyond', () => {
    const reach = new Map([[1, { driveMinutes: 180 }], [2, { driveMinutes: 181 }]]);
    const spots = [{ id: 1, regionName: 'Edge' }, { id: 2, regionName: 'Far' }];
    expect(areaRegions(spots, reach)).toEqual(['Edge']);
    expect(beyondRegions(spots, reach)).toEqual(['Far']);
  });

  it('orders by shortest drive, unmeasured last, ties by name', () => {
    const reach = new Map([[1, { driveMinutes: 60 }], [2, { driveMinutes: 30 }], [3, { driveMinutes: 60 }]]);
    const spots = [
      { id: 1, regionName: 'Beta' }, { id: 2, regionName: 'Gamma' },
      { id: 3, regionName: 'Alpha' }, { id: 4, regionName: 'Unmeasured' },
    ];
    expect(areaRegions(spots, reach)).toEqual(['Gamma', 'Alpha', 'Beta', 'Unmeasured']);
  });
});
