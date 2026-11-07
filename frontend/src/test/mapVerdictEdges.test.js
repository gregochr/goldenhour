import { describe, it, expect } from 'vitest';
import {
  buildEvVerdicts, buildReachVerdictIndex, buildRegionVerdictIndex, buildShapedLeadIndex,
  keyOf, regionNamesOf, shapedTierRank, verdictRegionLabel, windowVerdict,
} from '../utils/mapVerdict.js';

/**
 * Edge cases of the Map tab's verdict module that the happy-path suites do not reach: null and
 * malformed entries inside the served trees (the indexes must skip them, not throw), the shaped
 * window's ranking when the lead region is not the sort winner, the served-order pick's tie, the
 * scope-name filtering, and the label's defaults.
 */

const DATE = '2026-10-27';

/** A served region record with a finite mean, eligible by default. */
function region(regionName, overrides = {}) {
  return {
    regionName,
    displayVerdict: 'MAYBE',
    meanRating: 3,
    sampleSufficient: true,
    scoredLocationCount: 3,
    slots: [{ canopy: false }],
    ...overrides,
  };
}

/** An index holding the given records for DATE|SUNSET. */
function indexOf(...records) {
  return new Map(records.map((r) => [keyOf(DATE, 'SUNSET', r.regionName), r]));
}

describe('buildRegionVerdictIndex tolerates malformed trees', () => {
  it('skips null days, null summaries and null or nameless regions without throwing', () => {
    const lakes = region('The Lakes');
    const index = buildRegionVerdictIndex([
      null,
      undefined,
      { date: DATE, eventSummaries: [null, { targetType: 'SUNSET', regions: [null, { slots: [] }, lakes] }] },
    ]);

    expect([...index.keys()]).toEqual([keyOf(DATE, 'SUNSET', 'The Lakes')]);
    expect(index.get(keyOf(DATE, 'SUNSET', 'The Lakes'))).toBe(lakes);
  });

  it('skips a day with no date even when it carries summaries, and a summary with no targetType', () => {
    const index = buildRegionVerdictIndex([
      { eventSummaries: [{ targetType: 'SUNSET', regions: [region('A')] }] },
      { date: DATE, eventSummaries: [{ regions: [region('B')] }] },
    ]);

    expect(index.size).toBe(0);
  });

  it('keeps the first record when a key repeats', () => {
    const first = region('A', { meanRating: 1 });
    const index = buildRegionVerdictIndex([
      { date: DATE, eventSummaries: [{ targetType: 'SUNSET', regions: [first, region('A', { meanRating: 5 })] }] },
    ]);

    expect(index.get(keyOf(DATE, 'SUNSET', 'A'))).toBe(first);
  });
});

describe('buildShapedLeadIndex', () => {
  it('skips null days, null summaries and a day with no date', () => {
    const index = buildShapedLeadIndex([
      null,
      { shapedLeadRegion: 'x', eventSummaries: [{ targetType: 'SUNSET', shapedLeadRegion: 'Undated' }] },
      { date: DATE, eventSummaries: [null, { targetType: 'SUNSET', shapedLeadRegion: 'The Lakes' }] },
    ]);

    expect([...index.entries()]).toEqual([[`${DATE}|SUNSET`, 'The Lakes']]);
  });

  it('indexes only a summary with a targetType and a non-empty string lead', () => {
    const index = buildShapedLeadIndex([
      {
        date: DATE,
        eventSummaries: [
          { shapedLeadRegion: 'NoTargetType' },
          { targetType: 'SUNRISE', shapedLeadRegion: 5 },
          { targetType: 'SUNRISE', shapedLeadRegion: '' },
          { targetType: 'SUNRISE' },
          { targetType: 'SUNSET', shapedLeadRegion: 'Wales' },
        ],
      },
    ]);

    expect([...index.entries()]).toEqual([[`${DATE}|SUNSET`, 'Wales']]);
  });
});

describe('buildReachVerdictIndex', () => {
  it('skips null windows and nameless regions, ranks by position and keeps the first duplicate', () => {
    const { index, windowKeys } = buildReachVerdictIndex([
      null,
      { date: DATE },
      { targetType: 'SUNSET' },
      {
        date: DATE,
        targetType: 'SUNSET',
        regions: [null, { displayVerdict: 'MAYBE' }, region('A'), region('B'), region('A', { meanRating: 5 })],
      },
    ]);

    expect([...windowKeys]).toEqual([`${DATE}|SUNSET`]);
    expect([...index.keys()]).toEqual([keyOf(DATE, 'SUNSET', 'A'), keyOf(DATE, 'SUNSET', 'B')]);
    expect(index.get(keyOf(DATE, 'SUNSET', 'A')).reachRank).toBe(2);
    expect(index.get(keyOf(DATE, 'SUNSET', 'A')).meanRating).toBe(3);
    expect(index.get(keyOf(DATE, 'SUNSET', 'B')).reachRank).toBe(3);
  });

  it('tolerates non-array windows and a window whose regions are not an array', () => {
    expect(buildReachVerdictIndex('nope').index.size).toBe(0);
    const { index, windowKeys } = buildReachVerdictIndex([{ date: DATE, targetType: 'SUNSET', regions: 'x' }]);
    expect(index.size).toBe(0);
    expect([...windowKeys]).toEqual([`${DATE}|SUNSET`]);
  });
});

describe('shapedTierRank', () => {
  it('ranks the served tiers in order and puts an unknown or missing tier after all of them', () => {
    expect(['WORTH_IT', 'MAYBE', 'UNRATED', 'STAND_DOWN', 'AWAITING'].map(shapedTierRank)).toEqual([0, 1, 2, 3, 4]);
    expect(shapedTierRank('BOGUS')).toBe(5);
    expect(shapedTierRank(undefined)).toBe(5);
  });
});

describe('windowVerdict on a shaped window (shapedLeads Map)', () => {
  const verdictOf = (records, shapedLeads, regionsInScope) => windowVerdict({
    index: indexOf(...records),
    date: DATE,
    targetType: 'SUNSET',
    regionsInScope: regionsInScope ?? records.map((r) => r.regionName),
    shapedLeads,
  });

  it('returns the served lead even when another region sorts above it', () => {
    const lead = region('Lakes', { displayVerdict: 'MAYBE', sampleSufficient: false });
    const other = region('Cumbria', { displayVerdict: 'WORTH_IT' });

    const v = verdictOf([other, lead], new Map([[`${DATE}|SUNSET`, 'Lakes']]));

    expect(v.regionName).toBe('Lakes');
    expect(v.tier).toBe('MAYBE');
  });

  it('with no lead, ranks eligible before ineligible even against a better tier', () => {
    const ineligible = region('Alpha', { displayVerdict: 'WORTH_IT', sampleSufficient: false });
    const eligible = region('Zeta', { displayVerdict: 'MAYBE' });

    expect(verdictOf([ineligible, eligible], new Map()).regionName).toBe('Zeta');
    expect(verdictOf([eligible, ineligible], new Map()).regionName).toBe('Zeta');
  });

  it('with no lead, ranks the better tier first, ahead of the name order', () => {
    const maybe = region('Alpha', { displayVerdict: 'MAYBE' });
    const worth = region('Zeta', { displayVerdict: 'WORTH_IT' });

    const v = verdictOf([maybe, worth], new Map());

    expect(v.regionName).toBe('Zeta');
    expect(v.tier).toBe('WORTH_IT');
    expect(v.sharingCount).toBe(0);
  });

  it('with no lead and equal tiers, falls back to the name order', () => {
    const v = verdictOf([region('Zeta'), region('Alpha')], new Map());

    expect(v.regionName).toBe('Alpha');
    expect(v.sharingCount).toBe(1);
  });

  it('ignores a region with no scored locations, and returns null when none is scored', () => {
    const unscored = region('Alpha', { displayVerdict: 'WORTH_IT', scoredLocationCount: 0 });
    const scored = region('Zeta', { displayVerdict: 'STAND_DOWN' });

    expect(verdictOf([unscored, scored], new Map()).regionName).toBe('Zeta');
    expect(verdictOf([unscored], new Map())).toBeNull();
    expect(verdictOf([unscored, scored], new Map([[`${DATE}|SUNSET`, 'Alpha']])).regionName).toBe('Zeta');
  });
});

describe('windowVerdict scope and ranking edges', () => {
  it('drops falsy scope names before counting the scope', () => {
    const v = windowVerdict({
      index: indexOf(region('A')),
      date: DATE,
      targetType: 'SUNSET',
      regionsInScope: ['A', null, '', undefined],
    });

    expect(v.scopedRegionCount).toBe(1);
    expect(v.allInScope).toBe(false);
  });

  it('returns null for a scope of only falsy names, and for a non-array scope', () => {
    const index = indexOf(region('A'));
    const base = { index, date: DATE, targetType: 'SUNSET' };

    expect(windowVerdict({ ...base, regionsInScope: [null, ''] })).toBeNull();
    expect(windowVerdict({ ...base, regionsInScope: 'A' })).toBeNull();
  });

  it('never consults the index for a non-array scope', () => {
    const anyKey = { get: () => region('Any') };

    expect(windowVerdict({
      index: anyKey, date: DATE, targetType: 'SUNSET', regionsInScope: 'A',
    })).toBeNull();
    expect(windowVerdict({
      index: anyKey, date: DATE, targetType: 'SUNSET', regionsInScope: undefined,
    })).toBeNull();
  });

  it('ranks by the eligible-mean order when only some records carry a reachRank', () => {
    const ranked = region('Ranked', { reachRank: 0, meanRating: 3 });
    const unranked = region('Unranked', { meanRating: 4.5 });

    const v = windowVerdict({
      index: indexOf(ranked, unranked),
      date: DATE,
      targetType: 'SUNSET',
      regionsInScope: ['Ranked', 'Unranked'],
    });

    expect(v.regionName).toBe('Unranked');
  });

  it('ranks by the served order when every record carries a reachRank, keeping the first of a tie', () => {
    const first = region('First', { reachRank: 1, meanRating: 2 });
    const second = region('Second', { reachRank: 1, meanRating: 5 });
    const better = region('Better', { reachRank: 0, meanRating: 1 });
    const args = { date: DATE, targetType: 'SUNSET' };

    expect(windowVerdict({
      ...args, index: indexOf(first, second), regionsInScope: ['First', 'Second'],
    }).regionName).toBe('First');
    expect(windowVerdict({
      ...args, index: indexOf(first, second, better), regionsInScope: ['First', 'Second', 'Better'],
    }).regionName).toBe('Better');
  });

  it('skips a reach-ranked record with no finite mean', () => {
    const noMean = region('NoMean', { reachRank: 0, meanRating: null });
    const withMean = region('WithMean', { reachRank: 1, meanRating: 2 });

    const v = windowVerdict({
      index: indexOf(noMean, withMean),
      date: DATE,
      targetType: 'SUNSET',
      regionsInScope: ['NoMean', 'WithMean'],
    });

    expect(v.regionName).toBe('WithMean');
  });
});

describe('regionNamesOf', () => {
  it('skips null spots, spots without a region and repeats, keeping first-seen order', () => {
    expect(regionNamesOf([
      null, undefined, {}, { regionName: '' }, { regionName: 'B' }, { regionName: 'A' }, { regionName: 'B' },
    ])).toEqual(['B', 'A']);
    expect(regionNamesOf(null)).toEqual([]);
  });
});

describe('buildEvVerdicts edges', () => {
  const solar = (id, extra = {}) => ({ id, kind: 'solar', date: DATE, eventType: 'SUNSET', ...extra });

  it('skips null rows, non-solar rows and filler rows', () => {
    const out = buildEvVerdicts({
      events: [null, undefined, solar('a', { kind: 'tide' }), solar('b', { served: false }), solar('c')],
      index: indexOf(region('A')),
      regionsInScope: ['A'],
    });

    expect([...out.keys()]).toEqual(['c']);
  });

  it('returns an empty map without an index, even when the reach answer could supply one', () => {
    const reach = {
      ...buildReachVerdictIndex([{ date: DATE, targetType: 'SUNSET', regions: [region('A')] }]),
      regionsInScope: ['A'],
      tierMinutes: 45,
    };

    const out = buildEvVerdicts({ events: [solar('a')], index: null, regionsInScope: ['A'], reach });

    expect(out.size).toBe(0);
  });

  it('tolerates events that are not an array', () => {
    expect(buildEvVerdicts({ events: 'x', index: indexOf(region('A')), regionsInScope: ['A'] }).size).toBe(0);
  });
});

describe('verdictRegionLabel', () => {
  it('defaults to the area wording for an everywhere verdict', () => {
    expect(verdictRegionLabel({ allInScope: true })).toBe('everywhere in your area');
    expect(verdictRegionLabel({ allInScope: true }, { scopeIsArea: false })).toBe('everywhere');
    expect(verdictRegionLabel({ allInScope: true, scope: { tierMinutes: 45 } })).toBe('everywhere');
  });

  it('is empty for no verdict and for a verdict with no region name', () => {
    expect(verdictRegionLabel(null)).toBe('');
    expect(verdictRegionLabel(undefined)).toBe('');
    expect(verdictRegionLabel({ allInScope: false, regionName: null, sharingCount: 0 })).toBe('');
    expect(verdictRegionLabel({ allInScope: false, regionName: null, sharingCount: 2 })).toBe('');
  });

  it('names the region, with the sharing count when others share the tier', () => {
    expect(verdictRegionLabel({ regionName: 'Wales', sharingCount: 0 })).toBe('Wales');
    expect(verdictRegionLabel({ regionName: 'Wales', sharingCount: 2 })).toBe('Wales +2');
  });
});
