import { describe, it, expect } from 'vitest';
import {
  ANY_RATING_ID,
  ANY_TYPE_ID,
  browseEmptyLine,
  browseSpots,
  sheetOffersMore,
  spotTypes,
} from '../utils/windowSpotBrowse.js';

function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Beach',
    regionName: 'Northumberland',
    rating: 4,
    driveMinutes: 30,
    ...overrides,
  };
}

/** Three spots, two of which are landscape and one seascape, in a deliberately unsorted order. */
function roster() {
  const spots = [
    spot({ key: 'a', locationName: 'Alpha', rating: 2 }),
    spot({ key: 'b', locationName: 'Bravo', rating: 5 }),
    spot({ key: 'c', locationName: 'Charlie', rating: 3 }),
  ];
  const typesByName = new Map([
    ['Alpha', ['LANDSCAPE']],
    ['Bravo', ['SEASCAPE']],
    ['Charlie', ['LANDSCAPE']],
  ]);
  return { spots, typesByName };
}

const call = (over = {}) => {
  const { spots, typesByName } = roster();
  return browseSpots({
    spots, limitMinutes: null, ratingFloorId: ANY_RATING_ID, typeId: ANY_TYPE_ID, typesByName, ...over,
  });
};

describe('spotTypes', () => {
  it('reads a single type given as a bare string, and nothing for a place the roster does not know', () => {
    const typesByName = new Map([['Alpha', 'LANDSCAPE']]);
    expect(spotTypes({ locationName: 'Alpha' }, typesByName)).toEqual(['LANDSCAPE']);
    expect(spotTypes({ locationName: 'Zed' }, typesByName)).toEqual([]);
    expect(spotTypes({ locationName: 'Alpha' }, null)).toEqual([]);
  });
});

describe('browseSpots — the type filter', () => {
  it('filters to an offered type', () => {
    expect(call({ typeId: 'LANDSCAPE' }).map((s) => s.key)).toEqual(['c', 'a']);
  });

  it('ignores a type that no spot in the window has, rather than emptying the list', () => {
    expect(call({ typeId: 'WATERFALL' }).map((s) => s.key)).toEqual(['b', 'c', 'a']);
  });

  it('ignores a type id this build does not know', () => {
    expect(call({ typeId: 'nonsense' }).map((s) => s.key)).toEqual(['b', 'c', 'a']);
  });
});

describe('browseSpots — the rating floor', () => {
  it('applies a known floor', () => {
    expect(call({ ratingFloorId: '4' }).map((s) => s.key)).toEqual(['b']);
  });

  it('ignores a floor id this build does not know rather than failing', () => {
    expect(call({ ratingFloorId: 'nonsense' }).map((s) => s.key)).toEqual(['b', 'c', 'a']);
  });
});

describe('browseSpots — the input list', () => {
  it('sorts a copy, leaving the list it was given in its original order', () => {
    const { spots, typesByName } = roster();
    const result = browseSpots({
      spots, limitMinutes: null, ratingFloorId: ANY_RATING_ID, typeId: ANY_TYPE_ID, typesByName,
    });
    expect(result.map((s) => s.key)).toEqual(['b', 'c', 'a']);
    expect(spots.map((s) => s.key)).toEqual(['a', 'b', 'c']);
    expect(result).not.toBe(spots);
  });
});

describe('sheetOffersMore', () => {
  it('offers more when the card holds a spot but shows none, even with no spots list at all', () => {
    expect(sheetOffersMore({ allSpots: [spot({ rating: null })] }, new Map())).toBe(true);
  });

  it('offers nothing for a card with no spots or no card', () => {
    expect(sheetOffersMore({ allSpots: [] }, new Map())).toBe(false);
    expect(sheetOffersMore(null, new Map())).toBe(false);
    expect(sheetOffersMore({}, new Map())).toBe(false);
  });
});

describe('browseEmptyLine — only suggests a way out of a filter that is actually set', () => {
  const base = () => {
    const { spots, typesByName } = roster();
    return {
      spots, limitMinutes: null, ratingFloorId: ANY_RATING_ID, typeId: ANY_TYPE_ID, typesByName,
    };
  };

  it('suggests nothing when no filter is set, even though the list is not empty', () => {
    expect(browseEmptyLine(base())).toBe('No spots in this window.');
  });

  it('suggests widening the reach only when a reach limit is set', () => {
    const args = base();
    args.spots = args.spots.map((s) => ({ ...s, driveMinutes: 90 }));
    expect(browseEmptyLine({ ...args, limitMinutes: 45 })).toBe('Nothing matches — widen the reach.');
    expect(browseEmptyLine({ ...args, limitMinutes: null })).toBe('No spots in this window.');
  });

  it('suggests dropping the rating floor only when a floor is set', () => {
    const args = base();
    args.spots = args.spots.map((s) => ({ ...s, rating: 2 }));
    expect(browseEmptyLine({ ...args, ratingFloorId: '5' })).toBe('Nothing matches — drop the rating floor.');
    expect(browseEmptyLine({ ...args, ratingFloorId: ANY_RATING_ID })).toBe('No spots in this window.');
  });

  it('suggests clearing the type only when a type is chosen', () => {
    const args = base();
    // Two types are present (so the chip is offered); the chosen one has no spot inside the reach.
    args.spots = [
      spot({ key: 'a', locationName: 'Alpha', driveMinutes: 90 }),
      spot({ key: 'b', locationName: 'Bravo', driveMinutes: 20 }),
    ];
    expect(browseEmptyLine({ ...args, typeId: 'LANDSCAPE', limitMinutes: 45 }))
      .toBe('Nothing matches — widen the reach or clear the type.');
    expect(browseEmptyLine({ ...args, typeId: ANY_TYPE_ID, limitMinutes: null })).toBe('No spots in this window.');
  });

  it('joins several suggestions with commas and "or"', () => {
    const args = base();
    args.spots = [
      spot({ key: 'a', locationName: 'Alpha', rating: 5, driveMinutes: 90 }),
      spot({ key: 'b', locationName: 'Bravo', rating: 2, driveMinutes: 20 }),
    ];
    expect(browseEmptyLine({ ...args, limitMinutes: 45, ratingFloorId: '5' }))
      .toBe('Nothing matches — widen the reach or drop the rating floor.');
    // Three at once: with all three filters set, relaxing any one of them frees a spot.
    args.spots = [
      spot({ key: 'a', locationName: 'Alpha', rating: 5, driveMinutes: 90 }),
      spot({ key: 'b', locationName: 'Bravo', rating: 5, driveMinutes: 20 }),
      spot({ key: 'c', locationName: 'Charlie', rating: 2, driveMinutes: 20 }),
    ];
    expect(browseEmptyLine({ ...args, limitMinutes: 45, ratingFloorId: '5', typeId: 'LANDSCAPE' }))
      .toBe('Nothing matches — widen the reach, drop the rating floor or clear the type.');
  });
});
