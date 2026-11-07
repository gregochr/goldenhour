import { describe, it, expect } from 'vitest';
import { buildJumpRows, buildNightRegionBest, buildRegionBestIndex } from '../utils/regionsJump.js';

// Edge pins for the Regions jump list's pure logic: which served rows the best-rating index keeps, how a night's
// rows group by region, and the jump rows' total order. Fixtures are built inside each test.

/** A briefing with one window per entry: [date, targetType, regions]. */
function days(entries) {
  return entries.map(([date, targetType, regions]) => ({ date, eventSummaries: [{ targetType, regions }] }));
}

describe('buildRegionBestIndex', () => {
  it('keeps only a finite numeric rating', () => {
    const index = buildRegionBestIndex(days([['2026-06-15', 'SUNSET', [
      { regionName: 'Good', bestRating: 4 },
      { regionName: 'Zero', bestRating: 0 },
      { regionName: 'Text', bestRating: '4' },
      { regionName: 'NotANumber', bestRating: NaN },
      { regionName: 'Infinite', bestRating: Infinity },
      { regionName: 'Missing' },
      { regionName: 'Null', bestRating: null },
    ]]]));
    expect([...index.entries()]).toEqual([
      ['2026-06-15|SUNSET|Good', 4],
      ['2026-06-15|SUNSET|Zero', 0],
    ]);
  });

  it('skips a day with no date, a window with no target type and a region with no name', () => {
    const index = buildRegionBestIndex([
      { eventSummaries: [{ targetType: 'SUNSET', regions: [{ regionName: 'A', bestRating: 3 }] }] },
      { date: '2026-06-15', eventSummaries: [{ regions: [{ regionName: 'B', bestRating: 3 }] }] },
      { date: '2026-06-15', eventSummaries: [{ targetType: 'SUNSET', regions: [{ bestRating: 3 }] }] },
    ]);
    expect(index.size).toBe(0);
  });

  it('survives null days, null windows and null regions beside good ones', () => {
    const index = buildRegionBestIndex([
      null,
      { date: '2026-06-15', eventSummaries: [null, { targetType: 'SUNRISE', regions: [null, { regionName: 'A', bestRating: 2 }] }] },
    ]);
    expect([...index.keys()]).toEqual(['2026-06-15|SUNRISE|A']);
  });

  it('survives a day with no windows, a window with no regions and a briefing that is not a list', () => {
    expect(buildRegionBestIndex([{ date: '2026-06-15' }, { date: '2026-06-16', eventSummaries: [{ targetType: 'SUNSET' }] }]).size)
      .toBe(0);
    expect(buildRegionBestIndex(null).size).toBe(0);
    expect(buildRegionBestIndex({ date: '2026-06-15' }).size).toBe(0);
  });

  it('keeps the first rating served for a repeated window and region', () => {
    const index = buildRegionBestIndex(days([
      ['2026-06-15', 'SUNSET', [{ regionName: 'A', bestRating: 5 }, { regionName: 'A', bestRating: 2 }]],
    ]));
    expect(index.get('2026-06-15|SUNSET|A')).toBe(5);
  });
});

describe('buildNightRegionBest', () => {
  it('groups served rows by their location\'s region and takes each region\'s best star', () => {
    const spots = [
      { name: 'Loc 1', regionName: 'North' }, { name: 'Loc 2', regionName: 'North' }, { name: 'Loc 3', regionName: 'South' },
    ];
    const rows = [
      { locationName: 'Loc 1', stars: 5 }, { locationName: 'Loc 2', stars: 2 },
      { locationName: 'Loc 3', stars: 3 }, { locationName: 'Loc 3', stars: 1 },
    ];
    expect([...buildNightRegionBest(rows, spots).entries()]).toEqual([['North', 5], ['South', 3]]);
  });

  it('keeps a lower-starred row listed before a higher one in the same region', () => {
    const spots = [{ name: 'Loc 1', regionName: 'North' }, { name: 'Loc 2', regionName: 'North' }];
    const rows = [{ locationName: 'Loc 1', stars: 1 }, { locationName: 'Loc 2', stars: 4 }];
    expect(buildNightRegionBest(rows, spots).get('North')).toBe(4);
  });

  it('leaves out a row whose location is not in the catalogue, and a row with no location name', () => {
    const spots = [{ name: 'Loc 1', regionName: 'North' }];
    const rows = [{ locationName: 'Ghost', stars: 5 }, { stars: 4 }, { locationName: 'Loc 1', stars: 2 }];
    const result = buildNightRegionBest(rows, spots);
    expect([...result.entries()]).toEqual([['North', 2]]);
  });

  it('does not pair a nameless spot with a nameless row', () => {
    const spots = [{ regionName: 'Orphan' }, { name: 'Loc 1', regionName: 'North' }];
    const rows = [{ stars: 5 }, { locationName: 'Loc 1', stars: 2 }];
    expect([...buildNightRegionBest(rows, spots).entries()]).toEqual([['North', 2]]);
  });

  it('does not pair a spot that has no region with its rows', () => {
    const spots = [{ name: 'Loc 1' }, { name: 'Loc 2', regionName: 'North' }];
    const rows = [{ locationName: 'Loc 1', stars: 5 }, { locationName: 'Loc 2', stars: 2 }];
    expect([...buildNightRegionBest(rows, spots).entries()]).toEqual([['North', 2]]);
  });

  it('survives null spots and null rows, and inputs that are not lists', () => {
    const spots = [null, { name: 'Loc 1', regionName: 'North' }];
    const rows = [null, { locationName: 'Loc 1', stars: 2 }];
    expect([...buildNightRegionBest(rows, spots).entries()]).toEqual([['North', 2]]);
    expect(buildNightRegionBest(null, null).size).toBe(0);
    expect(buildNightRegionBest({ locationName: 'Loc 1' }, { name: 'Loc 1' }).size).toBe(0);
  });

  it('gives a region whose rows carry no star a null best', () => {
    const spots = [{ name: 'Loc 1', regionName: 'North' }];
    expect(buildNightRegionBest([{ locationName: 'Loc 1', stars: null }], spots).get('North')).toBeNull();
  });
});

describe('buildJumpRows', () => {
  /** Three regions, each with one located spot, and a reach map giving them 100, 10 and 50 minutes. */
  function catalogue() {
    return {
      spots: [
        { id: 1, regionName: 'Far' }, { id: 2, regionName: 'Near' }, { id: 3, regionName: 'Mid' },
      ],
      driveMap: new Map([[1, { driveMinutes: 100 }], [2, { driveMinutes: 10 }], [3, { driveMinutes: 50 }]]),
    };
  }

  it('sorts several regions nearest first by their measured drive', () => {
    const rows = buildJumpRows(catalogue());
    expect(rows.map((r) => r.name)).toEqual(['Near', 'Mid', 'Far']);
    expect(rows.map((r) => r.driveMinutes)).toEqual([10, 50, 100]);
  });

  it('puts an unmeasured region after every measured one, and breaks equal drives by name', () => {
    const { spots, driveMap } = catalogue();
    spots.push({ id: 4, regionName: 'Unmeasured B' }, { id: 5, regionName: 'Unmeasured A' }, { id: 6, regionName: 'Alpha' });
    driveMap.set(6, { driveMinutes: 50 });
    const rows = buildJumpRows({ spots, driveMap });
    expect(rows.map((r) => r.name)).toEqual(['Near', 'Alpha', 'Mid', 'Far', 'Unmeasured A', 'Unmeasured B']);
    expect(rows[4].driveMinutes).toBeNull();
    expect(rows[4].beyondArea).toBe(false);
  });

  it('marks a region beyond the area only past the three-hour threshold', () => {
    const rows = buildJumpRows({
      spots: [{ id: 1, regionName: 'Edge' }, { id: 2, regionName: 'Past' }],
      driveMap: new Map([[1, { driveMinutes: 180 }], [2, { driveMinutes: 181 }]]),
    });
    expect(rows.map((r) => [r.name, r.beyondArea])).toEqual([['Edge', false], ['Past', true]]);
  });

  it('gives every row a null best when no resolver is passed', () => {
    const rows = buildJumpRows(catalogue());
    expect(rows.map((r) => r.bestRating)).toEqual([null, null, null]);
  });

  it('gives every row a null best when the resolver is not a function', () => {
    const rows = buildJumpRows({ ...catalogue(), bestRatingFor: 'nope' });
    expect(rows.map((r) => r.bestRating)).toEqual([null, null, null]);
  });

  it('takes each row\'s best from the resolver, null when it has none, zero kept', () => {
    const best = { Near: 4, Mid: 0 };
    const rows = buildJumpRows({ ...catalogue(), bestRatingFor: (name) => best[name] });
    expect(rows.map((r) => [r.name, r.bestRating])).toEqual([['Near', 4], ['Mid', 0], ['Far', null]]);
  });

  it('lists a region once however many spots it has, and skips spots with no region', () => {
    const rows = buildJumpRows({
      spots: [null, { id: 1, regionName: 'A' }, { id: 2, regionName: 'A' }, { id: 3 }],
      driveMap: new Map([[1, { driveMinutes: 30 }], [2, { driveMinutes: 20 }]]),
    });
    expect(rows).toEqual([{ name: 'A', driveMinutes: 20, beyondArea: false, bestRating: null }]);
  });

  it('gives no rows for a catalogue that is not a list', () => {
    expect(buildJumpRows({ spots: null, driveMap: new Map() })).toEqual([]);
    expect(buildJumpRows({ spots: { regionName: 'A' }, driveMap: new Map() })).toEqual([]);
  });
});
