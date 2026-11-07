import { describe, it, expect } from 'vitest';
import { NOT_SEEN, myRatingOf, ratedCountByWindow } from '../utils/outcomeRatingJoin.js';
import { rowKey } from '../hooks/useOutcomeRatings.js';

const D1 = '2026-10-14';

/** A hook-shaped ratings object over {@code mine} entries that has read every date. */
function ratingsOf(entries) {
  return {
    mine: new Map(entries),
    readFailed: false,
    covers: () => true,
    wasRead: () => true,
  };
}

describe('ratedCountByWindow — roster rows without an id', () => {
  it('never counts a record keyed "null" or "undefined" for a roster row that has no id', () => {
    const ratings = ratingsOf([
      [`null|${D1}|SUNSET`, { rating: 3 }],
      [`undefined|${D1}|SUNSET`, { rating: 4 }],
      [rowKey(1, D1, 'SUNSET'), { rating: 5 }],
    ]);
    const counts = ratedCountByWindow({
      ratings,
      windows: [{ date: D1, targetType: 'SUNSET' }],
      locations: [{ id: 1 }, { id: null }, { name: 'no id at all' }],
    });
    expect(counts.get(`solar:${D1}:SUNSET`)).toBe(1);
  });

  it('counts a record for a place whose id is 0 or a numeric string', () => {
    const ratings = ratingsOf([
      [rowKey(0, D1, 'SUNRISE'), { rating: 2 }],
      [rowKey('7', D1, 'SUNRISE'), { rating: 2 }],
    ]);
    const counts = ratedCountByWindow({
      ratings,
      windows: [{ date: D1, targetType: 'SUNRISE' }],
      locations: [{ id: 0 }, { id: 7 }],
    });
    expect(counts.get(`solar:${D1}:SUNRISE`)).toBe(2);
  });
});

describe('myRatingOf — a missing location id', () => {
  it('is null for a null or undefined id even when a record is keyed with that text', () => {
    const ratings = ratingsOf([
      [`null|${D1}|SUNSET`, { rating: 4 }],
      [`undefined|${D1}|SUNSET`, { rating: 2 }],
    ]);
    expect(myRatingOf(ratings, null, D1, 'SUNSET')).toBeNull();
    expect(myRatingOf(ratings, undefined, D1, 'SUNSET')).toBeNull();
  });
});

describe('myRatingOf — which stored ratings count as stars', () => {
  const rated = (rating) => myRatingOf(
    ratingsOf([[rowKey(1, D1, 'SUNSET'), { rating }]]),
    1,
    D1,
    'SUNSET',
  );

  it('returns the whole stars 1 to 5 as they are (both ends included)', () => {
    expect([1, 2, 3, 4, 5].map(rated)).toEqual([1, 2, 3, 4, 5]);
  });

  it('refuses anything outside 1 to 5 or not a whole number', () => {
    expect(rated(0)).toBeNull();
    expect(rated(6)).toBeNull();
    expect(rated(-2)).toBeNull();
    expect(rated(2.5)).toBeNull();
    expect(rated(0.5)).toBeNull();
    expect(rated(5.5)).toBeNull();
    expect(rated('3')).toBeNull();
    expect(rated(Number.NaN)).toBeNull();
  });

  it('reads a null rating as not seen and a missing record as null', () => {
    expect(rated(null)).toBe(NOT_SEEN);
    expect(myRatingOf(ratingsOf([]), 1, D1, 'SUNSET')).toBeNull();
  });
});
