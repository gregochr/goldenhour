import { describe, it, expect } from 'vitest';
import { otherWindow, isNightRow } from '../utils/mapPeek.js';

/**
 * `utils/mapPeek.js`'s pure logic (map-mobile-sheet-plan.md §3 M2 task 3). `otherWindow`'s four
 * named cases: forward non-Poor, wrap, all-Poor fallback, night row.
 */

function row(id, kind = 'solar') {
  return { id, kind };
}

describe('otherWindow', () => {
  it('forward non-Poor: picks the very next row when it is not STAND_DOWN', () => {
    const events = [row('a'), row('b'), row('c')];
    const verdicts = new Map([
      ['a', { tier: 'WORTH_IT' }],
      ['b', { tier: 'MAYBE' }],
      ['c', { tier: 'STAND_DOWN' }],
    ]);
    expect(otherWindow(events, 0, verdicts)).toEqual(row('b'));
  });

  it('wraps past the end of the list back to the rows before the active one', () => {
    const events = [row('a'), row('b'), row('c')];
    const verdicts = new Map([
      ['a', { tier: 'WORTH_IT' }],
      ['b', { tier: 'STAND_DOWN' }],
      ['c', { tier: 'STAND_DOWN' }],
    ]);
    // Active is 'b' (index 1); 'c' (STAND_DOWN) is skipped, wrapping to 'a' (WORTH_IT).
    expect(otherWindow(events, 1, verdicts)).toEqual(row('a'));
  });

  it('all-Poor fallback: every OTHER row is STAND_DOWN — falls back to the very next row regardless', () => {
    const events = [row('a'), row('b'), row('c')];
    const verdicts = new Map([
      ['a', { tier: 'STAND_DOWN' }],
      ['b', { tier: 'STAND_DOWN' }],
      ['c', { tier: 'STAND_DOWN' }],
    ]);
    // Active is 'a' — every other row is STAND_DOWN, so the fallback is the very next row, 'b'.
    expect(otherWindow(events, 0, verdicts)).toEqual(row('b'));
  });

  it('night row: eligible even with no verdict entry at all (astro/aurora carry no per-region rollup)', () => {
    const events = [row('a'), row('night-1', 'astro')];
    const verdicts = new Map([
      ['a', { tier: 'STAND_DOWN' }],
    ]);
    // 'night-1' has no entry in `verdicts` at all — a missing tier is not STAND_DOWN, so it is
    // picked over wrapping back to the STAND_DOWN 'a'.
    expect(otherWindow(events, 0, verdicts)).toEqual(row('night-1', 'astro'));
  });

  it('returns null with fewer than two rows — there is no "other" window to name', () => {
    expect(otherWindow([], 0, new Map())).toBeNull();
    expect(otherWindow([row('a')], 0, new Map())).toBeNull();
  });

  it('is defensive against an out-of-range active index (-1, "no match")', () => {
    const events = [row('a'), row('b')];
    const verdicts = new Map([['a', { tier: 'WORTH_IT' }], ['b', { tier: 'WORTH_IT' }]]);
    expect(() => otherWindow(events, -1, verdicts)).not.toThrow();
    expect(otherWindow(events, -1, verdicts)).not.toBeNull();
  });

  it('treats a solar row with no verdict entry (unscored, or AWAITING) as "not STAND_DOWN" — the honest reading of "non-Poor"', () => {
    const events = [row('a'), row('b')];
    // No verdicts map at all.
    expect(otherWindow(events, 0, null)).toEqual(row('b'));
  });
});

describe('isNightRow', () => {
  it('is true for astro/aurora kinds, false for solar', () => {
    expect(isNightRow(row('a', 'astro'))).toBe(true);
    expect(isNightRow(row('a', 'aur'))).toBe(true);
    expect(isNightRow(row('a', 'solar'))).toBe(false);
  });

  it('is true for a null/undefined row-ish input rather than throwing', () => {
    expect(isNightRow(null)).toBe(true);
    expect(isNightRow(undefined)).toBe(true);
  });
});
