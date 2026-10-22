import { describe, it, expect } from 'vitest';
import {
  buildHeatSpots, forecastScoreRows, heatPointsFor, unratedPointsFor,
} from '../utils/heatSpots.js';

/**
 * The per-window state beside each score (thin-sample-plan.md §1 #1–#2, F1): rated, stood (looked
 * at and stood down), unrated (nobody has looked) and na (not a sky subject, never hatched). The
 * unknown-ground layer reads only `unrated`, so each of the other three has to stay out of it.
 */

const EVENTS = [
  { date: '2026-10-24', targetType: 'SUNRISE' },
  { date: '2026-10-24', targetType: 'SUNSET' },
];

function location(overrides = {}) {
  return {
    id: 1, name: 'Bamburgh', lat: 55.6, lon: -1.7, regionName: 'North East', ...overrides,
  };
}

function row(overrides = {}) {
  return {
    locationId: 1,
    locationName: 'Bamburgh',
    date: '2026-10-24',
    targetType: 'SUNRISE',
    rating: 4,
    ...overrides,
  };
}

describe('buildHeatSpots — per-window state', () => {
  it('reads a valid rating as rated', () => {
    const [spot] = buildHeatSpots([location()], [row()], EVENTS);
    expect(spot.states).toEqual(['rated', 'unrated']);
    expect(spot.scores).toEqual([4, null]);
  });

  it('reads a null rating WITH a triage reason as stood — looked at, not unrated', () => {
    const [spot] = buildHeatSpots(
      [location()], [row({ rating: null, triageReason: 'HIGH_CLOUD' })], EVENTS,
    );
    expect(spot.states[0]).toBe('stood');
    expect(spot.scores[0]).toBeNull();
  });

  it('reads a null rating with NO triage reason as unrated', () => {
    const [a] = buildHeatSpots([location()], [row({ rating: null })], EVENTS);
    const [b] = buildHeatSpots(
      [location()], [row({ rating: null, triageReason: null })], EVENTS,
    );
    const [c] = buildHeatSpots(
      [location()], [row({ rating: null, triageReason: '  ' })], EVENTS,
    );
    expect(a.states[0]).toBe('unrated');
    expect(b.states[0]).toBe('unrated');
    expect(c.states[0]).toBe('unrated');
  });

  it('reads a sky place with no row at all as unrated', () => {
    const [spot] = buildHeatSpots([location()], [], EVENTS);
    expect(spot.states).toEqual(['unrated', 'unrated']);
  });

  it('reads a rating the join refuses (out of 1–5) with no reason as unrated', () => {
    const [spot] = buildHeatSpots([location()], [row({ rating: 0 })], EVENTS);
    expect(spot.states[0]).toBe('unrated');
  });

  it('keeps a rated row rated even if it also names a triage reason', () => {
    const [spot] = buildHeatSpots([location()], [row({ triageReason: 'X' })], EVENTS);
    expect(spot.states[0]).toBe('rated');
  });

  it('reads a wood as na in every window — even one that HAS a score row', () => {
    const wood = location({ id: 2, name: 'Hamsterley', locationType: ['WOODLAND'] });
    const [spot] = buildHeatSpots(
      [wood],
      [row({ locationId: 2, locationName: 'Hamsterley', rating: 5 }),
        row({
          locationId: 2, locationName: 'Hamsterley', rating: null, targetType: 'SUNSET',
        })],
      EVENTS,
    );
    expect(spot.skySubject).toBe(false);
    expect(spot.states).toEqual(['na', 'na']);
    expect(spot.scores).toEqual([null, null]);
  });

  it('reads a wildlife hide as na', () => {
    const [spot] = buildHeatSpots([location({ locationType: ['WILDLIFE'] })], [], EVENTS);
    expect(spot.states).toEqual(['na', 'na']);
  });

  it('lets an id-keyed row decide the state, as it decides the score', () => {
    // The id row is a stand-down; a same-named row under another id must not override it.
    const [spot] = buildHeatSpots(
      [location()],
      [row({ rating: null, triageReason: 'RAIN' }), row({ locationId: 99, rating: 5 })],
      EVENTS,
    );
    expect(spot.states[0]).toBe('stood');
  });

  it('falls back to the name-keyed row when no id row exists', () => {
    const [spot] = buildHeatSpots(
      [location()], [row({ locationId: null, rating: null, triageReason: 'RAIN' })], EVENTS,
    );
    expect(spot.states[0]).toBe('stood');
  });

  it('keeps one state per rendered window, in render order', () => {
    const [spot] = buildHeatSpots(
      [location()],
      [row({ targetType: 'SUNSET', rating: 2 }), row({ rating: null, triageReason: 'RAIN' })],
      EVENTS,
    );
    expect(spot.states).toEqual(['stood', 'rated']);
  });
});

describe('unratedPointsFor', () => {
  const locations = [
    location({ id: 1, name: 'Rated' }),
    location({ id: 2, name: 'Stood' }),
    location({ id: 3, name: 'NoRow' }),
    location({ id: 4, name: 'Wood', locationType: ['WOODLAND'] }),
  ];
  const rows = [
    row({ locationId: 1, locationName: 'Rated', rating: 4 }),
    row({
      locationId: 2, locationName: 'Stood', rating: null, triageReason: 'RAIN',
    }),
    row({ locationId: 4, locationName: 'Wood', rating: 5 }),
  ];
  const spots = buildHeatSpots(locations, rows, EVENTS);

  it('returns only the unrated place — not rated, stood-down or na', () => {
    expect(unratedPointsFor(spots, 0).map((p) => p.name)).toEqual(['NoRow']);
  });

  it('is the shape heatPointsFor returns, so a host can project it the same way', () => {
    const [p] = unratedPointsFor(spots, 0);
    expect(p).toEqual({
      id: 3, name: 'NoRow', lat: 55.6, lng: -1.7, rid: 'North East', r: [0],
    });
  });

  it('leaves the rated points exactly as they were', () => {
    expect(heatPointsFor(spots, 0).map((p) => p.name)).toEqual(['Rated']);
  });

  it('is empty for an unknown window, a missing state list and bad input', () => {
    expect(unratedPointsFor(spots, 9)).toEqual([]);
    expect(unratedPointsFor([{ id: 1 }], 0)).toEqual([]);
    expect(unratedPointsFor(null, 0)).toEqual([]);
  });
});

describe('forecastScoreRows — the triage field for a past window', () => {
  const windows = [{ date: '2026-10-24', targetType: 'SUNSET' }];
  const loc = (sunset) => [{
    id: 1,
    name: 'Bamburgh',
    lat: 55.6,
    lon: -1.7,
    regionName: 'North East',
    forecastsByDate: new Map([['2026-10-24', { sunset }]]),
  }];

  it('passes the forecast row\'s triageReason through, so a past stood-down is not unrated', () => {
    const located = loc({ rating: null, triageReason: 'HIGH_CLOUD' });
    const rows = forecastScoreRows(located, windows);
    expect(rows[0].triageReason).toBe('HIGH_CLOUD');
    const [spot] = buildHeatSpots(located, rows, windows);
    expect(spot.states).toEqual(['stood']);
  });

  it('carries none when the row has none, and reads the place as unrated', () => {
    const located = loc({ rating: null });
    const rows = forecastScoreRows(located, windows);
    expect(rows[0]).not.toHaveProperty('triageReason');
    const [spot] = buildHeatSpots(located, rows, windows);
    expect(spot.states).toEqual(['unrated']);
  });
});
