import { describe, it, expect } from 'vitest';
import {
  buildEvVerdicts, buildRegionVerdictIndex, buildShapedLeadIndex, windowVerdict,
} from '../utils/mapVerdict.js';

/**
 * The Map pill's verdict for a body the server shaped by the reader's sub-region picks (S5a-1). The
 * shaper strips every region `meanRating`, which `pickTopEligibleRegion` ranks by and skips without, so
 * the window's lead region is served as `shapedLeadRegion` and read here instead.
 */

const DATE = '2026-10-27';

/** A shaped region record: a verdict and a scored count, and NO meanRating (the server removed it). */
function shapedRegion(regionName, displayVerdict, overrides = {}) {
  return {
    regionName,
    displayVerdict,
    scoredLocationCount: 3,
    slots: [{ canopy: false, gated: true }],
    ...overrides,
  };
}

function shapedDays(regions, shapedLeadRegion) {
  return [{
    date: DATE,
    eventSummaries: [{
      targetType: 'SUNSET', regions, ...(shapedLeadRegion ? { shapedLeadRegion } : {}),
    }],
  }];
}

describe('buildShapedLeadIndex', () => {
  it('keys the served lead region on date|targetType, and skips a window with none', () => {
    const index = buildShapedLeadIndex([
      { date: DATE, eventSummaries: [{ targetType: 'SUNSET', shapedLeadRegion: 'The Lakes' }, { targetType: 'SUNRISE' }] },
    ]);

    expect(index.get(`${DATE}|SUNSET`)).toBe('The Lakes');
    expect(index.has(`${DATE}|SUNRISE`)).toBe(false);
  });

  it('is empty for an unshaped body, which never carries the field', () => {
    expect(buildShapedLeadIndex(shapedDays([shapedRegion('The Lakes', 'WORTH_IT')])).size).toBe(0);
    expect(buildShapedLeadIndex(null).size).toBe(0);
  });
});

describe('windowVerdict — a shaped window keeps its verdict word', () => {
  const regions = [
    shapedRegion('The Lakes', 'MAYBE'),
    shapedRegion('Northumberland', 'WORTH_IT'),
    shapedRegion('The Dales', 'WORTH_IT'),
  ];
  const days = shapedDays(regions, 'Northumberland');
  const index = buildRegionVerdictIndex(days);
  const regionsInScope = ['The Lakes', 'Northumberland', 'The Dales'];

  it('loses the verdict without the served lead: no region carries a mean to rank by', () => {
    // The unshaped rule on a shaped record: this is the defect the served lead region exists to fix.
    expect(windowVerdict({
      index, date: DATE, targetType: 'SUNSET', regionsInScope,
    })).toBeNull();
  });

  it('names the served lead region and its tier, and tallies the regions sharing it', () => {
    const verdict = windowVerdict({
      index,
      date: DATE,
      targetType: 'SUNSET',
      regionsInScope,
      shapedLeads: buildShapedLeadIndex(days),
    });

    expect(verdict).toMatchObject({
      tier: 'WORTH_IT', regionName: 'Northumberland', sharingCount: 1, allInScope: false, scopedRegionCount: 3,
    });
  });

  it('falls back to the best tier in scope, then the name, when the lead lies outside the scope', () => {
    const verdict = windowVerdict({
      index,
      date: DATE,
      targetType: 'SUNSET',
      regionsInScope: ['The Lakes', 'The Dales'],
      shapedLeads: buildShapedLeadIndex(days),
    });

    expect(verdict.regionName).toBe('The Dales');
    expect(verdict.tier).toBe('WORTH_IT');
  });

  it('breaks a tier tie on a case-insensitive code-unit order, as the server does, not on the locale', () => {
    const tied = shapedDays([
      shapedRegion('\u00C4bc', 'WORTH_IT'),
      shapedRegion('abd', 'WORTH_IT'),
      shapedRegion('Borders', 'WORTH_IT'),
      shapedRegion('alnwick', 'WORTH_IT'),
    ], null);
    const verdict = (names) => windowVerdict({
      index: buildRegionVerdictIndex(tied),
      date: DATE,
      targetType: 'SUNSET',
      regionsInScope: names,
      shapedLeads: buildShapedLeadIndex(tied),
    });

    // localeCompare would put the umlaut A with the A's and name "\u00C4bc" first.
    expect(verdict(['\u00C4bc', 'abd']).regionName).toBe('abd');
    expect(verdict(['Borders', 'alnwick']).regionName).toBe('alnwick');
  });

  it('puts a verdict-eligible region ahead of a better tier that is not', () => {
    const mixed = shapedDays([
      shapedRegion('Thin', 'WORTH_IT'),
      shapedRegion('Sound', 'MAYBE', { sampleSufficient: true }),
    ], null);

    const verdict = windowVerdict({
      index: buildRegionVerdictIndex(mixed),
      date: DATE,
      targetType: 'SUNSET',
      regionsInScope: ['Thin', 'Sound'],
      shapedLeads: buildShapedLeadIndex(mixed),
    });

    expect(verdict.regionName).toBe('Sound');
  });

  it('states no verdict for a window nothing was scored in, as an unshaped window with no mean has none', () => {
    const unscored = shapedDays([shapedRegion('The Lakes', 'AWAITING', { scoredLocationCount: 0 })], 'The Lakes');

    expect(windowVerdict({
      index: buildRegionVerdictIndex(unscored),
      date: DATE,
      targetType: 'SUNSET',
      regionsInScope: ['The Lakes'],
      shapedLeads: buildShapedLeadIndex(unscored),
    })).toBeNull();
  });

  it('leaves an unshaped body exactly as it was: a mean-ranked pick, the lead index ignored when null', () => {
    const unshaped = shapedDays([
      shapedRegion('The Lakes', 'MAYBE', { meanRating: 3.1 }),
      shapedRegion('Northumberland', 'WORTH_IT', { meanRating: 4.2 }),
    ], null);

    const verdict = windowVerdict({
      index: buildRegionVerdictIndex(unshaped),
      date: DATE,
      targetType: 'SUNSET',
      regionsInScope: ['The Lakes', 'Northumberland'],
      shapedLeads: null,
    });

    expect(verdict.regionName).toBe('Northumberland');
  });
});

describe('buildEvVerdicts — the lead index reaches every solar row', () => {
  it('gives a shaped solar row its verdict and still skips a night row', () => {
    const days = shapedDays([shapedRegion('Northumberland', 'WORTH_IT')], 'Northumberland');
    const events = [
      { id: 's1', kind: 'solar', date: DATE, eventType: 'SUNSET' },
      { id: 'n1', kind: 'astro', date: DATE, eventType: 'ASTRO' },
    ];

    const verdicts = buildEvVerdicts({
      events,
      index: buildRegionVerdictIndex(days),
      regionsInScope: ['Northumberland'],
      shapedLeads: buildShapedLeadIndex(days),
    });

    expect(verdicts.get('s1').tier).toBe('WORTH_IT');
    expect(verdicts.has('n1')).toBe(false);
  });
});
