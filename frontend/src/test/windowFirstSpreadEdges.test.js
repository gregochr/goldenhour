import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  spreadBandsSpoken, spreadCountLayout, spreadQualifier, unratedPhrase,
} from '../utils/windowFirstSpread.js';

/**
 * Edges of the spread helpers the main suites leave unpinned: module-level tables (loaded fresh in
 * each test so a change to them is seen), and the defensive paths for an absent spread, a null bar
 * or a tier list holding an unlimited-less tier. Fixtures are built inside each `it`.
 */

describe('module-level tables', () => {
  let mod;
  beforeEach(async () => {
    vi.resetModules();
    mod = await import('../utils/windowFirstSpread.js');
  });

  it('lists the five stars, one to five, in order', () => {
    expect(mod.SPREAD_STARS).toEqual([1, 2, 3, 4, 5]);
  });

  it('builds a spread with a count slot for each star', () => {
    const spread = mod.buildSpread([{ rating: 1 }, { rating: 5 }, { rating: 5 }]);
    expect(spread.counts).toEqual([1, 0, 0, 0, 2]);
    expect(spread.max).toBe(2);
  });

  it('qualifies an unlimited reach as "any drive" in both written and spoken form', () => {
    expect(mod.spreadQualifier({ reachLimitMinutes: null, withinReach: true })).toEqual({
      text: 'any drive', spoken: 'any drive',
    });
    expect(mod.spreadQualifier({ reachLimitMinutes: null, withinReach: false })).toEqual({
      text: 'any drive', spoken: 'any drive',
    });
  });
});

describe('unratedPhrase — an absent spread', () => {
  it('is empty rather than throwing, whatever the separator', () => {
    expect(unratedPhrase(undefined)).toBe('');
    expect(unratedPhrase(null, ' / ')).toBe('');
    expect(unratedPhrase({}, ' / ')).toBe('');
  });

  it('prefixes the separator when something is unrated', () => {
    expect(unratedPhrase({ unrated: 3 }, ' / ')).toBe(' / 3 not yet rated');
  });
});

describe('spreadCountLayout — a null bar', () => {
  it('counts a null bar as the one-character zero rather than throwing', () => {
    expect(spreadCountLayout([null, { count: 1200 }], null)).toMatchObject({ chars: 4, small: true });
    expect(spreadCountLayout([null], null)).toMatchObject({ chars: 1, small: false });
  });
});

describe('spreadBandsSpoken — a spread with no counts', () => {
  it('says nothing for an empty object, an absent spread or counts of zero', () => {
    expect(spreadBandsSpoken({})).toBe('');
    expect(spreadBandsSpoken(undefined)).toBe('');
    expect(spreadBandsSpoken({ counts: [0, 0, 0, 0, 0] })).toBe('');
  });
});

describe('spreadQualifier — an undefined limit', () => {
  it('never matches a tier whose own limit is undefined', () => {
    const tiers = [{ limitMinutes: undefined, label: '1 hour', shortLabel: '1h' }];
    expect(spreadQualifier({ withinReach: true }, tiers)).toBeNull();
  });

  it('names a matching tier in written and spoken form', () => {
    const tiers = [{ limitMinutes: 45, label: '45 min', shortLabel: '45m' }];
    expect(spreadQualifier({ reachLimitMinutes: 45, withinReach: true }, tiers)).toEqual({
      text: 'within 45m', spoken: 'within 45 min',
    });
  });
});
