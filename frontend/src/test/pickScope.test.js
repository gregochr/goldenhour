import { describe, it, expect } from 'vitest';
import {
  GATED_STATE, hasRatedState, isGatedIn, isShaped, splitGround,
} from '../utils/pickScope.js';
import {
  buildHeatSpots, buildHeatWindowStates, forecastScoreRows, heatPointsFor, heatSpotKey, unratedPointsFor,
} from '../utils/heatSpots.js';
import { buildThinCounts, thinRatedCount } from '../utils/mapThinSample.js';

/**
 * The reader's sub-region picks on the Map (sub-regions-plan S5a-1): the shaped marker, the `gated` heat
 * state, and the rule that a gated place is in neither half of any count and never in the hatch.
 */

const EVENTS = [{ date: '2026-10-27', targetType: 'SUNSET' }];

const place = (id, overrides = {}) => ({
  id, name: `P${id}`, lat: 55 + id / 100, lon: -2, regionName: 'North East', locationType: ['LANDSCAPE'], ...overrides,
});

const row = (id, overrides = {}) => ({
  locationId: id, locationName: `P${id}`, date: '2026-10-27', targetType: 'SUNSET', rating: 4, ...overrides,
});

describe('isShaped', () => {
  it('is true for an array, an empty one included, and for nothing else', () => {
    expect(isShaped([7, 8])).toBe(true);
    expect(isShaped([])).toBe(true);
    expect(isShaped(null)).toBe(false);
    expect(isShaped(undefined)).toBe(false);
    expect(isShaped('7,8')).toBe(false);
    expect(isShaped({ length: 2 })).toBe(false);
  });
});

describe('buildHeatSpots — the gated state', () => {
  it('reads a row marked gated: true as its own state, with no score', () => {
    const [spot] = buildHeatSpots([place(1)], [row(1, { rating: null, gated: true })], EVENTS);

    expect(spot.states).toEqual([GATED_STATE]);
    expect(spot.scores).toEqual([null]);
  });

  it('never reads a gated place as unrated: it is not in the hatch, and not in the colour pool', () => {
    const spots = buildHeatSpots(
      [place(1), place(2), place(3)],
      [row(1), row(2, { rating: null, gated: true }), row(3, { rating: null })],
      EVENTS,
    );

    expect(spots.map((s) => s.states[0])).toEqual(['rated', GATED_STATE, 'unrated']);
    expect(heatPointsFor(spots, 0).map((p) => p.id)).toEqual([1]);
    expect(unratedPointsFor(spots, 0).map((p) => p.id)).toEqual([3]);
  });

  it('marks a gated wood gated too: outside the picks does not depend on a sky verdict applying', () => {
    const [wood] = buildHeatSpots(
      [place(4, { locationType: ['WOODLAND'] })], [row(4, { rating: null, gated: true })], EVENTS,
    );

    expect(wood.states).toEqual([GATED_STATE]);
    expect(wood.scores).toEqual([null]);
  });

  it('keeps an ungated wood as na, and never lets a gated row\'s rating through', () => {
    const [wood, odd] = buildHeatSpots(
      [place(4, { locationType: ['WOODLAND'] }), place(5)],
      [row(4), row(5, { rating: 5, gated: true })],
      EVENTS,
    );

    expect(wood.states).toEqual(['na']);
    expect(odd.states).toEqual([GATED_STATE]);
    expect(odd.scores).toEqual([null]);
  });

  it('is not gated when the row carries no marker, whatever the role: only the server says so', () => {
    const [spot] = buildHeatSpots([place(1)], [row(1, { gated: false })], EVENTS);

    expect(spot.states).toEqual(['rated']);
  });

  it('carries the marker through the forecast rows a PAST window is built from', () => {
    const locations = [{
      id: 1,
      name: 'P1',
      forecastsByDate: new Map([['2026-10-27', { sunset: { rating: null, gated: true } }]]),
    }];

    const rows = forecastScoreRows(locations, EVENTS);

    expect(rows[0].gated).toBe(true);
    expect(forecastScoreRows([{ ...locations[0], forecastsByDate: new Map([['2026-10-27', { sunset: { rating: 3 } }]]) }], EVENTS)[0])
      .not.toHaveProperty('gated');
  });
});

describe('thinRatedCount — a gated place is in neither half', () => {
  const sky = (id) => ({ id, name: `P${id}`, skySubject: true });
  const WORTH = { tier: 'WORTH_IT' };

  it('counts rated and total over the reader\'s own places only', () => {
    // 5 own places, 5 rated: the spread floors pass, so the pill stays silent. With 50 gated places
    // counted in the total it would read "5 rated" of 55 and print a count.
    const own = [1, 2, 3, 4, 5].map(sky);
    const outside = Array.from({ length: 50 }, (_, i) => sky(100 + i));
    const states = new Map([
      ...own.map((s) => [heatSpotKey(s), 'rated']),
      ...outside.map((s) => [heatSpotKey(s), GATED_STATE]),
    ]);

    expect(thinRatedCount({ verdict: WORTH, states, scopePool: [...own, ...outside] })).toBeNull();
  });

  it('still prints a thin count for the own places when they are genuinely thin', () => {
    const own = Array.from({ length: 12 }, (_, i) => sky(i + 1));
    const outside = Array.from({ length: 50 }, (_, i) => sky(100 + i));
    const states = new Map([
      ...own.map((s, i) => [heatSpotKey(s), i < 4 ? 'rated' : 'unrated']),
      ...outside.map((s) => [heatSpotKey(s), GATED_STATE]),
    ]);

    expect(thinRatedCount({ verdict: WORTH, states, scopePool: [...own, ...outside] })).toBe(4);
  });

  it('flows through buildThinCounts from the real heat window states', () => {
    const spots = buildHeatSpots(
      [place(1), place(2), place(3)],
      [row(1), row(2, { rating: null, gated: true }), row(3, { rating: null, gated: true })],
      EVENTS,
    );
    const windowStates = buildHeatWindowStates(spots, EVENTS);
    const events = [{ id: 'e1', kind: 'solar', date: '2026-10-27', eventType: 'SUNSET' }];

    const counts = buildThinCounts({
      events, verdicts: new Map([['e1', WORTH]]), windowStates, scopePool: spots,
    });

    expect(counts.get('e1')).toBe(1);
  });
});

describe('isGatedIn and hasRatedState', () => {
  it('reads the state by the heat spot key, and is false without a Map', () => {
    const states = new Map([['#1', GATED_STATE], ['#2', 'rated']]);

    expect(isGatedIn(states, { id: 1, name: 'A' })).toBe(true);
    expect(isGatedIn(states, { id: 2, name: 'B' })).toBe(false);
    expect(isGatedIn(null, { id: 1, name: 'A' })).toBe(false);
  });

  it('asks the states, not the served window best star, whether anything is rated', () => {
    expect(hasRatedState(new Map([['#1', GATED_STATE], ['#2', 'unrated']]))).toBe(false);
    expect(hasRatedState(new Map([['#1', GATED_STATE], ['#2', 'rated']]))).toBe(true);
    expect(hasRatedState(null)).toBe(false);
  });
});

describe('splitGround', () => {
  const spots = [1, 2, 3].map((id) => ({
    id, name: `P${id}`, lat: 55, lng: -2, rid: 'North East',
  }));

  it('puts the gated places outside and every other place, rated or not, in allowed', () => {
    const states = new Map([['#1', 'rated'], ['#2', GATED_STATE], ['#3', 'unrated']]);

    const ground = splitGround({ shaped: true, spots, states });

    expect(ground.outside.map((p) => p.id)).toEqual([2]);
    expect(ground.allowed.map((p) => p.id)).toEqual([1, 3]);
  });

  it('is null for a body that is not shaped, and for a window with no place states', () => {
    const states = new Map([['#1', 'rated']]);

    expect(splitGround({ shaped: false, spots, states })).toBeNull();
    expect(splitGround({ shaped: true, spots, states: new Map() })).toBeNull();
    expect(splitGround({ shaped: true, spots, states: null })).toBeNull();
  });

  it('makes no claim about a place that has no state in the window', () => {
    const states = new Map([['#1', GATED_STATE]]);

    const ground = splitGround({ shaped: true, spots, states });

    expect(ground.outside.map((p) => p.id)).toEqual([1]);
    expect(ground.allowed).toEqual([]);
  });

  it('gives a reader with no picks every place outside and none allowed', () => {
    const states = new Map(spots.map((s) => [heatSpotKey(s), GATED_STATE]));

    const ground = splitGround({ shaped: true, spots, states });

    expect(ground.outside).toHaveLength(3);
    expect(ground.allowed).toEqual([]);
  });
});
