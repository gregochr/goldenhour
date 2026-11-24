import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn() } }));

import apiClient from '../api/axiosClient.js';
import { fetchAreaDirectory, fetchAreaOutlook } from '../api/areasApi.js';
import { areaMapWindow, buildAreaSheet } from '../utils/areaFind.js';
import { buildScoreIndex } from '../utils/locationSheet.js';

/**
 * The reader's area reads and the area sheet's model (find-and-favourites-plan.md F2): the sheet lists each spot's own
 * best window, best first then nearer, never an area rating, and a place outside a Lite reader's picks is outside them
 * and never "unrated".
 */
describe('areasApi', () => {
  beforeEach(() => vi.resetAllMocks());

  it.each([
    ['fetchAreaDirectory', fetchAreaDirectory, '/api/areas/directory'],
    ['fetchAreaOutlook', fetchAreaOutlook, '/api/areas/outlook'],
  ])('%s reads %s and returns the body', async (_name, call, url) => {
    apiClient.get.mockResolvedValue({ data: [{ id: 1 }] });

    expect(await call()).toEqual([{ id: 1 }]);
    expect(apiClient.get).toHaveBeenCalledWith(url);
  });
});

describe('buildAreaSheet', () => {
  const WINDOWS = [
    { key: '2026-10-05:SUNSET', date: '2026-10-05', targetType: 'SUNSET', label: 'Tonight Sunset' },
    { key: '2026-10-06:SUNRISE', date: '2026-10-06', targetType: 'SUNRISE', label: 'Tue Sunrise' },
  ];
  const row = (id, name, date, targetType, rating) => ({
    locationId: id, locationName: name, date, targetType, rating,
  });
  const scoreIndex = buildScoreIndex([
    row(1, 'Alnmouth', '2026-10-05', 'SUNSET', 3),
    row(1, 'Alnmouth', '2026-10-06', 'SUNRISE', 4),
    row(2, 'Bamburgh', '2026-10-05', 'SUNSET', 4),
    row(3, 'Craster', '2026-10-05', 'SUNSET', 4),
  ]);
  const SPOTS = [
    { id: 1, name: 'Alnmouth', regionName: 'North East' },
    { id: 2, name: 'Bamburgh', regionName: 'North East' },
    { id: 3, name: 'Craster', regionName: 'North East' },
    { id: 4, name: 'Dunstan', regionName: 'North East' },
    { id: 8, name: 'Elsewhere', regionName: 'Lakes' },
  ];
  const AREA = {
    id: 5, name: 'Northumberland Coast', kind: 'SUB_REGION', regionName: 'North East', locationIds: [1, 2, 3, 4],
  };
  const reachById = new Map([
    [1, { driveMinutes: 70 }], [2, { driveMinutes: 50 }], [3, { driveMinutes: 40 }],
  ]);

  it('lists only the area\'s spots, each with its own best window, best first then nearer, then by name', () => {
    const sheet = buildAreaSheet(AREA, SPOTS, WINDOWS, { scoreIndex, reachById });

    expect(sheet.rows.map((r) => r.spot.name)).toEqual(['Craster', 'Bamburgh', 'Alnmouth', 'Dunstan']);
    // The window it belongs to rides along (date, event), so a Your places chip can open the map on it.
    expect(sheet.rows[2].top).toMatchObject({ rating: 4, label: 'Tue Sunrise' });
    expect(sheet.rows[2].top).toEqual({
      rating: 4, label: 'Tue Sunrise', date: expect.any(String), targetType: 'SUNRISE',
    });
    expect(sheet).toMatchObject({
      name: 'Northumberland Coast', kindLabel: 'Sub-region', regionName: 'North East', count: 4, ratedCount: 3, gatedCount: 0,
      driveRange: '40 min to 1h 10min',
    });
  });

  it('⚠️ never gives the area a rating of its own', () => {
    const sheet = buildAreaSheet(AREA, SPOTS, WINDOWS, { scoreIndex, reachById });

    expect(Object.keys(sheet)).not.toEqual(expect.arrayContaining(['rating', 'best', 'verdict']));
  });

  it('⚠️ a place outside a Lite reader\'s picks is gated and sorts last, never shown as rated or as unrated', () => {
    const sheet = buildAreaSheet(AREA, SPOTS, WINDOWS, { scoreIndex, reachById, allowedLocationIds: [1] });

    expect(sheet.rows.map((r) => [r.spot.name, r.gated, r.top?.rating ?? null])).toEqual([
      ['Alnmouth', false, 4], ['Craster', true, null], ['Bamburgh', true, null], ['Dunstan', true, null],
    ]);
    expect(sheet.gatedCount).toBe(3);
    expect(sheet.ratedCount).toBe(1);
  });

  it('prints one drive when every spot is the same, and none when no drive is known', () => {
    const same = new Map([[1, { driveMinutes: 30 }], [2, { driveMinutes: 30 }]]);
    expect(buildAreaSheet(AREA, SPOTS, WINDOWS, { scoreIndex, reachById: same }).driveRange).toBe('30 min');
    expect(buildAreaSheet(AREA, SPOTS, WINDOWS, { scoreIndex }).driveRange).toBeNull();
  });

  it('reads a named area as such, and an area with none of its spots on the plan as an empty list', () => {
    const named = { ...AREA, kind: 'NAMED', locationIds: [99] };

    expect(buildAreaSheet(named, SPOTS, WINDOWS, { scoreIndex })).toMatchObject({ kindLabel: 'Named area', rows: [], count: 1 });
  });
});

describe('areaMapWindow', () => {
  const cards = [
    { key: 'a:SUNSET', date: 'a', targetType: 'SUNSET' },
    { key: 'b:SUNRISE', date: 'b', targetType: 'SUNRISE' },
    { key: 'c:SUNSET', date: 'c', targetType: 'SUNSET', away: true },
  ];

  it('opens the window with the highest top rating, the first on a tie, and never an away day', () => {
    const outlook = [{ date: 'a', targetType: 'SUNSET', topRating: 3 }, { date: 'b', targetType: 'SUNRISE', topRating: 5 },
      { date: 'c', targetType: 'SUNSET', topRating: 5 }];

    expect(areaMapWindow(cards, outlook)).toEqual({ date: 'b', targetType: 'SUNRISE' });
    expect(areaMapWindow(cards, [{ date: 'a', targetType: 'SUNSET', topRating: 4 }, { date: 'b', targetType: 'SUNRISE', topRating: 4 }]))
      .toEqual({ date: 'a', targetType: 'SUNSET' });
  });

  it('falls back to the first window with no outlook, and to null with no usable window', () => {
    expect(areaMapWindow(cards, null)).toEqual({ date: 'a', targetType: 'SUNSET' });
    expect(areaMapWindow([cards[2]], null)).toBeNull();
    expect(areaMapWindow(null, null)).toBeNull();
  });
});
