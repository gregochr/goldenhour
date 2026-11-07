// @ts-nocheck
import { describe, it, expect } from 'vitest';
import {
  bestReachOf, buildWindowCards, eligibleRegions, formatRegionMean, pickTopEligibleRegion, verdictEligible,
} from '../utils/windowFirstCards.js';

/**
 * Edge behaviour of the window cards that {@code windowFirstCards.test.js} leaves open: the
 * defensive handling of empty or non-numeric records in the exported region helpers, the tie rules
 * of the region argmax, the reach counts a card carries when a tier removes places, and the figures
 * a card re-points to when the page is scoped to one region.
 */

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';

function events(...pairs) {
  return pairs.map(([date, targetType]) => ({ date, targetType }));
}

function windowOf(regions, window = {}, targetType = 'SUNSET') {
  return {
    events: events([TODAY, targetType]),
    days: [{
      date: TODAY,
      eventSummaries: [{
        targetType, regions, unregioned: [], window: { verdict: 'WORTH_IT', badges: [], ...window },
      }],
    }],
  };
}

const slot = (overrides = {}) => ({
  locationId: 1, locationName: 'Somewhere', canopy: false, ...overrides,
});

describe('bestReachOf edges', () => {
  it('stops at an empty entry rather than reading past it', () => {
    expect(bestReachOf([null, { rating: 4 }])).toEqual({ bestReach: null, withheld: false });
  });

  it('names nothing for a missing pool', () => {
    expect(bestReachOf(undefined)).toEqual({ bestReach: null, withheld: false });
  });
});

describe('eligibleRegions edges', () => {
  it('does not let a region with no slots stop an all-wood window keeping its woods', () => {
    const woods = { regionName: 'Woods', slots: [slot({ canopy: true })] };
    const bare = { regionName: 'Bare' };
    expect(eligibleRegions({ regions: [woods, bare] })).toEqual([woods, bare]);
  });

  it('keeps an empty entry in the region list, as it keeps a region with no slots', () => {
    const sky = { regionName: 'Sky', slots: [slot()] };
    expect(eligibleRegions({ regions: [null, sky] })).toEqual([null, sky]);
  });

  it('returns nothing for a missing summary', () => {
    expect(eligibleRegions(null)).toEqual([]);
  });
});

describe('pickTopEligibleRegion edges', () => {
  it('skips an empty entry', () => {
    const a = { regionName: 'A', meanRating: 3 };
    expect(pickTopEligibleRegion([null, a])).toBe(a);
  });

  it('keeps the first of two regions that tie on eligibility, mean and name', () => {
    const first = { regionName: 'A', meanRating: 3, id: 1 };
    const second = { regionName: 'A', meanRating: 3, id: 2 };
    expect(pickTopEligibleRegion([first, second])).toBe(first);
  });

  it('breaks a tie on the mean by the earlier name, whatever the order given', () => {
    const a = { regionName: 'Alpha', meanRating: 3 };
    const b = { regionName: 'Bravo', meanRating: 3 };
    expect(pickTopEligibleRegion([b, a])).toBe(a);
    expect(pickTopEligibleRegion([a, b])).toBe(a);
  });

  it('answers null for a list that is not a list', () => {
    expect(pickTopEligibleRegion(null)).toBeNull();
  });
});

describe('verdictEligible and formatRegionMean edges', () => {
  it('reads a missing region as not eligible', () => {
    expect(verdictEligible(null)).toBe(false);
    expect(verdictEligible(undefined)).toBe(false);
    expect(verdictEligible({ forcedSample: true })).toBe(true);
  });

  it.each([[null], [undefined], [Number.NaN], [Number.POSITIVE_INFINITY], ['3.5']])(
    'prints nothing for the mean %s',
    (mean) => {
      expect(formatRegionMean(mean)).toBeNull();
    },
  );

  it('always keeps one decimal', () => {
    expect(formatRegionMean(4)).toBe('4.0');
    expect(formatRegionMean(3.46)).toBe('3.5');
  });
});

describe('buildWindowCards edges', () => {
  it('builds cards when no set of travel days is given', () => {
    const { events: ev, days } = windowOf([]);
    const cards = buildWindowCards(ev, days, TODAY, TOMORROW, undefined);
    expect(cards.map((c) => c.key)).toEqual([`${TODAY}:SUNSET`]);
  });

  it('builds a card, with the not-yet-scored verdict, when the briefing carries no days', () => {
    const [card] = buildWindowCards(events([TODAY, 'SUNSET']), null, TODAY, TOMORROW, new Set());
    expect(card.verdict).toBe('AWAITING');
  });

  it('gives a window the day has no summary for the not-yet-scored verdict, not a sibling\'s', () => {
    const { days } = windowOf([], { verdict: 'MAYBE' }, 'SUNRISE');
    const [card] = buildWindowCards(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());
    expect(card.verdict).toBe('AWAITING');
  });

  describe('a verdict word over regions with no mean', () => {
    const live = { regionName: 'Live', displayVerdict: 'STAND_DOWN', slots: [slot()] };
    const blank = { regionName: 'Blank', displayVerdict: 'STAND_DOWN', slots: [] };
    const build = (regions) => {
      const { events: ev, days } = windowOf(regions, { verdict: 'STAND_DOWN' });
      return buildWindowCards(ev, days, TODAY, TOMORROW, new Set())[0];
    };

    it('comes from the region with content, whichever order the regions are served in', () => {
      expect(build([live, blank]).verdictBlanked).toBe(false);
      expect(build([blank, live]).verdictBlanked).toBe(false);
    });

    it('is blanked when every region is', () => {
      expect(build([blank, { ...blank, regionName: 'Other' }]).verdictBlanked).toBe(true);
    });
  });

  describe('what a tier took out of reach', () => {
    it('counts the rated places removed for distance apart from the unroutable ones', () => {
      const regions = [{
        regionName: 'R',
        displayVerdict: 'WORTH_IT',
        slots: [
          slot({ locationId: 1, locationName: 'Near', claudeRating: 4 }),
          slot({ locationId: 2, locationName: 'Far', claudeRating: 5 }),
          slot({ locationId: 3, locationName: 'Cut off', claudeRating: 3 }),
        ],
      }];
      const reachById = new Map([
        [1, { driveMinutes: 30 }],
        [2, { driveMinutes: 100 }],
        [3, { driveMinutes: null, driveStatus: 'UNROUTABLE' }],
      ]);
      const { events: ev, days } = windowOf(regions);
      const [card] = buildWindowCards(
        ev, days, TODAY, TOMORROW, new Set(), reachById, { limitMinutes: 45, defaultLimitMinutes: null },
      );
      expect(card.pool.map((s) => s.locationName)).toEqual(['Near']);
      expect(card.reachUnroutableCount).toBe(1);
      expect(card.reachUnroutableRemoved).toBe(1);
      expect(card.reachFarRemoved).toBe(1);
    });
  });

  describe('scoped to one region whose mean is not a usable number', () => {
    it.each([[Number.NaN], [Number.POSITIVE_INFINITY], ['4.0'], [null]])(
      'names no hot region and prints no mean for %s',
      (meanRating) => {
        const regions = [{
          regionName: 'North',
          displayVerdict: 'WORTH_IT',
          sampleSufficient: true,
          meanRating,
          slots: [slot()],
        }];
        const { events: ev, days } = windowOf(regions);
        const [card] = buildWindowCards(
          ev, days, TODAY, TOMORROW, new Set(), undefined,
          { limitMinutes: null, defaultLimitMinutes: null, origin: { id: 1, name: 'North', baseName: 'North' } },
        );
        expect(card.hotRegionName).toBeNull();
        expect(card.topMeanRating).toBeNull();
        expect(card.verdictMean).toBeNull();
      },
    );

    it('names the region and its mean when the mean is a number', () => {
      const regions = [{
        regionName: 'North', displayVerdict: 'WORTH_IT', sampleSufficient: true, meanRating: 3.5, slots: [slot()],
      }];
      const { events: ev, days } = windowOf(regions);
      const [card] = buildWindowCards(
        ev, days, TODAY, TOMORROW, new Set(), undefined,
        { limitMinutes: null, defaultLimitMinutes: null, origin: { id: 1, name: 'North', baseName: 'North' } },
      );
      expect(card.hotRegionName).toBe('North');
      expect(card.topMeanRating).toBe(3.5);
      expect(card.verdictMean).toBe(3.5);
    });
  });
});
