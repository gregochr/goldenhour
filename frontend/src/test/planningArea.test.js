import { describe, it, expect } from 'vitest';
import {
  GLANCE_MINUTES, areaRegions, areaSpots, beyondRegions, regionDriveMinutes,
} from '../utils/planningArea.js';

/**
 * The planning area (plan D6): "everywhere you could reasonably go for one of these windows".
 *
 * The rule that matters most is the degrade. A user with no home postcode, or a drive-time
 * matrix that has not been computed yet, must see the WHOLE roster — never a smaller area
 * synthesised from missing data, because a place quietly missing from your area is a place you
 * never learn was good.
 */

function spot(id, regionName) {
  return {
    id, name: `Loc ${id}`, lat: 55, lng: -1.5, regionName, bortleClass: null, scores: [],
  };
}

/** Reach as the provider shapes it: keyed by location id, driveMinutes possibly null. */
function reach(entries) {
  return new Map(entries.map(([id, driveMinutes]) => [id, { driveMinutes, distanceMiles: null }]));
}

const SPOTS = [spot(1, 'North East'), spot(2, 'North East'), spot(3, 'Lake District')];

describe('GLANCE_MINUTES', () => {
  it('is three hours', () => {
    // Pinned as a value, not just referenced: every boundary case below is written against 180,
    // and a silent change to the constant would leave them all passing about a different rule.
    expect(GLANCE_MINUTES).toBe(180);
  });
});

describe('regionDriveMinutes', () => {
  it('takes each region\'s SHORTEST measured drive, not its first or its average', () => {
    const minutes = regionDriveMinutes(SPOTS, reach([[1, 200], [2, 90], [3, 240]]));

    expect(minutes.get('North East')).toBe(90);
    expect(minutes.get('Lake District')).toBe(240);
  });

  it('omits a region entirely when nothing in it has a measured drive', () => {
    // Absence is the signal the callers below read to tell "far" apart from "not known".
    const minutes = regionDriveMinutes(SPOTS, reach([[1, null], [2, undefined]]));

    expect(minutes.has('North East')).toBe(false);
  });

  it('ignores a spot with no id even when the reach map has a null key', () => {
    // The guard has to be tested against a reach map that WOULD answer `get(null)` — with an
    // ordinary map, `get(null)` is undefined anyway and the fixture cannot tell the guard from
    // its absence. A null key is reachable: the provider skips entries whose `locationId` is
    // null, but nothing stops a future endpoint shape carrying one.
    const reachById = reach([[null, 30], [1, 30]]);

    expect(regionDriveMinutes([{ ...spot(1, 'North East'), id: null }], reachById).size).toBe(0);
  });

  it('returns an empty map when there is no reach data at all', () => {
    expect(regionDriveMinutes(SPOTS, null).size).toBe(0);
    expect(regionDriveMinutes(SPOTS, new Map()).size).toBe(0);
  });
});

describe('areaRegions / beyondRegions — the boundary', () => {
  it.each([
    ['just inside', 179, true],
    ['exactly on', 180, true],
    ['just outside', 181, false],
  ])('a %s drive of %i minutes is in the area: %s', (_label, drive, inArea) => {
    // Inclusive at the threshold, matching reachLens's own boundary rule ("a 45-minute drive is
    // within 45 min"). One lens should not be inclusive where the other is exclusive.
    const spots = [spot(1, 'North East')];
    const reachById = reach([[1, drive]]);

    expect(areaRegions(spots, reachById)).toEqual(inArea ? ['North East'] : []);
    expect(beyondRegions(spots, reachById)).toEqual(inArea ? [] : ['North East']);
  });

  it('measures a region by its NEAREST location, so one far spot does not exile a close region', () => {
    const reachById = reach([[1, 400], [2, 60], [3, 400]]);

    expect(areaRegions(SPOTS, reachById)).toEqual(['North East']);
    expect(beyondRegions(SPOTS, reachById)).toEqual(['Lake District']);
  });

  it('orders both lists nearest first', () => {
    const spots = [spot(1, 'Yorkshire'), spot(2, 'North East'), spot(3, 'Lake District')];

    expect(areaRegions(spots, reach([[1, 150], [2, 40], [3, 90]])))
      .toEqual(['North East', 'Lake District', 'Yorkshire']);
    expect(beyondRegions(spots, reach([[1, 300], [2, 200], [3, 500]])))
      .toEqual(['North East', 'Yorkshire', 'Lake District']);
  });

  it('breaks a tie on name, so the order is total and the render is stable', () => {
    const spots = [spot(1, 'Yorkshire'), spot(2, 'Lake District')];

    expect(areaRegions(spots, reach([[1, 60], [2, 60]]))).toEqual(['Lake District', 'Yorkshire']);
  });
});

describe('areaRegions — the degrade', () => {
  it('is the WHOLE roster when the user has no drive times at all', () => {
    // The no-home case: no reach entries, so nothing is measured, so nothing may be excluded.
    // A smaller area here would hide places from someone who has told us nothing about where
    // they live — the one situation in which we know least.
    expect(areaRegions(SPOTS, new Map())).toEqual(['Lake District', 'North East']);
    expect(areaRegions(SPOTS, null)).toEqual(['Lake District', 'North East']);
  });

  it('keeps an unmeasured region in the area even when other regions ARE measured', () => {
    // A partial matrix — a location added since the last drive-time run. Unmeasured is not
    // evidence of distance, and the same rule that gives the no-home user the whole roster
    // gives this region the benefit of the doubt. It is one rule, not a special case.
    const reachById = reach([[1, 60], [2, 60]]);

    expect(areaRegions(SPOTS, reachById)).toEqual(['North East', 'Lake District']);
  });

  it('never NAMES an unmeasured region as beyond the area', () => {
    // The beyond line prints these names on screen, and "beyond your planning area" would be a
    // claim about a drive nobody has computed. The two lists stay exact complements — the doubt
    // is what moves, not the arithmetic — which the sibling test below pins directly.
    expect(beyondRegions(SPOTS, reach([[1, 60], [2, 60]]))).toEqual([]);
    expect(beyondRegions(SPOTS, new Map())).toEqual([]);
  });

  it('partitions every region between the two lists, with none in both', () => {
    // Pins what the javadoc claims, because a consumer computing "regions we could not place" as
    // the difference would get a set that is always empty — and might then render a category
    // that can never appear.
    const reachById = reach([[1, 60], [3, 400]]);
    const inArea = areaRegions(SPOTS, reachById);
    const beyond = beyondRegions(SPOTS, reachById);

    expect([...inArea, ...beyond].sort()).toEqual(['Lake District', 'North East']);
    expect(inArea.filter((name) => beyond.includes(name))).toEqual([]);
  });

  it('treats a reach entry with a null driveMinutes as unmeasured, not as zero', () => {
    // The provider writes `driveMinutes ?? null`, so a present-but-null entry is the normal
    // shape for a location the ORS matrix has not reached. Read as 0 it would be the nearest
    // region in the country.
    const reachById = reach([[1, null], [2, null], [3, 400]]);

    expect(areaRegions(SPOTS, reachById)).toEqual(['North East']);
    expect(beyondRegions(SPOTS, reachById)).toEqual(['Lake District']);
  });
});

describe('areaSpots', () => {
  it('returns the spots of the in-area regions, in input order', () => {
    expect(areaSpots(SPOTS, reach([[1, 400], [2, 60], [3, 400]])).map((s) => s.id))
      .toEqual([1, 2]);
  });

  it('keeps a region whole — a 400-minute spot stays in because its region is near', () => {
    // The area is a set of places you plan a trip around, and half a region is not one. It also
    // keeps the Map tab's opening bounds from cutting a region's own field in half. Spot 1 is
    // four hours away and in; spot 3 is four hours away and out — the difference is entirely
    // which region each sits in.
    const spots = areaSpots(SPOTS, reach([[1, 400], [2, 60], [3, 400]]));

    expect(spots.map((s) => s.id)).toEqual([1, 2]);
    expect(spots.map((s) => s.regionName)).toEqual(['North East', 'North East']);
  });

  it('is every spot when nothing is measured', () => {
    expect(areaSpots(SPOTS, new Map())).toHaveLength(3);
  });

  it('survives a null spot list', () => {
    expect(areaSpots(null, new Map())).toEqual([]);
  });
});
