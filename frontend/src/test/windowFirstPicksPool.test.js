import { describe, it, expect } from 'vitest';
import {
  buildWindowSpots, compareSpots, placesOf,
} from '../utils/windowFirstSpots.js';
import { buildWindowCards } from '../utils/windowFirstCards.js';
import { buildHeatStripCards } from '../utils/windowFirstStrip.js';
import { buildSpread, spokenPoolPhrase, spreadRowState } from '../utils/windowFirstSpread.js';
import { thinVerdictCount } from '../utils/windowFirstThinSample.js';
import { buildRegionRows, buildRegionSeries } from '../utils/windowFirstRegions.js';
import { bestReachLine } from '../components/WindowFirstHeatStrip.jsx';
import { factsOf } from './tideFactsFixture.js';

/**
 * A Lite reader's Plan pool (sub-regions-plan S5b-1, B2): a place outside the picks is carried on its spot as
 * {@code gated}, kept in the lists the popup and the Map read, and left out of the pool every count reads. A body
 * that was not shaped is byte-identical to what it was.
 */

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';

function slot(id, name, rating, extra = {}) {
  return {
    locationId: id, locationName: name, claudeRating: rating, canopy: false, ...extra,
  };
}

/**
 * Two regions. Coast holds one place the reader has (id 1, rated 4) and two outside (ids 2 and 3, gated by the server
 * with no star). Dales holds nothing of theirs: its only place (id 4) is outside and not marked on its row, so only
 * the served list can say so.
 */
function shapedSummary() {
  const slots = [
    slot(1, 'Bamburgh', 4, { tideState: 'HIGH', tideAligned: true }),
    slot(2, 'Craster', null, { gated: true, tideState: 'HIGH', tideAligned: true }),
    slot(3, 'Alnmouth', null, { gated: true }),
  ];
  const dales = [slot(4, 'Malham', 5)];
  return {
    targetType: 'SUNSET',
    regions: [
      {
        regionName: 'Coast', displayVerdict: 'WORTH_IT', meanRating: null, bestRating: null, picksBestRating: 4, slots,
      },
      {
        regionName: 'Dales', displayVerdict: 'MAYBE', meanRating: null, bestRating: null, slots: dales,
      },
    ],
    unregioned: [],
    shapedLeadRegion: 'Coast',
    window: { verdict: 'WORTH_IT', badges: [], bestRating: null, tideFacts: factsOf(slots) },
  };
}

const EVENTS = [{ date: TODAY, targetType: 'SUNSET' }];
const days = (summary) => [{ date: TODAY, eventSummaries: [summary] }];
const ALLOWED = new Set([1]);

function build(summary, allowedIds = ALLOWED, lens = {}) {
  return buildWindowCards(EVENTS, days(summary), TODAY, TOMORROW, new Set(), new Map(), {
    limitMinutes: null, defaultLimitMinutes: null, allowedIds, ...lens,
  });
}

describe('buildWindowSpots: a place outside the picks', () => {
  it('carries gated from the served row marker, with no star and no neighbour mark', () => {
    const spots = buildWindowSpots(shapedSummary(), new Map(), null, ALLOWED);
    const craster = spots.find((s) => s.locationName === 'Craster');

    expect(craster.gated).toBe(true);
    expect(craster.rating).toBeNull();
    expect(craster).not.toHaveProperty('neighbourOutlier');
  });

  it('gates a place the served list does not name even when its row carries no marker', () => {
    const spots = buildWindowSpots(shapedSummary(), new Map(), null, ALLOWED);

    // Malham is rated 5 on its row and unmarked: only the list says it is outside, and the star goes with it.
    const malham = spots.find((s) => s.locationName === 'Malham');
    expect(malham.gated).toBe(true);
    expect(malham.rating).toBeNull();
  });

  it('never marks a place with no id as outside unless the server did', () => {
    const summary = shapedSummary();
    summary.regions[0].slots.push(slot(null, 'Legacy', 3));
    const legacy = buildWindowSpots(summary, new Map(), null, ALLOWED).find((s) => s.locationName === 'Legacy');

    expect(legacy).not.toHaveProperty('gated');
    expect(legacy.rating).toBe(3);
  });

  it('puts the places the reader holds first, then the gated ones, whatever the names', () => {
    const spots = buildWindowSpots(shapedSummary(), new Map(), null, ALLOWED);

    expect(spots.map((s) => s.locationName)).toEqual(['Bamburgh', 'Alnmouth', 'Craster', 'Malham']);
  });

  it('adds nothing to a spot when no list is handed over (Pro, Admin, flag off)', () => {
    const summary = shapedSummary();
    summary.regions[0].slots.forEach((s) => { delete s.gated; });
    const spots = buildWindowSpots(summary, new Map());

    expect(spots.every((s) => !('gated' in s))).toBe(true);
    expect(spots.find((s) => s.locationName === 'Malham').rating).toBe(5);
  });

  it('orders by rating as ever when nothing is gated', () => {
    expect(compareSpots({ rating: 3, locationName: 'a' }, { rating: 4, locationName: 'b' })).toBeGreaterThan(0);
    expect(compareSpots({ rating: null, locationName: 'a' }, { rating: null, locationName: 'b' })).toBeLessThan(0);
  });

  it('placesOf hands back the very array when nothing is gated', () => {
    const list = [{ locationName: 'a' }, { locationName: 'b' }];
    expect(placesOf(list)).toBe(list);
    expect(placesOf([{ locationName: 'a' }, { gated: true }])).toHaveLength(1);
    expect(placesOf(null)).toEqual([]);
  });
});

describe('buildWindowCards: the pool of a shaped body', () => {
  it('keeps the gated places on spots and allSpots, and out of the pool', () => {
    const [card] = build(shapedSummary());

    expect(card.allSpots.map((s) => s.locationName)).toEqual(['Bamburgh', 'Alnmouth', 'Craster', 'Malham']);
    expect(card.spots).toBe(card.allSpots);
    expect(card.pool.map((s) => s.locationName)).toEqual(['Bamburgh']);
  });

  it('counts the places outside separately and leaves them out of both totals', () => {
    const [card] = build(shapedSummary());

    expect(card.outsideCount).toBe(3);
    expect(card.reachTotal).toBe(1);
    expect(card.reachedTotal).toBe(1);
    expect(card.picksScoped).toBe(true);
  });

  it('names the best of the reader\'s own places, with that place\'s own star', () => {
    const [card] = build(shapedSummary());

    // Malham is rated 5 on its row and is outside: it must not be named, nor its star printed.
    expect(card.bestReach).toMatchObject({ locationName: 'Bamburgh', rating: 4 });
  });

  it('feeds the spread and the thin-sample line the reader\'s places only', () => {
    const [card] = build(shapedSummary());
    const spread = buildSpread(card.pool);

    expect(spread.total).toBe(1);
    expect(spread.rated).toBe(1);
    expect(spreadRowState(spread).text).toBe('1/1 rated');
    expect(thinVerdictCount({ verdict: 'WORTH_IT', spread })).toBe(1);
    // The pool clause says one place, never four.
    expect(spokenPoolPhrase(spread.total, { text: 'your sub-regions', spoken: 'in your sub-regions' }))
      .toBe('1 location, in your sub-regions');
  });

  it('draws the coast fit over the reader\'s places only', () => {
    const [card] = build(shapedSummary());

    // Craster is coastal and aligned too, but outside the picks: only Bamburgh is in the pool the fit reads.
    expect(card.tideFit.coastal).toBe(1);
  });

  it('is inert for tide when the server withholds the per-location match (Lite)', () => {
    const summary = shapedSummary();
    summary.window.tideFacts = summary.window.tideFacts.map((fact) => ({ ...fact, tideAligned: null }));
    const [card] = build(summary);

    expect(card.tideFit).toEqual({
      coastal: 0, matched: 0, live: false, meanQuality: null,
    });
  });

  it('is a place count of zero for a reader with no pick, whose every place is outside', () => {
    const [card] = build(shapedSummary(), new Set());

    expect(card.pool).toEqual([]);
    expect(card.bestReach).toBeNull();
    expect(card.reachTotal).toBe(0);
    expect(card.outsideCount).toBe(4);
    expect(card.tideFit.coastal).toBe(0);
  });

  it('leaves a body that was not shaped exactly as it was', () => {
    const summary = shapedSummary();
    summary.regions[0].slots.forEach((s) => { delete s.gated; });
    const [card] = build(summary, null);

    expect(card).not.toHaveProperty('outsideCount');
    expect(card).not.toHaveProperty('picksScoped');
    expect(card.pool).toBe(card.allSpots);
    expect(card.reachTotal).toBe(card.allSpots.length);
    expect(card.bestReach.locationName).toBe('Malham');
  });

  it('folds the two shaped facts onto the strip descriptor only for a shaped body', () => {
    const [shaped] = buildHeatStripCards(EVENTS, build(shapedSummary()), new Set(), days(shapedSummary()), TODAY, TOMORROW);
    const plain = shapedSummary();
    plain.regions[0].slots.forEach((s) => { delete s.gated; });
    const [open] = buildHeatStripCards(EVENTS, build(plain, null), new Set(), days(plain), TODAY, TOMORROW);

    expect(shaped).toMatchObject({ picksScoped: true, outsideCount: 3 });
    expect(open).not.toHaveProperty('picksScoped');
    expect(open).not.toHaveProperty('outsideCount');
  });
});

describe('the Best row of a shaped card', () => {
  const stripCard = (summary, allowed) => buildHeatStripCards(
    EVENTS, build(summary, allowed), new Set(), days(summary), TODAY, TOMORROW,
  )[0];

  it('names the best allowed place and its own star', () => {
    expect(bestReachLine(stripCard(shapedSummary(), ALLOWED))).toMatchObject({ rating: 4, value: 'Bamburgh' });
  });

  it('says none of the sub-regions is rated when places exist and none has a star', () => {
    const summary = shapedSummary();
    summary.regions[0].slots[0].claudeRating = null;
    const line = bestReachLine(stripCard(summary, ALLOWED));

    expect(line.value).toBe('None of your sub-regions are rated for this sunset yet.');
    expect(line.spoken).toContain('None of your sub-regions are rated for this sunset yet.');
    expect(line.value).not.toMatch(/in reach|nothing to show|not scored yet/i);
  });

  it('says it for a sunrise too', () => {
    const summary = shapedSummary();
    summary.targetType = 'SUNRISE';
    summary.regions[0].slots[0].claudeRating = null;
    const events = [{ date: TODAY, targetType: 'SUNRISE' }];
    const cards = buildWindowCards(events, days(summary), TODAY, TOMORROW, new Set(), new Map(), {
      limitMinutes: null, defaultLimitMinutes: null, allowedIds: ALLOWED,
    });
    const [strip] = buildHeatStripCards(events, cards, new Set(), days(summary), TODAY, TOMORROW);

    expect(bestReachLine(strip).value).toBe('None of your sub-regions are rated for this sunrise yet.');
  });

  it('omits the row for a reader with no place of their own (null, never "nothing in reach")', () => {
    expect(bestReachLine(stripCard(shapedSummary(), new Set()))).toBeNull();
  });
});

describe('the region rows of a shaped body', () => {
  const picks = new Map([
    ['Coast', { allowedPlaces: 1, places: 3 }],
    ['Dales', { allowedPlaces: 0, places: 1 }],
  ]);
  const rows = (summary = shapedSummary(), regionPicks = picks) => {
    const [card] = build(summary);
    return buildRegionRows(summary, card.spots, card.allSpots, { limitMinutes: null, minRating: null }, regionPicks);
  };

  it('counts the reader\'s places and the outside ones apart, and carries the served star', () => {
    const coast = rows().find((r) => r.name === 'Coast');

    expect(coast).toMatchObject({
      shaped: true, hasPicks: true, drawnCount: 1, outsideCount: 2, picksBestRating: 4,
    });
  });

  it('puts a region with a pick first, then verdict tier, then name', () => {
    const summary = shapedSummary();
    summary.regions.push({
      regionName: 'Alpha', displayVerdict: 'WORTH_IT', meanRating: null, slots: [slot(9, 'Zed', null, { gated: true })],
    });
    const regionPicks = new Map([...picks, ['Alpha', { allowedPlaces: 0, places: 1 }]]);

    // Alpha is the better verdict and sorts first by name, but the reader has no pick there.
    expect(rows(summary, regionPicks).map((r) => r.name)).toEqual(['Coast', 'Alpha', 'Dales']);
  });

  it('puts the served lead region first when it holds a pick, whatever the order would say', () => {
    const summary = shapedSummary();
    summary.regions.unshift({
      regionName: 'Aaron', displayVerdict: 'WORTH_IT', meanRating: null, slots: [slot(8, 'Mine', 3)],
    });
    summary.shapedLeadRegion = 'Coast';
    const regionPicks = new Map([...picks, ['Aaron', { allowedPlaces: 1, places: 1 }]]);

    expect(rows(summary, regionPicks)[0].name).toBe('Coast');
  });

  it('leaves the lead where it falls when the reader holds no pick in it', () => {
    const summary = shapedSummary();
    summary.shapedLeadRegion = 'Dales';

    expect(rows(summary)[0].name).toBe('Coast');
  });

  it('leaves the unshaped order and fields untouched', () => {
    const summary = shapedSummary();
    summary.regions.forEach((r) => { r.meanRating = 3; r.slots.forEach((s) => { delete s.gated; }); });
    const [card] = build(summary, null);
    const plain = buildRegionRows(summary, card.spots, card.allSpots, { limitMinutes: null, minRating: null });

    expect(plain.every((r) => !('shaped' in r))).toBe(true);
    expect(plain.map((r) => r.name)).toEqual(['Coast', 'Dales']);
  });
});

describe('regionSeries on a shaped body', () => {
  it('holds null for every region (the means are stripped), which the popup renders as silence', () => {
    const series = buildRegionSeries(EVENTS, days(shapedSummary()));

    expect(series.get('Coast').get(`${TODAY}:SUNSET`)).toBeNull();
    expect(series.get('Dales').get(`${TODAY}:SUNSET`)).toBeNull();
  });
});
