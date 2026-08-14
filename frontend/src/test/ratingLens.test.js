import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  ANY_RATING_ID,
  LENS_RATING_FLOORS,
  PLAN_RATING_KEY,
  RATING_FLOORS,
  gateSpotsByRating,
  lensRatingFloorById,
  ratingFloorById,
  readStoredRatingFloorId,
  writeStoredRatingFloorId,
} from '../utils/ratingLens.js';

/** A spot as `buildWindowSpots` emits one — only the rating matters to this module. */
const spot = (rating, key = String(rating)) => ({ key, locationName: `L${key}`, rating });

describe('ratingLens — the vocabulary', () => {
  it('runs any → 3★+ → 4★+ → 5★, loosest first', () => {
    expect(RATING_FLOORS.map((f) => f.id)).toEqual(['any', '3', '4', '5']);
    expect(RATING_FLOORS.map((f) => f.label))
      .toEqual(['Any rating', '3★+', '4★+', '5★']);
  });

  it('writes no + on the ceiling, because there is no sixth star', () => {
    expect(ratingFloorById('5').label).toBe('5★');
  });

  it('gives the loosest step a null threshold rather than a zero', () => {
    // A number would make "Any rating" a comparison that happens to pass everything, and an
    // unrated spot fails every comparison — so zero would silently drop the unrated.
    expect(ratingFloorById(ANY_RATING_ID).min).toBeNull();
  });

  it('offers the bar three steps and withholds the ceiling', () => {
    // A page-wide 5★ floor usually empties six windows at once. The sheet keeps the step; the bar
    // must never be able to STORE one it could not then draw as pressed.
    expect(LENS_RATING_FLOORS.map((f) => f.id)).toEqual(['any', '3', '4']);
  });

  it('has one lookup per question, and they disagree exactly at 5★', () => {
    expect(ratingFloorById('5')).not.toBeNull();
    expect(lensRatingFloorById('5')).toBeNull();
    expect(lensRatingFloorById('4').min).toBe(4);
  });

  it('resolves nothing for an id no build has', () => {
    expect(ratingFloorById('2')).toBeNull();
    expect(ratingFloorById(undefined)).toBeNull();
    expect(lensRatingFloorById('2')).toBeNull();
  });
});

describe('ratingLens — the gate', () => {
  it('keeps a spot exactly at the floor, so "4★+" includes a 4', () => {
    expect(gateSpotsByRating([spot(4)], 4).map((s) => s.rating)).toEqual([4]);
  });

  it('drops everything below it', () => {
    expect(gateSpotsByRating([spot(2), spot(3), spot(5)], 4).map((s) => s.rating)).toEqual([5]);
  });

  it('DROPS an unrated spot, which is the opposite of what the reach gate does', () => {
    // Deliberately not symmetrical with `gateSpotsByReach`, where an unknown drive time passes
    // every tier. "4★ and up" is a claim about what was measured, and an unknown does not meet it.
    expect(gateSpotsByRating([spot(null, 'u'), spot(4)], 4).map((s) => s.key)).toEqual(['4']);
  });

  it('does not coerce a null rating to zero — the coercion that rendered ☆☆☆☆☆ once', () => {
    // With `(rating ?? 0) >= min` this passes for min 0; the explicit null test is what makes an
    // unrated spot fail every floor rather than only the ones above zero.
    expect(gateSpotsByRating([spot(null, 'u')], 3)).toEqual([]);
  });

  it('returns the input untouched under a null minimum, identity included', () => {
    const spots = [spot(1), spot(null, 'u')];
    expect(gateSpotsByRating(spots, null)).toBe(spots);
  });

  it('preserves the order it was handed', () => {
    // It filters; it does not sort. `compareSpots` is the one ordering, applied elsewhere.
    const ordered = [spot(3, 'a'), spot(5, 'b'), spot(4, 'c')];
    expect(gateSpotsByRating(ordered, 3).map((s) => s.key)).toEqual(['a', 'b', 'c']);
  });
});

describe('ratingLens — persistence', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

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
    writeStoredRatingFloorId('4');
    expect(Object.keys(JSON.parse(localStorage.getItem(PLAN_RATING_KEY)))).toEqual(['rating']);
  });

  it('reads null when there is no key at all', () => {
    expect(readStoredRatingFloorId()).toBeNull();
  });

  it('reads null rather than throwing on unparseable JSON', () => {
    localStorage.setItem(PLAN_RATING_KEY, 'not json');
    expect(readStoredRatingFloorId()).toBeNull();
  });

  it('reads null for a floor id no build offers', () => {
    localStorage.setItem(PLAN_RATING_KEY, JSON.stringify({ rating: '2' }));
    expect(readStoredRatingFloorId()).toBeNull();
  });

  it('reads null for the 5★ the sheet-owned floor could once write', () => {
    // The one migration the move to the bar needs, and it degrades in the safe direction: an
    // unfiltered page rather than six empty windows above a bar with no chip pressed. It is NOT
    // covered by the test above — '5' is still a valid floor, just not one this control offers.
    localStorage.setItem(PLAN_RATING_KEY, JSON.stringify({ rating: '5' }));
    expect(ratingFloorById('5')).not.toBeNull();
    expect(readStoredRatingFloorId()).toBeNull();
  });

  it('does not use the reach key — the two expire differently and must not share one object', () => {
    writeStoredRatingFloorId('4');
    expect(localStorage.getItem('photocast.planReach')).toBeNull();
  });

  it('swallows a write that throws, because the choice still applies for this session', () => {
    // Private browsing and a full quota both throw here. The sibling module pins this guard
    // (`reachLens.test.js`), and the sheet used to pin it for this one before the floor moved to
    // the bar — so without this the move would have quietly dropped a guard rather than relocating
    // it. `afterEach` restores the stub even if the assertion throws first.
    //
    // ⚠️ The owner is RESOLVED rather than assumed. `setup.js` installs a plain-object localStorage
    // only when jsdom does not supply one, so `Storage.prototype` is the owner in one environment
    // and bypassed in the other — `WindowFirstShellTabs.test.jsx` records CI catching exactly this,
    // where both spellings pass locally and one records nothing. A `Storage.prototype` spy that is
    // bypassed never throws, so `.not.toThrow()` passes without entering the guard at all: the
    // assertion would be vacuous in the environment that matters. Same resolution as
    // `swrCache.test.js`, plus a call assertion so a bypassed spy fails loudly instead of silently.
    const owner = Object.prototype.hasOwnProperty.call(localStorage, 'setItem')
      ? localStorage
      : (Object.getPrototypeOf(localStorage) ?? Storage.prototype);
    const spy = vi.spyOn(owner, 'setItem').mockImplementation(() => { throw new Error('quota'); });

    expect(() => writeStoredRatingFloorId('4')).not.toThrow();
    expect(spy).toHaveBeenCalled();
  });
});
