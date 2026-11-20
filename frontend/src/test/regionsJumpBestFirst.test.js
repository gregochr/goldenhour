import { describe, it, expect } from 'vitest';
import { sortBestFirst } from '../utils/regionsJump.js';

/**
 * `sortBestFirst` (glance-to-map-plan.md G1): the Regions menu's order for a solar window. It ranks the regions the
 * drilldown has a row for with the drilldown's own comparator and leaves every other region where the drive order put
 * it. It must never invent a verdict, rank a region the window did not answer for, or touch a night's order.
 */
const jump = (name, extra = {}) => ({
  name, driveMinutes: null, beyondArea: false, bestRating: null, ...extra,
});
const panel = (name, extra = {}) => ({
  name,
  tier: 'WORTH_IT',
  verdictLabel: 'Worth it',
  meanRating: 4,
  bestRating: 5,
  verdictEligible: true,
  placeCount: 4,
  atFourPlus: 2,
  ...extra,
});

describe('sortBestFirst', () => {
  it('returns the drive order untouched with no panel rows (a night, an unserved window, a closed menu)', () => {
    const rows = [jump('B'), jump('A')];
    expect(sortBestFirst(rows, [])).toBe(rows);
    expect(sortBestFirst(rows, null)).toBe(rows);
    expect(sortBestFirst(rows, undefined)).toBe(rows);
  });

  it('tolerates a missing jump list', () => {
    expect(sortBestFirst(null, [panel('A')])).toEqual([]);
    expect(sortBestFirst(undefined, [])).toEqual([]);
  });

  it('ranks by the served mean, then the name, with eligible regions before the rest', () => {
    const rows = [jump('Alpha'), jump('Beta'), jump('Gamma'), jump('Delta')];
    const ranked = sortBestFirst(rows, [
      panel('Alpha', { meanRating: 3 }),
      panel('Beta', { meanRating: 4.5 }),
      panel('Gamma', { meanRating: 4.5 }),
      panel('Delta', {
        meanRating: 4.9, verdictEligible: false, tier: 'UNRATED', verdictLabel: 'Too few rated',
      }),
    ]);
    expect(ranked.map((r) => r.name)).toEqual(['Beta', 'Gamma', 'Alpha', 'Delta']);
  });

  it('puts a too-few-rated region after every rated one and never calls it Poor', () => {
    const ranked = sortBestFirst([jump('Thin'), jump('Poorish')], [
      panel('Thin', {
        meanRating: 5, verdictEligible: false, tier: 'UNRATED', verdictLabel: 'Too few rated',
      }),
      panel('Poorish', { meanRating: 1.5, tier: 'STAND_DOWN', verdictLabel: 'Poor' }),
    ]);
    expect(ranked.map((r) => r.name)).toEqual(['Poorish', 'Thin']);
    expect(ranked[1].verdictLabel).toBe('Too few rated');
    expect(ranked[1].tier).toBe('UNRATED');
  });

  it('carries the served tier, word, ceiling and the "k of m" counts off the one panel record', () => {
    const [row] = sortBestFirst([jump('A', { bestRating: 3, driveMinutes: 30 })], [
      panel('A', { bestRating: 4, atFourPlus: 1, placeCount: 3 }),
    ]);
    expect(row).toMatchObject({
      name: 'A',
      driveMinutes: 30,
      bestRating: 4,
      tier: 'WORTH_IT',
      verdictLabel: 'Worth it',
      atFourPlus: 1,
      placeCount: 3,
      showCounts: true,
    });
  });

  it('keeps a region with no panel row after the ranked ones, in its drive order, undecorated', () => {
    const ranked = sortBestFirst(
      [jump('Near', { bestRating: 5 }), jump('Ranked'), jump('Far', { bestRating: 2 })],
      [panel('Ranked')],
    );
    expect(ranked.map((r) => r.name)).toEqual(['Ranked', 'Near', 'Far']);
    expect(ranked[1]).toEqual(jump('Near', { bestRating: 5 }));
    expect(ranked[1].verdictLabel).toBeUndefined();
  });

  it('withholds the counts for a region with no place the window could rate', () => {
    const [row] = sortBestFirst([jump('A')], [panel('A', { placeCount: 0, atFourPlus: 0 })]);
    expect(row.showCounts).toBe(false);
  });

  describe('a shaped body', () => {
    const shaped = (name, extra = {}) => panel(name, { shaped: true, hasPicks: true, ...extra });

    it('keeps the panel\'s pick-first order, which has no mean to rank by', () => {
      const ranked = sortBestFirst([jump('A'), jump('B'), jump('C')], [
        shaped('C', { meanRating: null, verdictEligible: false }),
        shaped('A', { meanRating: null }),
        shaped('B', { meanRating: null, hasPicks: false, bestRating: null }),
      ]);
      expect(ranked.map((r) => r.name)).toEqual(['C', 'A', 'B']);
    });

    it('marks a region the reader holds no pick in as outside, with no ceiling and no counts, as the drilldown does', () => {
      const [row] = sortBestFirst([jump('B', { bestRating: 3 })], [
        shaped('B', { hasPicks: false, bestRating: null, outsideCount: 4 }),
      ]);
      expect(row).toMatchObject({
        bestRating: null, showCounts: false, outside: true, outsideCount: 4, verdictLabel: 'Worth it',
      });
    });

    it('takes the picked region\'s served ceiling and counts', () => {
      const [row] = sortBestFirst([jump('A', { bestRating: 1 })], [shaped('A', { bestRating: 4 })]);
      expect(row.bestRating).toBe(4);
      expect(row.showCounts).toBe(true);
      expect(row.outside).toBe(false);
      expect(row.outsideCount).toBe(0);
    });
  });
});
