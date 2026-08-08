import { describe, it, expect, beforeEach } from 'vitest';
import {
  ANY_RATING_ID,
  ANY_TYPE_ID,
  PLAN_RATING_KEY,
  RATING_FLOORS,
  browseCountLine,
  browseEmptyLine,
  browseSpots,
  hasRatings,
  ratingFloorById,
  readStoredRatingFloorId,
  sheetOffersMore,
  spotTypes,
  typeOptionsFor,
  writeStoredRatingFloorId,
} from '../utils/windowSpotBrowse.js';

function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Beach',
    regionName: 'Northumberland & Tyneside',
    rating: 4,
    driveMinutes: 66,
    distanceMiles: 47,
    ...overrides,
  };
}

const COAST = spot({ key: '1', locationName: 'Bamburgh Beach', rating: 4, driveMinutes: 66 });
const FELL = spot({ key: '2', locationName: 'Simonside', rating: 3, driveMinutes: 19 });
const FALLS = spot({ key: '3', locationName: 'High Force', rating: 5, driveMinutes: 95 });
const UNRATED = spot({ key: '4', locationName: 'Cresswell', rating: null, driveMinutes: 30 });
const NO_DRIVE = spot({ key: '5', locationName: 'Wastwater', rating: 2, driveMinutes: null });

const TYPES = new Map([
  ['Bamburgh Beach', ['SEASCAPE', 'LANDSCAPE']],
  ['Simonside', ['LANDSCAPE']],
  ['High Force', ['WATERFALL']],
  ['Cresswell', ['SEASCAPE']],
  ['Wastwater', ['LANDSCAPE']],
]);

/** The loosest possible call — every filter off, so only the ordering runs. */
const unfiltered = (spots, typesByName = TYPES) => browseSpots({
  spots, limitMinutes: null, ratingFloorId: ANY_RATING_ID, typeId: ANY_TYPE_ID, typesByName,
});

describe('windowSpotBrowse', () => {
  describe('the rating floors', () => {
    it('offers Any, 3, 4 and 5 — the ladder Close to Home already cycles', () => {
      expect(RATING_FLOORS.map((f) => f.id)).toEqual(['any', '3', '4', '5']);
    });

    it('labels the top step 5★ and never 5★+, because there is no sixth star', () => {
      expect(ratingFloorById('5').label).toBe('5★');
      expect(ratingFloorById('4').label).toBe('4★+');
      expect(ratingFloorById('3').label).toBe('3★+');
    });

    it('gives the loose tier a null threshold, so no arithmetic runs for it', () => {
      expect(ratingFloorById(ANY_RATING_ID).min).toBeNull();
    });

    it('returns null for an id this build does not offer', () => {
      expect(ratingFloorById('2')).toBeNull();
      expect(ratingFloorById(undefined)).toBeNull();
    });
  });

  describe('persistence', () => {
    beforeEach(() => localStorage.clear());

    it('round-trips a floor under its own key', () => {
      writeStoredRatingFloorId('4');
      expect(readStoredRatingFloorId()).toBe('4');
    });

    it('writes only the rating, so nothing else under any key can ride along', () => {
      localStorage.setItem(PLAN_RATING_KEY, JSON.stringify({ rating: '3', smuggled: 'x' }));
      writeStoredRatingFloorId('4');
      expect(JSON.parse(localStorage.getItem(PLAN_RATING_KEY))).toEqual({ rating: '4' });
    });

    it('carries NO day stamp — a rating floor is taste, not a today-only choice like reach', () => {
      writeStoredRatingFloorId('5');
      expect(Object.keys(JSON.parse(localStorage.getItem(PLAN_RATING_KEY)))).toEqual(['rating']);
    });

    it('reads null when there is no key at all', () => {
      expect(readStoredRatingFloorId()).toBeNull();
    });

    it('reads null rather than throwing on unparseable JSON', () => {
      localStorage.setItem(PLAN_RATING_KEY, 'not json');
      expect(readStoredRatingFloorId()).toBeNull();
    });

    it('reads null for a floor id this build no longer offers', () => {
      localStorage.setItem(PLAN_RATING_KEY, JSON.stringify({ rating: '2' }));
      expect(readStoredRatingFloorId()).toBeNull();
    });

    it('does not use the reach key — the two expire differently and must not share one object', () => {
      writeStoredRatingFloorId('4');
      expect(localStorage.getItem('photocast.planReach')).toBeNull();
    });
  });

  describe('the type lookup', () => {
    it('returns the location types in display order, whatever order the payload gave', () => {
      const backwards = new Map([['Bamburgh Beach', ['SEASCAPE', 'LANDSCAPE']]]);
      expect(spotTypes(COAST, backwards)).toEqual(['LANDSCAPE', 'SEASCAPE']);
    });

    it('drops BLUEBELL, which is a seasonal subject rather than a kind of place', () => {
      const bluebell = new Map([['Simonside', ['LANDSCAPE', 'BLUEBELL']]]);
      expect(spotTypes(FELL, bluebell)).toEqual(['LANDSCAPE']);
    });

    it('drops WOODLAND, because the canopy sites were already removed upstream', () => {
      // Allen Banks' shape: a wood with an open aspect, so `isWoodlandOnly()` is false, its slot is
      // not canopy-flagged and it IS in this sheet. Offering it a "Woodland" chip would put a
      // complete-sounding word over a set the enclosed woods had been taken out of, with nothing on
      // screen saying so. It reads as Landscape, which is true of it and is why it is here.
      const allenBanks = new Map([['Simonside', ['LANDSCAPE', 'BLUEBELL', 'WOODLAND']]]);
      expect(spotTypes(FELL, allenBanks)).toEqual(['LANDSCAPE']);
    });

    it('yields nothing for a location typed only WOODLAND, so no chip can name it', () => {
      expect(spotTypes(FELL, new Map([['Simonside', ['WOODLAND']]]))).toEqual([]);
    });

    it('returns nothing when the name join misses, rather than guessing', () => {
      expect(spotTypes(COAST, new Map())).toEqual([]);
      expect(spotTypes(COAST, null)).toEqual([]);
    });

    it('accepts a bare string as well as an array — the roster field is declared both ways', () => {
      expect(spotTypes(FELL, new Map([['Simonside', 'LANDSCAPE']]))).toEqual(['LANDSCAPE']);
    });
  });

  describe('the type options, derived from the population', () => {
    it('offers Any plus each type present, in display order', () => {
      expect(typeOptionsFor([COAST, FELL, FALLS], TYPES)).toEqual([
        { id: 'any', label: 'Any type' },
        { id: 'LANDSCAPE', label: 'Landscape' },
        { id: 'SEASCAPE', label: 'Seascape' },
        { id: 'WATERFALL', label: 'Waterfall' },
      ]);
    });

    it('offers nothing when every spot is one type — that is a label, not a choice', () => {
      expect(typeOptionsFor([FELL, NO_DRIVE], TYPES)).toEqual([]);
    });

    it('offers nothing when no spot joined, which is the whole roster being absent', () => {
      expect(typeOptionsFor([COAST, FELL], new Map())).toEqual([]);
    });

    it('offers nothing for an empty window', () => {
      expect(typeOptionsFor([], TYPES)).toEqual([]);
    });

    it('never offers a type no spot carries, so no chip can match nothing', () => {
      const ids = typeOptionsFor([COAST, FELL], TYPES).map((o) => o.id);
      expect(ids).not.toContain('WATERFALL');
    });

    it('never offers WOODLAND even when a spot carries it', () => {
      const withWood = new Map([
        ['Bamburgh Beach', ['SEASCAPE']],
        ['Simonside', ['LANDSCAPE', 'WOODLAND']],
      ]);
      expect(typeOptionsFor([COAST, FELL], withWood).map((o) => o.id))
        .toEqual(['any', 'LANDSCAPE', 'SEASCAPE']);
    });
  });

  describe('hasRatings', () => {
    it('is true when one spot is rated and false when none is', () => {
      expect(hasRatings([UNRATED, COAST])).toBe(true);
      expect(hasRatings([UNRATED])).toBe(false);
      expect(hasRatings([])).toBe(false);
    });
  });

  describe('browseSpots — one function, so the list and its count cannot disagree', () => {
    it('ranks by rating, then drive time, then name — the strip\'s own comparator', () => {
      expect(unfiltered([COAST, FELL, FALLS]).map((s) => s.locationName))
        .toEqual(['High Force', 'Bamburgh Beach', 'Simonside']);
    });

    it('sorts an unrated spot last, because an unknown is not a good', () => {
      expect(unfiltered([UNRATED, FELL]).map((s) => s.locationName))
        .toEqual(['Simonside', 'Cresswell']);
    });

    describe('the reach gate', () => {
      it('keeps a drive exactly at the threshold', () => {
        const kept = browseSpots({
          spots: [COAST], limitMinutes: 66, ratingFloorId: ANY_RATING_ID, typeId: ANY_TYPE_ID,
        });
        expect(kept).toHaveLength(1);
      });

      it('drops a drive one minute over it', () => {
        const kept = browseSpots({
          spots: [COAST], limitMinutes: 65, ratingFloorId: ANY_RATING_ID, typeId: ANY_TYPE_ID,
        });
        expect(kept).toEqual([]);
      });

      it('passes a spot with no drive time through every tier — unknown is not out of reach', () => {
        const kept = browseSpots({
          spots: [NO_DRIVE], limitMinutes: 45, ratingFloorId: ANY_RATING_ID, typeId: ANY_TYPE_ID,
        });
        expect(kept.map((s) => s.locationName)).toEqual(['Wastwater']);
      });
    });

    describe('the rating floor', () => {
      const floored = (spots, ratingFloorId) => browseSpots({
        spots, limitMinutes: null, ratingFloorId, typeId: ANY_TYPE_ID, typesByName: TYPES,
      });

      it('keeps a rating exactly at the floor', () => {
        expect(floored([FELL], '3').map((s) => s.locationName)).toEqual(['Simonside']);
      });

      it('drops a rating one star below it', () => {
        expect(floored([FELL, COAST], '4').map((s) => s.locationName)).toEqual(['Bamburgh Beach']);
      });

      it('keeps a rating above it', () => {
        expect(floored([FALLS], '4').map((s) => s.locationName)).toEqual(['High Force']);
      });

      it('drops an unrated spot — "4★ and up" is a claim about what was measured', () => {
        expect(floored([COAST, UNRATED], '4').map((s) => s.locationName))
          .toEqual(['Bamburgh Beach']);
      });

      it('DOES NOT run when nothing in the window is rated, however the floor was stored', () => {
        // The trap this pairing exists to close: a floor of 4★ kept from a scored evening would
        // otherwise silently empty an unevaluated window, with no control on screen to undo it.
        const unratedOnly = [UNRATED, spot({ key: '9', locationName: 'Druridge', rating: null })];
        expect(floored(unratedOnly, '4')).toHaveLength(2);
      });
    });

    describe('the type filter', () => {
      const typed = (spots, typeId, typesByName = TYPES) => browseSpots({
        spots, limitMinutes: null, ratingFloorId: ANY_RATING_ID, typeId, typesByName,
      });

      it('keeps a spot carrying the type among several', () => {
        expect(typed([COAST, FALLS], 'SEASCAPE').map((s) => s.locationName))
          .toEqual(['Bamburgh Beach']);
      });

      it('keeps the same multi-typed spot under EITHER of its types', () => {
        expect(typed([COAST, FALLS], 'LANDSCAPE').map((s) => s.locationName))
          .toEqual(['Bamburgh Beach']);
      });

      it('drops a spot the name join missed — an unknown type is not a match', () => {
        // Two joined types, so the control IS offered; High Force joins to nothing and is not
        // "Seascape" on the strength of an absent record.
        const partial = new Map([['Bamburgh Beach', ['SEASCAPE']], ['Simonside', ['LANDSCAPE']]]);
        expect(typed([COAST, FELL, FALLS], 'SEASCAPE', partial).map((s) => s.locationName))
          .toEqual(['Bamburgh Beach']);
      });

      it('DOES NOT run for a type the window never offered as a chip', () => {
        // Same pairing as the rating floor: `typeOptionsFor` returns nothing when every spot is one
        // type, so a stale id must not quietly empty the list.
        expect(typed([FELL, NO_DRIVE], 'SEASCAPE')).toHaveLength(2);
      });
    });

    it('applies all three at once', () => {
      const kept = browseSpots({
        spots: [COAST, FELL, FALLS, UNRATED],
        limitMinutes: 90,
        ratingFloorId: '4',
        typeId: 'SEASCAPE',
        typesByName: TYPES,
      });
      expect(kept.map((s) => s.locationName)).toEqual(['Bamburgh Beach']);
    });
  });

  describe('the empty line names a control only where relaxing it alone would help', () => {
    // Two spots, deliberately disjoint on every axis: the waterfall is far and 5★, the coast is
    // near and 1★. So each control can be shown to be the ONLY one that would help.
    const NEAR_POOR = spot({ key: 'a', locationName: 'Blyth Beach', rating: 1, driveMinutes: 20 });
    const FAR_GOOD = spot({ key: 'b', locationName: 'High Force', rating: 5, driveMinutes: 200 });
    const TWO = new Map([['Blyth Beach', ['SEASCAPE']], ['High Force', ['WATERFALL']]]);
    const line = (over) => browseEmptyLine({
      spots: [NEAR_POOR, FAR_GOOD],
      limitMinutes: null,
      ratingFloorId: ANY_RATING_ID,
      typeId: ANY_TYPE_ID,
      typesByName: TWO,
      ...over,
    });

    it('names the reach alone when it is the only control set', () => {
      expect(line({ spots: [FAR_GOOD], limitMinutes: 45 }))
        .toBe('Nothing matches — widen the reach.');
    });

    it('names the floor alone when it is the only control set', () => {
      expect(line({ spots: [NEAR_POOR], ratingFloorId: '4' }))
        .toBe('Nothing matches — drop the rating floor.');
    });

    it('names both when either one alone would work', () => {
      // Reach 45 keeps only the 1★ coast; the 4★ floor then removes it. Widening finds the 5★
      // waterfall, and dropping the floor keeps the coast — so both are honest suggestions.
      expect(line({ limitMinutes: 45, ratingFloorId: '4' }))
        .toBe('Nothing matches — widen the reach or drop the rating floor.');
    });

    it('names the type alongside whatever else would work, never alone', () => {
      // "Clear the type" cannot be the sole suggestion, and that is a property of the derivation
      // rather than a gap in the fixture: a chip is only offered when a spot carries it, so with
      // no other filter set the list is non-empty by construction.
      expect(line({ limitMinutes: 45, typeId: 'WATERFALL' }))
        .toBe('Nothing matches — widen the reach or clear the type.');
    });

    it('names ONE of three set controls, when only that one would work', () => {
      // This is the whole value of testing each relaxation rather than reading which controls are
      // set. All three are narrowing, and only the floor is worth touching: widening the reach
      // finds the 5★ waterfall, which the SEASCAPE chip then removes; clearing the type restores
      // the coast, which the 5★ floor then removes. The design's fixed two-control line and a
      // naive "name whatever is set" would both send the reader to two dead controls.
      expect(line({ limitMinutes: 45, ratingFloorId: '5', typeId: 'SEASCAPE' }))
        .toBe('Nothing matches — drop the rating floor.');
    });

    it('does NOT name a reach tier that gated nothing — the normal first-run state', () => {
      // Every drive time unknown, so the tier is a visible no-op (§2.5 rule 1). Telling this
      // reader to widen it sends them to the one control that cannot help them.
      const noDrives = [
        spot({ key: 'a', locationName: 'Blyth Beach', rating: 1, driveMinutes: null }),
        spot({ key: 'b', locationName: 'High Force', rating: 1, driveMinutes: null }),
      ];
      expect(browseEmptyLine({
        spots: noDrives, limitMinutes: 45, ratingFloorId: '4', typeId: ANY_TYPE_ID,
        typesByName: TWO,
      })).toBe('Nothing matches — drop the rating floor.');
    });

    it('suggests nothing when every control is already loose', () => {
      expect(browseEmptyLine({
        spots: [], limitMinutes: null, ratingFloorId: ANY_RATING_ID, typeId: ANY_TYPE_ID,
        typesByName: TWO,
      })).toBe('No spots in this window.');
    });

    it('suggests nothing when no single relaxation would help', () => {
      // Both controls are set, and undoing either one on its own still leaves nothing — so naming
      // one would send the reader to a control that changes nothing.
      expect(browseEmptyLine({
        spots: [NEAR_POOR], limitMinutes: 10, ratingFloorId: '4', typeId: ANY_TYPE_ID,
        typesByName: TWO,
      })).toBe('No spots in this window.');
    });
  });

  describe('the count states a second number only when it means something', () => {
    it('reads "N of M" when the filters withheld some', () => {
      expect(browseCountLine(7, 22)).toBe('7 of 22');
    });

    it('reads a plain count when nothing was withheld', () => {
      expect(browseCountLine(7, 7)).toBe('7 spots');
    });

    it('is singular at one', () => {
      expect(browseCountLine(1, 1)).toBe('1 spot');
    });

    it('is plural at zero', () => {
      expect(browseCountLine(0, 0)).toBe('0 spots');
    });
  });

  describe('sheetOffersMore — the trigger appears only where the sheet can differ', () => {
    const card = (allSpots, drawn = allSpots) => ({ allSpots, spots: drawn });

    it('is false for a window with no spots at all', () => {
      expect(sheetOffersMore(card([]), TYPES)).toBe(false);
      expect(sheetOffersMore(undefined, TYPES)).toBe(false);
    });

    it('is true when the reach lens withheld some, on that branch alone', () => {
      // Both unrated, both untyped and only two of them, so the other three clauses are all false
      // and the reach one is the only thing that can carry it.
      const a = spot({ key: 'a', locationName: 'Druridge', rating: null });
      const b = spot({ key: 'b', locationName: 'Cresswell', rating: null });
      expect(sheetOffersMore(card([a, b], [a, b]), new Map())).toBe(false);
      expect(sheetOffersMore(card([a, b], [a]), new Map())).toBe(true);
    });

    it('is true when there are more spots than the strip shows at once', () => {
      const four = [1, 2, 3, 4].map((n) => spot({ key: `k${n}`, locationName: `L${n}`, rating: null }));
      expect(sheetOffersMore(card(four), new Map())).toBe(true);
    });

    it('is true when something is rated, because the floor can act', () => {
      expect(sheetOffersMore(card([COAST]), new Map())).toBe(true);
    });

    it('is true when two types are present, because the type control can act', () => {
      const a = spot({ key: 'a', locationName: 'Bamburgh Beach', rating: null });
      const b = spot({ key: 'b', locationName: 'High Force', rating: null });
      expect(sheetOffersMore(card([a, b]), TYPES)).toBe(true);
    });

    it('is FALSE for three unrated single-type spots the lens never touched', () => {
      // The one state where a dialog would open onto exactly what the reader is already looking at.
      const three = ['Simonside', 'Wastwater', 'Nowhere'].map((locationName, i) => spot({
        key: `k${i}`, locationName, rating: null,
      }));
      expect(sheetOffersMore(card(three), TYPES)).toBe(false);
    });
  });
});
