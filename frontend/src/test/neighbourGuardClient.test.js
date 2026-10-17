import { describe, it, expect } from 'vitest';
import {
  buildWindowCards, bestReachOf, NEIGHBOUR_NAMED_FLOOR,
} from '../utils/windowFirstCards.js';
import { buildWindowSpots } from '../utils/windowFirstSpots.js';
import { bestReachLine } from '../components/WindowFirstHeatStrip.jsx';
import { buildHeatStripCards } from '../utils/windowFirstStrip.js';
import { buildSearchGroups } from '../utils/planSearch.js';
import { buildSpread } from '../utils/windowFirstSpread.js';

/**
 * The neighbour guard on the Plan card (docs/engineering/neighbour-guard-plan.md §4 G3).
 *
 * <p>The server flags a served slot `neighbourOutlier: true` when it is rated far above several
 * places that see the same sky. The flag never removes a star: the client only declines to NAME
 * that place as the best reachable one. Everything here is a filter over that served flag.
 */

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';
const REGION = 'Hadrian\'s Wall';

const slot = (id, name, claudeRating, extra = {}) => ({
  locationId: id, locationName: name, claudeRating, canopy: false, ...extra,
});

const REACH = new Map([
  [1, { driveMinutes: 20, distanceMiles: 10 }],
  [2, { driveMinutes: 25, distanceMiles: 12 }],
  [3, { driveMinutes: 30, distanceMiles: 15 }],
]);

const days = (slots) => [{
  date: TODAY,
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: [{
      regionName: REGION, meanRating: 3, sampleSufficient: true, slots,
    }],
    unregioned: [],
    window: { verdict: 'MAYBE', badges: [] },
  }],
}];

const EVENTS = [{ date: TODAY, targetType: 'SUNSET' }];

const cardOf = (slots, reachById = REACH, lens = null) => buildWindowCards(
  EVENTS, days(slots), TODAY, TOMORROW, new Set(), reachById, lens,
)[0];

/** The strip descriptor of a card, i.e. what the line, the search row and the popup are fed. */
const descriptorOf = (card) => buildHeatStripCards(
  EVENTS, [card], new Set(), days([]), TODAY, TOMORROW,
)[0];

const lineOf = (card) => bestReachLine(descriptorOf(card));

const CAW_GAP = slot(1, 'Caw Gap', 4, { neighbourOutlier: true });
const CAWFIELDS = slot(2, 'Cawfields', 1);
const STEEL_RIGG = slot(3, 'Steel Rigg', 2);

describe('the named floor', () => {
  it('is 3, the twin of NeighbourOutlierGuard.NAMED_FLOOR', () => {
    expect(NEIGHBOUR_NAMED_FLOOR).toBe(3);
  });
});

describe('buildWindowSpots copies the flag', () => {
  const summary = (slots) => ({
    targetType: 'SUNSET', regions: [{ regionName: REGION, slots }], unregioned: [],
  });

  it('carries a served true onto the spot, and its star with it', () => {
    const spots = buildWindowSpots(summary([CAW_GAP, CAWFIELDS]), new Map());
    expect(spots.find((s) => s.locationName === 'Caw Gap')).toMatchObject({
      neighbourOutlier: true, rating: 4,
    });
  });

  it('leaves the key off an unflagged spot, never false', () => {
    const spots = buildWindowSpots(summary([CAW_GAP, CAWFIELDS]), new Map());
    const plain = spots.find((s) => s.locationName === 'Cawfields');
    expect('neighbourOutlier' in plain).toBe(false);
  });

  it('does not copy a served false', () => {
    const spots = buildWindowSpots(summary([slot(5, 'X', 3, { neighbourOutlier: false })]), new Map());
    expect('neighbourOutlier' in spots[0]).toBe(false);
  });
});

describe('bestReach under the guard', () => {
  it('skips an outlier to the next rated spot', () => {
    const card = cardOf([CAW_GAP, slot(2, 'Hotbank', 3)]);
    expect(card.bestReach.locationName).toBe('Hotbank');
    expect(card.bestReachWithheld).toBe(false);
  });

  it('skips more than one outlier in a row', () => {
    const card = cardOf([
      CAW_GAP, slot(2, 'Other Outlier', 4, { neighbourOutlier: true }), slot(3, 'Hotbank', 3),
    ]);
    expect(card.bestReach.locationName).toBe('Hotbank');
  });

  it('withholds when the spot after the skipped outlier is below the named floor', () => {
    const card = cardOf([CAW_GAP, CAWFIELDS, STEEL_RIGG]);
    expect(card.bestReach).toBeNull();
    expect(card.bestReachWithheld).toBe(true);
  });

  it('names the next spot at exactly the floor', () => {
    const card = cardOf([CAW_GAP, slot(2, 'Hotbank', NEIGHBOUR_NAMED_FLOOR)]);
    expect(card.bestReach.locationName).toBe('Hotbank');
    expect(card.bestReachWithheld).toBe(false);
  });

  it('withholds when every rated spot is an outlier', () => {
    const card = cardOf([CAW_GAP, slot(2, 'Other', 5, { neighbourOutlier: true })]);
    expect(card.bestReach).toBeNull();
    expect(card.bestReachWithheld).toBe(true);
  });

  it('withholds when the only spots after the outlier are unrated', () => {
    const card = cardOf([CAW_GAP, slot(2, 'Unrated', null)]);
    expect(card.bestReach).toBeNull();
    expect(card.bestReachWithheld).toBe(true);
  });

  it('is exactly today\'s head with no outlier, even when the head is below the floor', () => {
    // The floor applies only AFTER a skip: a lone 2★ head with nothing skipped is named as ever.
    const card = cardOf([CAWFIELDS, STEEL_RIGG]);
    expect(card.bestReach).toBe(card.pool[0]);
    expect(card.bestReach.locationName).toBe('Steel Rigg');
    expect(card.bestReachWithheld).toBe(false);
  });

  it('is null and not withheld for an unrated pool with no outlier', () => {
    const card = cardOf([slot(1, 'A', null), slot(2, 'B', null)]);
    expect(card.bestReach).toBeNull();
    expect(card.bestReachWithheld).toBe(false);
  });

  it('leaves the pool and the histogram counting the outlier', () => {
    const card = cardOf([CAW_GAP, CAWFIELDS, STEEL_RIGG]);
    expect(card.pool.map((s) => s.locationName)).toContain('Caw Gap');
    expect(card.pool).toHaveLength(3);
    expect(card.reachedTotal).toBe(3);
    const spread = buildSpread(card.pool);
    expect(spread).toMatchObject({ total: 3, rated: 3 });
    // Index 0 is 1★: the 4★ outlier is the bar at index 3.
    expect(spread.counts).toEqual([1, 1, 0, 1, 0]);
  });

  it('reads the pre-floor pool, so a rating floor above the named pick changes nothing', () => {
    // A 4★ outlier and a 3★ place under a 4★ floor: `spots` holds only the outlier, `reached` both.
    // Reading `spots` would skip the outlier and find nothing (withheld); the pool names the 3★.
    const card = cardOf(
      [CAW_GAP, slot(2, 'Hotbank', 3)], REACH,
      { limitMinutes: null, defaultLimitMinutes: 45, floorId: '4', minRating: 4 },
    );
    expect(card.spots.map((s) => s.locationName)).toEqual(['Caw Gap']);
    expect(card.bestReach.locationName).toBe('Hotbank');
    expect(card.bestReachWithheld).toBe(false);
  });

  it('judges only the reach-gated pool, so an outlier beyond the tier changes nothing', () => {
    const far = new Map([[1, { driveMinutes: 90, distanceMiles: 60 }], ...[...REACH].slice(1)]);
    const card = cardOf(
      [CAW_GAP, slot(2, 'Hotbank', 3)], far,
      { limitMinutes: 45, defaultLimitMinutes: 45, tierId: '45' },
    );
    expect(card.pool.map((s) => s.locationName)).toEqual(['Hotbank']);
    expect(card.bestReach.locationName).toBe('Hotbank');
    expect(card.bestReachWithheld).toBe(false);
  });
});

describe('bestReachOf, direct', () => {
  it('handles an empty or missing pool', () => {
    expect(bestReachOf([])).toEqual({ bestReach: null, withheld: false });
    expect(bestReachOf(undefined)).toEqual({ bestReach: null, withheld: false });
  });
});

describe('the "no clear pick" line', () => {
  const withheld = (reachById) => cardOf([CAW_GAP, CAWFIELDS, STEEL_RIGG], reachById);

  it('prints "no clear pick", muted, never "not scored yet"', () => {
    const line = lineOf(withheld());
    expect(line).toMatchObject({
      label: 'Best', value: 'no clear pick', muted: true, rating: null,
    });
    expect(line.value).not.toMatch(/not scored/);
    expect(line.spoken).not.toMatch(/not scored/);
  });

  it('speaks the label word first (WCAG 2.5.3)', () => {
    expect(lineOf(withheld()).spoken.startsWith('Best, no clear pick')).toBe(true);
  });

  it('speaks the reason too, with the reach rule', () => {
    expect(lineOf(withheld()).spoken)
      .toBe('Best, no clear pick: the top rating within reach is well above the places around it');
    const unmeasured = lineOf(withheld(new Map())).spoken;
    expect(unmeasured)
      .toBe('Best, no clear pick: the top rating here is well above the places around it');
    expect(unmeasured).not.toMatch(/within reach/);
  });

  it('says "within reach" in the title only with a measured drive', () => {
    expect(lineOf(withheld()).title)
      .toBe('The highest rating within reach is well above every place around it, so none is named.');
    const unmeasured = lineOf(withheld(new Map()));
    expect(unmeasured.title)
      .toBe('The highest rating here is well above every place around it, so none is named.');
    expect(unmeasured.title).not.toMatch(/within reach/);
  });

  it('beats the verdict-beyond-reach fall-through, which a rated pool would otherwise reach', () => {
    // A descriptor that would otherwise answer "Region · beyond 45 min" or "nothing in reach".
    const line = bestReachLine({
      bestReach: null,
      bestReachWithheld: true,
      // One rated (outlier) spot: with an EMPTY pool the `empty` branch would answer the same way
      // whatever the order, so only a populated pool can show that the withheld branch comes first.
      pool: [{ rating: 4, neighbourOutlier: true }],
      reachMeasured: true,
      reachLimitMinutes: 45,
      hotRegionName: REGION,
      topMeanRating: 3.5,
    });
    expect(line.value).toBe('no clear pick');
  });

  it('names the pick as ever when one survives', () => {
    const line = lineOf(cardOf([CAW_GAP, slot(2, 'Hotbank', 3)]));
    expect(line).toMatchObject({ value: 'Hotbank', rating: 3, muted: false });
  });
});

describe('the strip fold', () => {
  it('forwards bestReachWithheld beside bestReach', () => {
    const desc = descriptorOf(cardOf([CAW_GAP, CAWFIELDS, STEEL_RIGG]));
    expect(desc.bestReachWithheld).toBe(true);
    expect(desc.bestReach).toBeNull();
  });

  it('forwards false for an ordinary card and for an away day', () => {
    expect(descriptorOf(cardOf([CAWFIELDS])).bestReachWithheld).toBe(false);
    const [away] = buildHeatStripCards(
      EVENTS, [], new Set([TODAY]), days([]), TODAY, TOMORROW,
    );
    expect(away.bestReachWithheld).toBe(false);
  });
});

describe('the search figure', () => {
  const figureOf = (desc, reachById) => buildSearchGroups('', {
    windows: [{
      ...desc, dow: 'Tue', label: 'Tonight Sunset', time: '21:11', verdictLabel: 'Maybe', away: false,
    }],
    reachById,
  })[0].rows[0].figure;

  it('prints "no clear pick" for a withheld window, captioned only with a measured drive', () => {
    const desc = descriptorOf(cardOf([CAW_GAP, CAWFIELDS, STEEL_RIGG]));
    expect(figureOf(desc, REACH)).toMatchObject({ value: 'no clear pick', caption: 'in reach' });
    expect(figureOf(desc, new Map())).toMatchObject({ value: 'no clear pick', caption: null });
  });

  it('carries the reason as spoken text, with the reach rule', () => {
    const desc = descriptorOf(cardOf([CAW_GAP, CAWFIELDS, STEEL_RIGG]));
    expect(figureOf(desc, REACH).reason)
      .toBe(': the top rating within reach is well above the places around it');
    const unmeasured = figureOf(desc, new Map()).reason;
    expect(unmeasured).toBe(': the top rating here is well above the places around it');
    expect(unmeasured).not.toMatch(/within reach/);
  });

  it('prints the surviving pick\'s star after a skip', () => {
    const desc = descriptorOf(cardOf([CAW_GAP, slot(2, 'Hotbank', 3)]));
    expect(figureOf(desc, REACH).value).toBe('3★');
  });

  it('has no figure when nothing is rated and nothing was withheld', () => {
    const desc = descriptorOf(cardOf([slot(1, 'A', null)]));
    expect(figureOf(desc, REACH)).toBeNull();
  });
});
