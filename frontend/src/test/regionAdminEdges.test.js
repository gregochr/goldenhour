import { describe, it, expect } from 'vitest';
import { buildRegionRows } from '../utils/regionAdmin.js';

const sub = (id, name, regionId, locationIds, kind = 'SUB_REGION') => ({
  id, name, kind, regionId, locationIds,
});

describe('buildRegionRows — which areas are a region\'s sub-regions', () => {
  it('keeps only SUB_REGION areas of THIS region, leaving other kinds and other regions out', () => {
    const rows = buildRegionRows(
      [{ id: 1, name: 'North' }, { id: 2, name: 'South' }],
      [],
      [
        sub(10, 'Coast', 1, [1, 2]),
        sub(11, 'Hills', 2, [3]),
        sub(12, 'Pool', 1, [4], 'DARK_SKY_POOL'),
        sub(13, 'Fells', 1, [5]),
      ],
    );
    expect(rows[0].subRegions.map((s) => s.id)).toEqual([10, 13]);
    expect(rows[0].subRegionCount).toBe(2);
    expect(rows[1].subRegions.map((s) => s.id)).toEqual([11]);
    expect(rows[1].subRegionCount).toBe(1);
  });

  it('counts each sub-region\'s locations, zero when the area carries no id list', () => {
    const rows = buildRegionRows(
      [{ id: 1, name: 'North' }],
      [],
      [sub(10, 'Coast', 1, [1, 2, 3]), sub(11, 'Fells', 1, undefined)],
    );
    expect(rows[0].subRegions).toEqual([
      { id: 10, name: 'Coast', locationCount: 3 },
      { id: 11, name: 'Fells', locationCount: 0 },
    ]);
  });

  it('sorts sub-regions by name, ignoring case, and keeps the input order for names equal that way', () => {
    const rows = buildRegionRows(
      [{ id: 1, name: 'North' }],
      [],
      [
        sub(1, 'zebra', 1, []),
        sub(2, 'Apple', 1, []),
        sub(3, 'apple', 1, []),
        sub(4, 'Mid', 1, []),
      ],
    );
    expect(rows[0].subRegions.map((s) => s.id)).toEqual([2, 3, 4, 1]);
  });
});

describe('buildRegionRows — the location counts', () => {
  it('counts a region\'s locations and its enabled ones', () => {
    const rows = buildRegionRows(
      [{ id: 1, name: 'North' }, { id: 2, name: 'South' }],
      [
        { id: 1, region: { id: 1 }, enabled: true },
        { id: 2, region: { id: 1 }, enabled: false },
        { id: 3, region: { id: 2 }, enabled: true },
        { id: 4, region: null, enabled: true },
        { id: 5, enabled: true },
      ],
      [],
    );
    expect(rows[0]).toMatchObject({ locationCount: 2, enabledLocationCount: 1, subRegionCount: 0 });
    expect(rows[1]).toMatchObject({ locationCount: 1, enabledLocationCount: 1 });
  });
});
