import { describe, it, expect } from 'vitest';
import {
  buildRegionBestIndex, regionBestRatingFor, buildJumpRows, buildNightRegionBest,
} from '../utils/regionsJump.js';
import { GLANCE_MINUTES } from '../utils/planningArea.js';

describe('buildRegionBestIndex / regionBestRatingFor', () => {
  function daysFixture() {
    return [
      {
        date: '2026-06-15',
        eventSummaries: [
          {
            targetType: 'SUNSET',
            regions: [
              { regionName: 'Northumberland', bestRating: 5 },
              { regionName: 'The Lakes', bestRating: null },
            ],
          },
        ],
      },
    ];
  }

  it('joins on date|targetType|regionName', () => {
    const index = buildRegionBestIndex(daysFixture());
    expect(regionBestRatingFor(index, '2026-06-15', 'SUNSET', 'Northumberland')).toBe(5);
  });

  it('does not index a region whose bestRating is null', () => {
    const index = buildRegionBestIndex(daysFixture());
    expect(regionBestRatingFor(index, '2026-06-15', 'SUNSET', 'The Lakes')).toBeNull();
  });

  it('returns null for a region the index was never built with', () => {
    const index = buildRegionBestIndex(daysFixture());
    expect(regionBestRatingFor(index, '2026-06-15', 'SUNSET', 'Nowhere')).toBeNull();
  });

  it('returns null with no index, no date, no targetType or no region name', () => {
    const index = buildRegionBestIndex(daysFixture());
    expect(regionBestRatingFor(null, '2026-06-15', 'SUNSET', 'Northumberland')).toBeNull();
    expect(regionBestRatingFor(index, null, 'SUNSET', 'Northumberland')).toBeNull();
    expect(regionBestRatingFor(index, '2026-06-15', null, 'Northumberland')).toBeNull();
    expect(regionBestRatingFor(index, '2026-06-15', 'SUNSET', null)).toBeNull();
  });

  it('reads region.regionName, never region.name — the field the served BriefingRegion record actually carries', () => {
    // A sibling join (`mapCallout.buildRegionGlossIndex`) once read `region.name`, which
    // `BriefingRegion` never serves at all (its own field is `regionName`) — a pre-existing bug
    // found by this phase's own adversarial review and fixed alongside it. Not repeated here.
    const index = buildRegionBestIndex([{
      date: '2026-06-15',
      eventSummaries: [{ targetType: 'SUNSET', regions: [{ name: 'Northumberland', bestRating: 5 }] }],
    }]);
    expect(index.size).toBe(0);
  });

  it('is empty for an unbuilt/absent days array', () => {
    expect(buildRegionBestIndex(undefined).size).toBe(0);
    expect(buildRegionBestIndex([{ date: null }]).size).toBe(0);
  });

  it('first-inserted wins on a duplicate key', () => {
    const days = [{
      date: '2026-06-15',
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [
          { regionName: 'Northumberland', bestRating: 5 },
          { regionName: 'Northumberland', bestRating: 2 },
        ],
      }],
    }];
    expect(regionBestRatingFor(buildRegionBestIndex(days), '2026-06-15', 'SUNSET', 'Northumberland')).toBe(5);
  });
});

describe('buildJumpRows', () => {
  const SPOTS = [
    { id: 1, regionName: 'North East' },
    { id: 2, regionName: 'North East' },
    { id: 3, regionName: 'The Lakes' },
    { id: 4, regionName: 'The Borders' },
  ];

  it('sorts by nearest measured drive, ascending', () => {
    const driveMap = new Map([
      [1, { driveMinutes: 90 }],
      [2, { driveMinutes: 30 }], // North East's nearest
      [3, { driveMinutes: 60 }],
    ]);
    const rows = buildJumpRows({ spots: SPOTS, driveMap });
    expect(rows.map((r) => r.name)).toEqual(['North East', 'The Lakes', 'The Borders']);
    expect(rows[0].driveMinutes).toBe(30);
    expect(rows[1].driveMinutes).toBe(60);
  });

  it('sorts an unmeasured region LAST and gives it no duration', () => {
    const driveMap = new Map([[1, { driveMinutes: 200 }], [3, { driveMinutes: 30 }]]);
    const rows = buildJumpRows({ spots: SPOTS, driveMap });
    expect(rows.at(-1).name).toBe('The Borders');
    expect(rows.at(-1).driveMinutes).toBeNull();
    expect(rows.at(-1).beyondArea).toBe(false);
  });

  it('ties on drive minutes (including two unmeasured regions) break on name, so the order is total', () => {
    const rows = buildJumpRows({ spots: SPOTS, driveMap: null });
    // No entries measured at all → every region ties at "unmeasured" → alphabetical.
    expect(rows.map((r) => r.name)).toEqual(['North East', 'The Borders', 'The Lakes']);
  });

  it(`sets beyondArea only once the drive clears GLANCE_MINUTES (${GLANCE_MINUTES})`, () => {
    const atThreshold = buildJumpRows({
      spots: SPOTS, driveMap: new Map([[1, { driveMinutes: GLANCE_MINUTES }]]),
    }).find((r) => r.name === 'North East');
    expect(atThreshold.beyondArea).toBe(false); // strictly greater, not >=

    const overThreshold = buildJumpRows({
      spots: SPOTS, driveMap: new Map([[1, { driveMinutes: GLANCE_MINUTES + 1 }]]),
    }).find((r) => r.name === 'North East');
    expect(overThreshold.beyondArea).toBe(true);
  });

  it('never mixes two drive maps — the caller passes ONE, and this module reads only it', () => {
    // Simulates the away-origin overwrite `MapView.driveMinutesFor` already applies: a home reach
    // map exists, but the caller hands `buildJumpRows` the away region-base matrix instead, and a
    // location absent from THAT map reads unmeasured rather than falling back to the home figure.
    const homeReach = new Map([[1, { driveMinutes: 10 }], [2, { driveMinutes: 10 }], [3, { driveMinutes: 10 }]]);
    const awayMatrix = new Map([[4, { driveMinutes: 15 }]]); // only Kelso/"The Borders" measured
    const rows = buildJumpRows({ spots: SPOTS, driveMap: awayMatrix });
    expect(rows.find((r) => r.name === 'The Borders').driveMinutes).toBe(15);
    expect(rows.find((r) => r.name === 'North East').driveMinutes).toBeNull();
    expect(homeReach.size).toBe(3); // sanity: the home map was never consulted
  });

  it('resolves the best score through the injected callback, per region name', () => {
    const rows = buildJumpRows({
      spots: SPOTS,
      driveMap: null,
      bestRatingFor: (name) => (name === 'North East' ? 4 : null),
    });
    expect(rows.find((r) => r.name === 'North East').bestRating).toBe(4);
    expect(rows.find((r) => r.name === 'The Lakes').bestRating).toBeNull();
  });

  it('is null for every row with no bestRatingFor at all', () => {
    const rows = buildJumpRows({ spots: SPOTS, driveMap: null });
    expect(rows.every((r) => r.bestRating === null)).toBe(true);
  });

  it('is empty for an empty or absent spot list', () => {
    expect(buildJumpRows({ spots: [], driveMap: null })).toEqual([]);
    expect(buildJumpRows({ spots: undefined, driveMap: null })).toEqual([]);
  });

  it('drops a spot with no region name rather than producing a blank row', () => {
    const rows = buildJumpRows({ spots: [...SPOTS, { id: 5, regionName: '' }, { id: 6 }], driveMap: null });
    expect(rows.every((r) => r.name)).toBe(true);
    expect(rows).toHaveLength(3);
  });
});

/**
 * A night window's best, grouped by region — the adjudicated ruling (map-tab-v2-plan.md §3 P11):
 * `mapEvents.bestOfNight`'s ALREADY-licensed client max, reused at a finer key over the same served
 * rows, never a second re-derivation.
 */
describe('buildNightRegionBest', () => {
  const SPOTS = [
    { name: 'Bamburgh', regionName: 'North East' },
    { name: 'Tynemouth', regionName: 'North East' },
    { name: 'Wastwater', regionName: 'The Lakes' },
    { name: 'Kelso', regionName: 'The Borders' },
  ];

  it('takes the max served star per region, over the SAME rows bestOfNight itself reduces', () => {
    const rows = [
      { locationName: 'Bamburgh', stars: 3 },
      { locationName: 'Tynemouth', stars: 5 }, // North East's max
      { locationName: 'Wastwater', stars: 2 },
    ];
    const best = buildNightRegionBest(rows, SPOTS);
    expect(best.get('North East')).toBe(5);
    expect(best.get('The Lakes')).toBe(2);
  });

  it('a region with no served night rows at all is ABSENT — the honest dash, not a zero', () => {
    const rows = [{ locationName: 'Bamburgh', stars: 4 }];
    const best = buildNightRegionBest(rows, SPOTS);
    expect(best.has('The Borders')).toBe(false);
    expect(best.get('The Borders')).toBeUndefined();
  });

  it('a region whose served rows carry no usable star is null, not absent', () => {
    const rows = [{ locationName: 'Kelso', stars: null }];
    const best = buildNightRegionBest(rows, SPOTS);
    expect(best.has('The Borders')).toBe(true);
    expect(best.get('The Borders')).toBeNull();
  });

  it('silently excludes a served row naming a location absent from the catalogue', () => {
    const rows = [{ locationName: 'Nowhere', stars: 5 }, { locationName: 'Bamburgh', stars: 3 }];
    const best = buildNightRegionBest(rows, SPOTS);
    expect(best.get('North East')).toBe(3);
    expect(best.size).toBe(1);
  });

  it('is empty for no rows, no spots, or both absent', () => {
    expect(buildNightRegionBest([], SPOTS).size).toBe(0);
    expect(buildNightRegionBest(undefined, SPOTS).size).toBe(0);
    expect(buildNightRegionBest([{ locationName: 'Bamburgh', stars: 3 }], undefined).size).toBe(0);
  });

  it('feeding buildJumpRows a bestRatingFor built from this gives night rows a real score', () => {
    const rows = [
      { locationName: 'Bamburgh', stars: 3 },
      { locationName: 'Tynemouth', stars: 5 },
    ];
    const nightBest = buildNightRegionBest(rows, SPOTS);
    const jumpRows = buildJumpRows({
      spots: SPOTS,
      driveMap: null,
      bestRatingFor: (name) => nightBest.get(name) ?? null,
    });
    expect(jumpRows.find((r) => r.name === 'North East').bestRating).toBe(5);
    // "The Borders" had no served night rows at all — still the honest dash.
    expect(jumpRows.find((r) => r.name === 'The Borders').bestRating).toBeNull();
  });
});
