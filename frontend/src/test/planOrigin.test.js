import { describe, it, expect } from 'vitest';
import {
  AWAY_LIMIT_MINUTES,
  AWAY_TIER_ID,
  canBeOrigin,
  gateSpotsByOrigin,
  originReachMap,
  scopeSpots,
  toOrigin,
} from '../utils/planOrigin.js';
import { GLANCE_MINUTES } from '../utils/planningArea.js';
import { tierById } from '../utils/reachLens.js';

/**
 * The origin — the frame of reference the whole Plan tab is written from (plan §4.8).
 *
 * <p><b>What breaks if these fail.</b> Two things, and the second is the dangerous one. A wrong
 * scope draws the wrong week; a wrong drive figure becomes a departure time on a spot card, which
 * is the one number on the screen a reader acts on without checking. The mixing tests below are
 * therefore the load-bearing ones: they pin that a per-user, home-measured figure can never reach
 * an away card.
 */
describe('planOrigin', () => {
  const region = (extra = {}) => ({
    id: 7, name: 'Lake District', baseName: 'Keswick', baseLat: 54.601, baseLon: -3.135, ...extra,
  });

  describe('canBeOrigin — a baseless region cannot be an origin', () => {
    it('accepts a complete base', () => {
      expect(canBeOrigin(region())).toBe(true);
    });

    it('rejects null and undefined without throwing', () => {
      expect(canBeOrigin(null)).toBe(false);
      expect(canBeOrigin(undefined)).toBe(false);
    });

    it('rejects a name with no coordinates — there is nothing to route from', () => {
      expect(canBeOrigin(region({ baseLat: null, baseLon: null }))).toBe(false);
    });

    it('rejects coordinates with no name — the chip would be unlabelled', () => {
      expect(canBeOrigin(region({ baseName: null }))).toBe(false);
      expect(canBeOrigin(region({ baseName: '   ' }))).toBe(false);
    });

    it('rejects a missing longitude alone', () => {
      expect(canBeOrigin(region({ baseLon: null }))).toBe(false);
    });

    it('rejects a coordinate that arrived as a string', () => {
      // `Number('')` is 0 and `Number(null)` is 0, so a coercing check would place a base in the
      // Gulf of Guinea. Strict number, never a coercion — the same rule `heatSpots.coord` holds.
      expect(canBeOrigin(region({ baseLat: '54.601' }))).toBe(false);
    });

    it('rejects a non-finite coordinate', () => {
      expect(canBeOrigin(region({ baseLat: Number.NaN }))).toBe(false);
      expect(canBeOrigin(region({ baseLon: Number.POSITIVE_INFINITY }))).toBe(false);
    });

    it('accepts a base at zero longitude — Greenwich is a place', () => {
      expect(canBeOrigin(region({ baseLon: 0 }))).toBe(true);
    });
  });

  describe('toOrigin', () => {
    it('carries the id, the region name and the trimmed base name — and nothing else', () => {
      expect(toOrigin(region({ baseName: '  Keswick ' })))
        .toEqual({ id: 7, name: 'Lake District', baseName: 'Keswick' });
    });

    it('answers null for a region that cannot be an origin', () => {
      expect(toOrigin(region({ baseLat: null }))).toBeNull();
      expect(toOrigin(null)).toBeNull();
    });

    it('carries no rating, verdict or count, so an origin cannot go stale against the forecast', () => {
      const origin = toOrigin(region({ meanRating: 4, verdict: 'GO' }));
      expect(Object.keys(origin).sort()).toEqual(['baseName', 'id', 'name']);
    });
  });

  describe('originReachMap — the shared matrix, and ONLY the shared matrix', () => {
    const origin = toOrigin(region());

    it('is null at home, so "am I away" is the origin rather than an empty map', () => {
      expect(originReachMap(null, { 7: { 11: 25 } })).toBeNull();
    });

    it('reads the origin region\'s own row and coerces the JSON string keys to numbers', () => {
      const map = originReachMap(origin, { 7: { 11: 25, 12: 40 }, 9: { 11: 5 } });
      expect(map.get(11)).toEqual({ driveMinutes: 25, distanceMiles: null });
      expect(map.get(12)).toEqual({ driveMinutes: 40, distanceMiles: null });
      // The other region's row must not leak in: 11 is 25 from Keswick, not 5.
      expect(map.size).toBe(2);
    });

    it('reads a region id that arrived as a string key', () => {
      const map = originReachMap(origin, { '7': { '11': 25 } });
      expect(map.get(11).driveMinutes).toBe(25);
    });

    it('⚠️ never carries a distance — a home-measured mile beside an away drive is two journeys', () => {
      const map = originReachMap(origin, { 7: { 11: 25 } });
      expect(map.get(11).distanceMiles).toBeNull();
    });

    it('⚠️ takes nothing at all from the per-user reach map, even for a location it has no row for', () => {
      // The mixing failure this exists to prevent: location 12 is in the reader's home reach map
      // and NOT in the matrix. It must be absent — unknown — never filled from home.
      const map = originReachMap(origin, { 7: { 11: 25 } });
      expect(map.has(12)).toBe(false);
      expect(map.get(12)).toBeUndefined();
    });

    it('is an empty map — not null — for a region with no stored rows', () => {
      // The ordinary state before the first nightly sweep, and immediately after a base moves.
      // Every drive then reads as unknown, which passes every reach tier and prints no drive line.
      expect(originReachMap(origin, { 9: { 11: 5 } }).size).toBe(0);
      expect(originReachMap(origin, {}).size).toBe(0);
      expect(originReachMap(origin, null).size).toBe(0);
    });

    it('drops a non-numeric or non-finite duration rather than storing NaN minutes', () => {
      const map = originReachMap(origin, {
        7: { 11: 25, 12: null, 13: 'soon', 14: Number.NaN },
      });
      expect([...map.keys()]).toEqual([11]);
    });

    it('keeps a zero-minute drive — a location inside the base town is not a missing row', () => {
      expect(originReachMap(origin, { 7: { 11: 0 } }).get(11).driveMinutes).toBe(0);
    });
  });

  describe('scopeSpots — the planning area at home, one region away', () => {
    const spots = [
      { id: 1, regionName: 'Lake District' },
      { id: 2, regionName: 'Northumberland' },
      { id: 3, regionName: 'Lake District' },
    ];

    it('is exactly the planning area at home, so nothing about the home path changes', () => {
      // With no measured drives every region is unmeasured-and-therefore-in — `areaRegions`' own
      // degrade rule — so the whole catalogue is framed.
      expect(scopeSpots(spots, new Map(), null)).toEqual(spots);
    });

    it('still honours the planning area at home when drives ARE measured', () => {
      const reach = new Map([
        [1, { driveMinutes: 40 }],
        [2, { driveMinutes: GLANCE_MINUTES + 1 }],
        [3, { driveMinutes: 40 }],
      ]);
      expect(scopeSpots(spots, reach, null).map((s) => s.id)).toEqual([1, 3]);
    });

    it('narrows to the origin\'s own region when away — the re-framing move', () => {
      const origin = toOrigin(region());
      expect(scopeSpots(spots, new Map(), origin).map((s) => s.id)).toEqual([1, 3]);
    });

    it('ignores the reach map entirely when away — the field never filters by drive time', () => {
      const origin = toOrigin(region());
      // Every Lake District spot is far beyond the glance threshold from the reader's home; the
      // away scope must still contain both, because scope is about where you are planning FROM.
      const reach = new Map([
        [1, { driveMinutes: 400 }], [3, { driveMinutes: 400 }],
      ]);
      expect(scopeSpots(spots, reach, origin).map((s) => s.id)).toEqual([1, 3]);
    });

    it('matches the region name byte-identically, never normalised', () => {
      const origin = toOrigin(region({ name: 'Lake District' }));
      expect(scopeSpots([{ id: 9, regionName: ' Lake District' }], new Map(), origin)).toEqual([]);
    });

    it('survives a non-array input', () => {
      expect(scopeSpots(null, new Map(), toOrigin(region()))).toEqual([]);
    });
  });

  describe('gateSpotsByOrigin — the scope applied to one window\'s spots', () => {
    const spots = [
      { key: 'a', regionName: 'Lake District' },
      { key: 'b', regionName: 'Northumberland' },
    ];

    it('returns the input untouched at home, by identity', () => {
      expect(gateSpotsByOrigin(spots, null)).toBe(spots);
    });

    it('keeps only the origin\'s region when away', () => {
      expect(gateSpotsByOrigin(spots, toOrigin(region())).map((s) => s.key)).toEqual(['a']);
    });

    it('answers an empty list for a region with nothing in this window', () => {
      expect(gateSpotsByOrigin(spots, toOrigin(region({ name: 'Peak District' })))).toEqual([]);
    });

    it('survives a null spot in the list', () => {
      expect(gateSpotsByOrigin([null, spots[0]], toOrigin(region())).length).toBe(1);
    });
  });

  describe('the away tier', () => {
    it('names a tier the reach lens actually has, and its own threshold', () => {
      // The id and the threshold are two ways of saying one thing and are used by two different
      // consumers (the lens control, and the far mark). A drift between them would gate at one
      // number and mark at another, which is exactly the bug `reachLens`' own labels are derived
      // to prevent.
      expect(tierById(AWAY_TIER_ID)).not.toBeNull();
      expect(tierById(AWAY_TIER_ID).limitMinutes).toBe(AWAY_LIMIT_MINUTES);
    });

    it('is tighter than the home planning area, which is what makes it a stay rather than a trip', () => {
      expect(AWAY_LIMIT_MINUTES).toBeLessThan(GLANCE_MINUTES);
    });
  });
});
