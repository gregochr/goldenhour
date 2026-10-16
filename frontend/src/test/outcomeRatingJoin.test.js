/**
 * `utils/outcomeRatingJoin.js` — the two per-user joins of outcome-rating-plan.md §2 #6 (O4b): the
 * reader's rated-location count per past window, and their own record for one spot. Plain objects in,
 * plain values out. The thing worth pinning is the SILENCE: neither may state a fact the reader's
 * ratings cannot support (a date not read, a failed read, no ratings at all).
 */
import { describe, it, expect } from 'vitest';
import {
  NOT_SEEN, badgeAccessibleClause, badgeText, myRatingOf, ratedCountByWindow, ratingsKnownFor,
} from '../utils/outcomeRatingJoin.js';
import { rowKey } from '../hooks/useOutcomeRatings.js';

const D1 = '2026-10-14';
const D2 = '2026-10-15';

/** A hook-shaped ratings object over `mine` entries, covering [from, to]. */
function ratings(entries, { from = D1, to = D2, readFailed = false } = {}) {
  return {
    mine: new Map(entries),
    readFailed,
    covers: () => true,
    wasRead: (date) => date >= from && date <= to,
  };
}

const ROSTER = [{ id: 1 }, { id: 2 }, { id: 3 }];

describe('ratingsKnownFor', () => {
  it('is true for a covered date after a successful read', () => {
    expect(ratingsKnownFor(ratings([]), D1)).toBe(true);
  });

  it('is false for a date the read has not covered', () => {
    expect(ratingsKnownFor(ratings([]), '2026-10-01')).toBe(false);
  });

  it('trusts wasRead, never covers: a LITE reader\'s covers() is true for a date nobody asked about', () => {
    const lite = { ...ratings([]), covers: () => true, wasRead: () => false };
    expect(ratingsKnownFor(lite, D1)).toBe(false);
  });

  it('is false after a failed read even though covers() says the date was read', () => {
    expect(ratingsKnownFor(ratings([], { readFailed: true }), D1)).toBe(false);
  });

  it('is false with no ratings at all (a rewind)', () => {
    expect(ratingsKnownFor(null, D1)).toBe(false);
  });
});

describe('ratedCountByWindow', () => {
  const windows = [
    { date: D1, targetType: 'SUNRISE' },
    { date: D1, targetType: 'SUNSET' },
    { date: D2, targetType: 'SUNSET' },
  ];

  it('counts every record on a window — "not seen" (a null rating) included', () => {
    const counts = ratedCountByWindow({
      ratings: ratings([
        [rowKey(1, D1, 'SUNSET'), { rating: 4 }],
        [rowKey(2, D1, 'SUNSET'), { rating: null }],
        [rowKey(3, D1, 'SUNRISE'), { rating: 2 }],
      ]),
      windows,
      locations: ROSTER,
    });
    expect(counts.get(`solar:${D1}:SUNSET`)).toBe(2);
    expect(counts.get(`solar:${D1}:SUNRISE`)).toBe(1);
  });

  it('reads a covered window nobody rated as ZERO — a fact, so it prints "Not rated"', () => {
    const counts = ratedCountByWindow({ ratings: ratings([]), windows, locations: ROSTER });
    expect(counts.get(`solar:${D2}:SUNSET`)).toBe(0);
  });

  it('leaves a window out — absent, not zero — when its date has not been read', () => {
    const counts = ratedCountByWindow({
      ratings: ratings([], { from: D2, to: D2 }),
      windows,
      locations: ROSTER,
    });
    expect(counts.has(`solar:${D1}:SUNSET`)).toBe(false);
    expect(counts.has(`solar:${D2}:SUNSET`)).toBe(true);
  });

  it('is an empty map after a failed read, whatever `mine` holds', () => {
    const counts = ratedCountByWindow({
      ratings: ratings([[rowKey(1, D1, 'SUNSET'), { rating: 4 }]], { readFailed: true }),
      windows,
      locations: ROSTER,
    });
    expect(counts.size).toBe(0);
  });

  it('does not count a record for a place the roster no longer holds', () => {
    const counts = ratedCountByWindow({
      ratings: ratings([
        [rowKey(1, D1, 'SUNSET'), { rating: 4 }],
        [rowKey(99, D1, 'SUNSET'), { rating: 5 }],
      ]),
      windows,
      locations: ROSTER,
    });
    expect(counts.get(`solar:${D1}:SUNSET`)).toBe(1);
  });

  it('matches a string id against the roster\'s numeric one (keys are strings)', () => {
    const counts = ratedCountByWindow({
      ratings: ratings([[rowKey('1', D1, 'SUNSET'), { rating: 3 }]]),
      windows,
      locations: [{ id: 1 }],
    });
    expect(counts.get(`solar:${D1}:SUNSET`)).toBe(1);
  });

  it('is null with no ratings at all', () => {
    expect(ratedCountByWindow({ ratings: null, windows, locations: ROSTER })).toBeNull();
  });
});

describe('myRatingOf', () => {
  const mine = [
    [rowKey(1, D1, 'SUNSET'), { rating: 4 }],
    [rowKey(2, D1, 'SUNSET'), { rating: null }],
    [rowKey(3, D1, 'SUNSET'), { rating: 9 }],
  ];

  it('returns the star, NOT_SEEN for a null rating, and null for no record', () => {
    expect(myRatingOf(ratings(mine), 1, D1, 'SUNSET')).toBe(4);
    expect(myRatingOf(ratings(mine), 2, D1, 'SUNSET')).toBe(NOT_SEEN);
    expect(myRatingOf(ratings(mine), 1, D1, 'SUNRISE')).toBeNull();
    expect(myRatingOf(ratings(mine), 7, D1, 'SUNSET')).toBeNull();
  });

  it('ignores an out-of-range value rather than drawing "9★"', () => {
    expect(myRatingOf(ratings(mine), 3, D1, 'SUNSET')).toBeNull();
  });

  it('is null for a date not read, after a failed read, with no ratings, and with no location id', () => {
    expect(myRatingOf(ratings(mine, { from: D2, to: D2 }), 1, D1, 'SUNSET')).toBeNull();
    expect(myRatingOf(ratings(mine, { readFailed: true }), 1, D1, 'SUNSET')).toBeNull();
    expect(myRatingOf(null, 1, D1, 'SUNSET')).toBeNull();
    expect(myRatingOf(ratings(mine), null, D1, 'SUNSET')).toBeNull();
  });
});

describe('the badge words', () => {
  it('prints N★ or an en dash for not seen', () => {
    expect(badgeText(3)).toBe('3★');
    expect(badgeText(NOT_SEEN)).toBe('–');
  });

  it('appends the spoken clause with its comma, and nothing for no record', () => {
    expect(badgeAccessibleClause(3)).toBe(', you rated 3 out of 5');
    expect(badgeAccessibleClause(NOT_SEEN)).toBe(', you marked this not seen');
    expect(badgeAccessibleClause(null)).toBe('');
  });
});
