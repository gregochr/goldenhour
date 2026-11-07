import { describe, it, expect } from 'vitest';
import {
  buildHeatPointSets, buildHeatSpots, buildHeatWindowStates, buildUnratedPointSets, forecastScoreRows,
  heatPointsFor, stoodPointsFor, unratedPointsFor,
} from '../utils/heatSpots.js';
import { windowKey } from '../utils/windowKeys.js';

/**
 * Edge behaviour of the heat field's catalogue join: malformed input, key collisions and the
 * id-first / name-second lookup. Companion to heatSpots.test.js; each case pins an answer the join
 * gives to input the happy-path tests never send.
 */

const EVENTS = [
  { date: '2026-08-19', targetType: 'SUNSET' },
  { date: '2026-08-20', targetType: 'SUNRISE' },
];

const loc = (o = {}) => ({
  id: 1, name: 'Bamburgh', lat: 55.6, lon: -1.7, regionName: 'North East', ...o,
});
const row = (o = {}) => ({
  locationId: 1, locationName: 'Bamburgh', date: '2026-08-19', targetType: 'SUNSET', rating: 4, ...o,
});

describe('buildHeatSpots rating validity', () => {
  it('paints only integer ratings: a string or a fraction is "not scored"', () => {
    const spots = buildHeatSpots(
      [loc({ id: 1, name: 'A' }), loc({ id: 2, name: 'B' }), loc({ id: 3, name: 'C' })],
      [
        row({ locationId: 1, locationName: 'A', rating: '3' }),
        row({ locationId: 2, locationName: 'B', rating: 3.5 }),
        row({ locationId: 3, locationName: 'C', rating: 3 }),
      ],
      EVENTS,
    );
    expect(spots.map((s) => s.scores[0])).toEqual([null, null, 3]);
    expect(spots.map((s) => s.states[0])).toEqual(['unrated', 'unrated', 'rated']);
  });
});

describe('buildHeatSpots coordinates', () => {
  it('drops a place whose coordinate is not a finite number, never coercing it', () => {
    const spots = buildHeatSpots(
      [
        loc({ id: 1, lat: '51.5' }),
        loc({ id: 2, lon: '-1.7' }),
        loc({ id: 3, lat: Number.NaN }),
        loc({ id: 4, lon: Number.POSITIVE_INFINITY }),
        loc({ id: 5, lat: null }),
        loc({ id: 6 }),
      ],
      [],
      EVENTS,
    );
    expect(spots.map((s) => s.id)).toEqual([6]);
  });

  it('skips a null entry in the roster', () => {
    const spots = buildHeatSpots([null, loc()], [], EVENTS);
    expect(spots).toHaveLength(1);
  });
});

describe('buildHeatSpots stand-down reasons', () => {
  const reasonFor = (triageReason) => {
    const [spot] = buildHeatSpots([loc()], [row({ rating: null, triageReason })], EVENTS);
    return spot.reasons[0];
  };

  it('reads an object reason from name, then reason, trimmed', () => {
    expect(reasonFor({ name: '  HIGH_CLOUD ' })).toBe('HIGH_CLOUD');
    expect(reasonFor({ reason: ' TIDE_MISALIGNED ' })).toBe('TIDE_MISALIGNED');
  });

  it('falls back to GENERIC when an object names nothing readable', () => {
    expect(reasonFor({ name: 5 })).toBe('GENERIC');
    expect(reasonFor({ name: '   ' })).toBe('GENERIC');
    expect(reasonFor({})).toBe('GENERIC');
  });

  it('trims a string reason', () => {
    expect(reasonFor('  HIGH_CLOUD ')).toBe('HIGH_CLOUD');
  });
});

describe('buildHeatSpots row indexing', () => {
  it('keeps the first of two rows for the same place and window', () => {
    const [byId] = buildHeatSpots(
      [loc()],
      [row({ rating: 5 }), row({ rating: 1 })],
      EVENTS,
    );
    expect(byId.scores[0]).toBe(5);

    const [byName] = buildHeatSpots(
      [loc({ id: 9 })],
      [row({ locationId: null, rating: 2 }), row({ locationId: null, rating: 5 })],
      EVENTS,
    );
    expect(byName.scores[0]).toBe(2);
  });

  it('ignores a row without a date or event, and a null row', () => {
    // The rendered events are equally malformed, so a row that wrongly slipped through would match.
    const events = [{ targetType: 'SUNSET' }, { date: '2026-08-19' }];
    const rows = [
      null,
      row({ date: undefined, targetType: 'SUNSET', rating: 5 }),
      row({ date: '2026-08-19', targetType: undefined, rating: 5 }),
    ];
    const [spot] = buildHeatSpots([loc()], rows, events);
    expect(spot.scores).toEqual([null, null]);
    expect(spot.states).toEqual(['unrated', 'unrated']);
  });

  it('does not let an id-less row match a place whose id is the string "undefined"', () => {
    const [spot] = buildHeatSpots(
      [loc({ id: 'undefined', name: 'Elsewhere' })],
      [row({ locationId: undefined, locationName: 'Bamburgh', rating: 5 })],
      EVENTS,
    );
    expect(spot.scores[0]).toBeNull();
  });

  it('does not let a name-less row match a place whose name is the string "undefined"', () => {
    const [spot] = buildHeatSpots(
      [loc({ id: 9, name: 'undefined' })],
      [row({ locationId: 1, locationName: undefined, rating: 5 })],
      EVENTS,
    );
    expect(spot.scores[0]).toBeNull();
  });

  it('does not give an id-less place an id match from a row keyed "undefined"', () => {
    const [spot] = buildHeatSpots(
      [loc({ id: undefined, name: 'Elsewhere' })],
      [row({ locationId: 'undefined', locationName: 'Bamburgh', rating: 5 })],
      EVENTS,
    );
    expect(spot.scores[0]).toBeNull();
  });
});

describe('buildHeatSpots lookup order', () => {
  it('falls back to the name when the place has no id', () => {
    const [spot] = buildHeatSpots([loc({ id: undefined })], [row({ locationId: null, rating: 3 })], EVENTS);
    expect(spot.scores[0]).toBe(3);
  });

  it('falls back to the name when no row carries the place id', () => {
    const [spot] = buildHeatSpots([loc({ id: 1 })], [row({ locationId: 99, rating: 3 })], EVENTS);
    expect(spot.scores[0]).toBe(3);
  });

  it('lets an id hit end the lookup even when the row is unrated', () => {
    const [spot] = buildHeatSpots(
      [loc()],
      [row({ rating: null }), row({ locationId: 77, rating: 5 })],
      EVENTS,
    );
    expect(spot.scores[0]).toBeNull();
    expect(spot.states[0]).toBe('unrated');
  });
});

describe('buildHeatSpots picks scope', () => {
  it('marks a place with no id outside the picks even when the set holds a null', () => {
    const allowed = new Set([null, undefined]);
    const [spot] = buildHeatSpots([loc({ id: undefined })], [row({ locationId: null })], EVENTS, allowed);
    expect(spot.states).toEqual(['gated', 'gated']);
    expect(spot.scores).toEqual([null, null]);
  });

  it('keeps a place inside the picks when its id is in the set', () => {
    const [spot] = buildHeatSpots([loc()], [row()], EVENTS, new Set([1]));
    expect(spot.states[0]).toBe('rated');
  });
});

describe('buildHeatSpots shape', () => {
  it('carries the served sub-region through, and null when absent', () => {
    const [withSub, without] = buildHeatSpots(
      [loc({ id: 1, subRegionId: 12, subRegionName: 'Coast' }), loc({ id: 2, name: 'B' })],
      [],
      EVENTS,
    );
    expect(withSub.subRegionId).toBe(12);
    expect(withSub.subRegionName).toBe('Coast');
    expect(without.subRegionId).toBeNull();
    expect(without.subRegionName).toBeNull();
  });

  it('gives each place no windows when the rendered events are not a list', () => {
    const [spot] = buildHeatSpots([loc()], [row()], null);
    expect(spot.scores).toEqual([]);
    expect(spot.states).toEqual([]);
    expect(spot.reasons).toEqual([]);
  });
});

describe('heatPointsFor', () => {
  const spot = (o = {}) => ({ id: 1, name: 'A', lat: 1, lng: 2, rid: 'R', scores: [4, 2], ...o });

  it('skips a non-finite score, a missing score, a spot without scores and a null spot', () => {
    const points = heatPointsFor(
      [
        spot({ id: 1, scores: [Number.NaN] }),
        spot({ id: 2, scores: [Number.POSITIVE_INFINITY] }),
        spot({ id: 3, scores: [null] }),
        spot({ id: 4, scores: undefined }),
        null,
        spot({ id: 5, scores: [3] }),
      ],
      0,
    );
    expect(points.map((p) => p.id)).toEqual([5]);
    expect(points[0].r).toEqual([3]);
  });
});

describe('unratedPointsFor', () => {
  const spot = (id, state) => ({ id, name: `n${id}`, lat: 1, lng: 2, rid: 'R', states: [state] });

  it('reads a window with only stood-down places as evidence', () => {
    const points = unratedPointsFor([spot(1, 'stood'), spot(2, 'unrated')], 0);
    expect(points.map((p) => p.id)).toEqual([2]);
  });

  it('reads a window with only rated places as evidence', () => {
    const points = unratedPointsFor([spot(1, 'rated'), spot(2, 'unrated')], 0);
    expect(points.map((p) => p.id)).toEqual([2]);
  });

  it('yields nothing without evidence and tolerates places with no states or null', () => {
    expect(unratedPointsFor([spot(1, 'unrated'), { id: 2 }, null], 0)).toEqual([]);
    const points = unratedPointsFor([spot(1, 'rated'), spot(2, 'unrated'), { id: 3 }, null], 0);
    expect(points.map((p) => p.id)).toEqual([2]);
  });
});

describe('stoodPointsFor', () => {
  it('keeps the reason per window and a null when the spot carries no reasons', () => {
    const spots = [
      { id: 1, name: 'A', lat: 1, lng: 2, rid: 'R', states: ['stood'], reasons: ['HIGH_CLOUD'] },
      { id: 2, name: 'B', lat: 1, lng: 2, rid: 'R', states: ['stood'] },
      { id: 3, name: 'C', lat: 1, lng: 2, rid: 'R', states: ['rated'], reasons: [null] },
      null,
      { id: 4, name: 'D' },
    ];
    const points = stoodPointsFor(spots, 0);
    expect(points.map((p) => [p.key, p.reason])).toEqual([['#1', 'HIGH_CLOUD'], ['#2', null]]);
  });
});

describe('window keyed sets skip malformed events', () => {
  const spots = [
    { id: 1, name: 'A', lat: 1, lng: 2, rid: 'R', scores: [3, 3, 3, 3], states: ['rated', 'unrated', 'rated', 'unrated'] },
  ];
  const events = [
    null,
    { targetType: 'SUNSET' },
    { date: '2026-08-19' },
    { date: '2026-08-20', targetType: 'SUNRISE' },
  ];
  const good = windowKey('2026-08-20', 'SUNRISE');

  it('buildHeatPointSets keys only the well-formed event', () => {
    expect([...buildHeatPointSets(spots, events).keys()]).toEqual([good]);
  });

  it('buildUnratedPointSets keys only the well-formed event', () => {
    expect([...buildUnratedPointSets(spots, events).keys()]).toEqual([good]);
  });

  it('buildHeatWindowStates keys only the well-formed event', () => {
    expect([...buildHeatWindowStates(spots, events).keys()]).toEqual([good]);
  });

  it('return nothing for events that are not a list', () => {
    expect(buildHeatPointSets(spots, null).size).toBe(0);
    expect(buildUnratedPointSets(spots, null).size).toBe(0);
    expect(buildHeatWindowStates(spots, null).size).toBe(0);
  });
});

describe('buildHeatWindowStates', () => {
  it('records a state only for spots that have one in that window', () => {
    const spots = [
      { id: 1, name: 'A', lat: 1, lng: 2, rid: 'R', scores: [3], states: ['rated'], reasons: [null] },
      { id: 2, name: 'B', lat: 1, lng: 2, rid: 'R', scores: [], states: [], reasons: [] },
      { id: 3, name: 'C', lat: 1, lng: 2, rid: 'R' },
      null,
    ];
    const out = buildHeatWindowStates(spots, [{ date: '2026-08-19', targetType: 'SUNSET' }]);
    const { states } = out.get(windowKey('2026-08-19', 'SUNSET'));
    expect([...states.entries()]).toEqual([['#1', 'rated']]);
  });

  it('gives an empty map when the spots are not a list', () => {
    const out = buildHeatWindowStates(null, [{ date: '2026-08-19', targetType: 'SUNSET' }]);
    expect(out.get(windowKey('2026-08-19', 'SUNSET')).states.size).toBe(0);
  });
});

describe('forecastScoreRows', () => {
  it('skips a null location in the roster', () => {
    const days = new Map([['2026-08-19', { sunset: { rating: 3 } }]]);
    const rows = forecastScoreRows(
      [null, { id: 1, name: 'A', forecastsByDate: days }],
      [{ date: '2026-08-19', targetType: 'SUNSET' }],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0].locationId).toBe(1);
  });

  it('produces no rows when the windows are not a list', () => {
    const days = new Map([[undefined, { sunset: { rating: 3 } }]]);
    expect(forecastScoreRows([{ id: 1, name: 'A', forecastsByDate: days }], null)).toEqual([]);
  });
});
