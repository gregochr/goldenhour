import { describe, it, expect } from 'vitest';
import {
  REGION_SORT_ACCESSORS,
  buildRegionRows,
  locationsText,
  subRegionsText,
  regionSummary,
  regionMeta,
  regionRowLabel,
} from '../utils/regionAdmin.js';

const REGIONS = [
  { id: 1, name: 'Northumberland', enabled: true, createdAt: '2026-03-01T09:00:00Z' },
  { id: 2, name: 'Lake District', enabled: false, createdAt: '2026-04-10T09:00:00Z' },
];

const LOCATIONS = [
  { id: 10, region: { id: 1 }, enabled: true },
  { id: 11, region: { id: 1 }, enabled: false },
  { id: 12, region: { id: 1 }, enabled: true },
  { id: 13, region: { id: 2 }, enabled: true },
  { id: 14, region: null, enabled: true },
  { id: 15, enabled: true },
];

const AREAS = [
  { id: 100, kind: 'SUB_REGION', regionId: 1, name: 'north coast', locationIds: [10, 11] },
  { id: 101, kind: 'SUB_REGION', regionId: 1, name: 'Cheviots', locationIds: [12] },
  { id: 102, kind: 'SUB_REGION', regionId: 1, name: 'Empty one' },
  { id: 103, kind: 'DRIVE_TIME', regionId: 1, name: 'Not a sub-region', locationIds: [10] },
  { id: 104, kind: 'SUB_REGION', regionId: 2, name: 'Langdale', locationIds: [13] },
];

describe('buildRegionRows', () => {
  const rows = buildRegionRows(REGIONS, LOCATIONS, AREAS);

  it('counts the locations in each region, ignoring ones with no region', () => {
    expect(rows[0].locationCount).toBe(3);
    expect(rows[1].locationCount).toBe(1);
  });

  it('counts only the switched-on locations as enabled', () => {
    expect(rows[0].enabledLocationCount).toBe(2);
    expect(rows[1].enabledLocationCount).toBe(1);
  });

  it('lists only SUB_REGION areas of that region, by name ignoring case, with a location count', () => {
    expect(rows[0].subRegions).toEqual([
      { id: 101, name: 'Cheviots', locationCount: 1 },
      { id: 102, name: 'Empty one', locationCount: 0 },
      { id: 100, name: 'north coast', locationCount: 2 },
    ]);
    expect(rows[0].subRegionCount).toBe(3);
    expect(rows[1].subRegions.map((s) => s.name)).toEqual(['Langdale']);
  });

  it('keeps the region fields and does not mutate the input', () => {
    expect(rows[0]).toMatchObject({ id: 1, name: 'Northumberland', enabled: true });
    expect(REGIONS[0]).not.toHaveProperty('subRegions');
  });

  it('yields zero counts when locations and areas failed to load', () => {
    const empty = buildRegionRows(REGIONS, [], []);
    expect(empty.map((r) => [r.subRegionCount, r.locationCount, r.enabledLocationCount])).toEqual([
      [0, 0, 0],
      [0, 0, 0],
    ]);
  });
});

describe('REGION_SORT_ACCESSORS', () => {
  const row = { name: 'Lakes', subRegionCount: 4, locationCount: 9, createdAt: '2026-01-01T00:00:00Z' };

  it('reads each sortable column from the row', () => {
    expect(REGION_SORT_ACCESSORS.name(row)).toBe('Lakes');
    expect(REGION_SORT_ACCESSORS.subRegions(row)).toBe(4);
    expect(REGION_SORT_ACCESSORS.locations(row)).toBe(9);
    expect(REGION_SORT_ACCESSORS.created(row)).toBe('2026-01-01T00:00:00Z');
  });

  it('reads a missing creation date as null so the sort can place it', () => {
    expect(REGION_SORT_ACCESSORS.created({ name: 'x' })).toBeNull();
  });
});

describe('region text helpers', () => {
  it('pluralises locations', () => {
    expect(locationsText(0)).toBe('0 locations');
    expect(locationsText(1)).toBe('1 location');
    expect(locationsText(3)).toBe('3 locations');
  });

  it('pluralises sub-regions', () => {
    expect(subRegionsText(1)).toBe('1 sub-region');
    expect(subRegionsText(2)).toBe('2 sub-regions');
  });

  it('joins the two counts for the card summary', () => {
    expect(regionSummary({ subRegionCount: 1, locationCount: 14 })).toBe('1 sub-region · 14 locations');
  });

  it('says On or Off with the creation date', () => {
    expect(regionMeta({ enabled: true, createdAt: '2026-03-01T09:00:00Z' })).toBe('On · created 1 Mar 2026');
    expect(regionMeta({ enabled: false, createdAt: '2026-03-01T09:00:00Z' })).toBe('Off · created 1 Mar 2026');
  });

  it('builds the accessible row name from everything the row shows', () => {
    const row = {
      name: 'Lake District', enabled: false, subRegionCount: 2, locationCount: 1, createdAt: '2020-01-05T10:00:00Z',
    };
    expect(regionRowLabel(row)).toBe('Lake District, disabled, 2 sub-regions · 1 location, created 5 Jan 2020');
    expect(regionRowLabel({ ...row, enabled: true })).toContain('Lake District, enabled,');
  });
});
