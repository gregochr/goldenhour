import { describe, it, expect } from 'vitest';
import {
  otherWindow, isNightRow, tideSummary, tideVisible,
} from '../utils/mapPeek.js';

/**
 * `utils/mapPeek.js`'s pure logic (map-mobile-sheet-plan.md §3 M2 task 3, §3 M3 task 4).
 * `otherWindow`'s four named cases: forward non-Poor, wrap, all-Poor fallback, night row.
 * `tideSummary`'s own named cases: High (no arrow), Mid rising/falling (arrow), the dimmed-count
 * clause at N > 0, and its absence at N = 0.
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

describe('tideSummary (map-mobile-sheet-plan.md §3 M3 task 4)', () => {
  it('High — no arrow, whatever the direction', () => {
    expect(tideSummary({ state: 'HIGH', direction: 'FALLING' }, 0)).toBe('High');
  });

  it('Low — no arrow, whatever the direction', () => {
    expect(tideSummary({ state: 'LOW', direction: 'RISING' }, 0)).toBe('Low');
  });

  it('Mid, rising — an up arrow', () => {
    expect(tideSummary({ state: 'MID', direction: 'RISING' }, 0)).toBe('Mid ↑');
  });

  it('Mid, falling — a down arrow', () => {
    expect(tideSummary({ state: 'MID', direction: 'FALLING' }, 0)).toBe('Mid ↓');
  });

  it('the dimmed-count clause appears only when N > 0', () => {
    expect(tideSummary({ state: 'HIGH', direction: 'FALLING' }, 6)).toBe('High · 6 dim');
    expect(tideSummary({ state: 'MID', direction: 'RISING' }, 6)).toBe('Mid ↑ · 6 dim');
  });

  it('no chip at 0 — omitted entirely, never "· 0 dim"', () => {
    expect(tideSummary({ state: 'HIGH', direction: 'FALLING' }, 0)).toBe('High');
    expect(tideSummary({ state: 'HIGH', direction: 'FALLING' })).toBe('High');
  });

  it('null when there is no served tide state at all', () => {
    expect(tideSummary(null, 3)).toBeNull();
    expect(tideSummary({ state: null }, 3)).toBeNull();
  });
});

describe('tideVisible (map-mobile-sheet-plan.md §3 M5 task 1)', () => {
  const TIERS = ['WORTH_IT', 'MAYBE', 'STAND_DOWN', 'AWAITING', null];

  describe('mode: off', () => {
    it('is always false, whatever the other three inputs say', () => {
      for (const tideAvailable of [true, false]) {
        for (const hasCoastalInView of [true, false]) {
          for (const tier of TIERS) {
            expect(tideVisible({
              mode: 'off', tideAvailable, hasCoastalInView, tier,
            })).toBe(false);
          }
        }
      }
    });
  });

  describe('mode: always', () => {
    it('ignores hasCoastalInView and tier — only tideAvailable decides', () => {
      for (const hasCoastalInView of [true, false]) {
        for (const tier of TIERS) {
          expect(tideVisible({
            mode: 'always', tideAvailable: true, hasCoastalInView, tier,
          })).toBe(true);
          expect(tideVisible({
            mode: 'always', tideAvailable: false, hasCoastalInView, tier,
          })).toBe(false);
        }
      }
    });

    it('still needs a tide — a night row (tideAvailable: false) never shows, whatever the tier', () => {
      expect(tideVisible({
        mode: 'always', tideAvailable: false, hasCoastalInView: true, tier: 'WORTH_IT',
      })).toBe(false);
    });
  });

  describe('mode: auto', () => {
    it('requires all three: tide available, coast in view, and Maybe-or-better', () => {
      expect(tideVisible({
        mode: 'auto', tideAvailable: true, hasCoastalInView: true, tier: 'WORTH_IT',
      })).toBe(true);
      expect(tideVisible({
        mode: 'auto', tideAvailable: true, hasCoastalInView: true, tier: 'MAYBE',
      })).toBe(true);
    });

    it('hides on Poor (STAND_DOWN)', () => {
      expect(tideVisible({
        mode: 'auto', tideAvailable: true, hasCoastalInView: true, tier: 'STAND_DOWN',
      })).toBe(false);
    });

    it('hides on a null or AWAITING verdict — unscored is not a positive test passing (D-6)', () => {
      expect(tideVisible({
        mode: 'auto', tideAvailable: true, hasCoastalInView: true, tier: null,
      })).toBe(false);
      expect(tideVisible({
        mode: 'auto', tideAvailable: true, hasCoastalInView: true, tier: 'AWAITING',
      })).toBe(false);
    });

    it('hides with no coastal spot in the padded viewport, even on a Worth-it window', () => {
      expect(tideVisible({
        mode: 'auto', tideAvailable: true, hasCoastalInView: false, tier: 'WORTH_IT',
      })).toBe(false);
    });

    it('hides with no served tide at all (a night row, or a solar row with none)', () => {
      expect(tideVisible({
        mode: 'auto', tideAvailable: false, hasCoastalInView: true, tier: 'WORTH_IT',
      })).toBe(false);
    });
  });

  it('the full 3 × 2 × 2 × 5 table has no case outside the rules above', () => {
    // Exhaustive sweep: every case is explained by one of the three modes' own rule.
    for (const mode of ['auto', 'always', 'off']) {
      for (const tideAvailable of [true, false]) {
        for (const hasCoastalInView of [true, false]) {
          for (const tier of TIERS) {
            const result = tideVisible({
              mode, tideAvailable, hasCoastalInView, tier,
            });
            let expected;
            if (mode === 'off') expected = false;
            else if (mode === 'always') expected = tideAvailable;
            else expected = tideAvailable && hasCoastalInView && (tier === 'WORTH_IT' || tier === 'MAYBE');
            expect(result).toBe(Boolean(expected));
          }
        }
      }
    }
  });
});
