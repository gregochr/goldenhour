/**
 * Edge behaviour of `utils/mapScopedTime.js`: the same-array answers when nothing can be scoped,
 * a forecast store that is not a Map, and the first-seen rule on two equal instants. Companion to
 * mapScopedTime.test.js and mapScopedTimeBranches.test.js; each case pins an answer the happy-path
 * suites never ask for.
 */
import { describe, it, expect } from 'vitest';
import { withScopedSolarTimes } from '../utils/mapScopedTime.js';

const DATE = '2026-10-13';

const loc = (id, name, sunset) => ({
  id,
  name,
  forecastsByDate: new Map([[DATE, { sunset: { solarEventTime: sunset } }]]),
});
const sunsetRow = (over = {}) => ({
  id: `solar:${DATE}:SUNSET`, kind: 'solar', date: DATE, eventType: 'SUNSET', time: '18:09', ...over,
});
/** Echoes the raw instant, so which of two equal instants was kept shows in the printed time. */
const echo = (raw) => raw;

describe('withScopedSolarTimes — nothing to scope', () => {
  it('returns the very same events array when there are no events, even with a matching place', () => {
    const events = [];
    const out = withScopedSolarTimes({
      events,
      spots: [{ id: 1 }],
      locations: [loc(1, 'A', `${DATE}T17:00:00`)],
      formatTimeUk: echo,
    });
    expect(out).toBe(events);
  });

  it('returns the very same events array when the spot pool is empty', () => {
    const events = [sunsetRow()];
    const out = withScopedSolarTimes({
      events, spots: [], locations: [loc(1, 'A', `${DATE}T17:00:00`)], formatTimeUk: echo,
    });
    expect(out).toBe(events);
  });

  it('returns the very same events array when the spots are not an array', () => {
    const events = [sunsetRow()];
    const out = withScopedSolarTimes({
      events, spots: null, locations: [loc(1, 'A', `${DATE}T17:00:00`)], formatTimeUk: echo,
    });
    expect(out).toBe(events);
  });

  it('answers an empty list for events that are not an array', () => {
    const out = withScopedSolarTimes({
      events: undefined, spots: [{ id: 1 }], locations: [loc(1, 'A', `${DATE}T17:00:00`)], formatTimeUk: echo,
    });
    expect(out).toEqual([]);
  });

  it('returns the same array when no spot matches a location, and tolerates null locations', () => {
    const events = [sunsetRow()];
    const out = withScopedSolarTimes({
      events, spots: [{ id: 9, name: 'Nowhere' }], locations: [null, loc(1, 'A', `${DATE}T17:00:00`)],
      formatTimeUk: echo,
    });
    expect(out).toBe(events);
  });

  it('does not match a spot with neither an id nor a name to a location that has neither', () => {
    const bare = { forecastsByDate: new Map([[DATE, { sunset: { solarEventTime: `${DATE}T17:00:00` } }]]) };
    const events = [sunsetRow()];
    const out = withScopedSolarTimes({
      events, spots: [{}, null], locations: [bare], formatTimeUk: echo,
    });
    expect(out).toBe(events);
  });
});

describe('withScopedSolarTimes — the forecast store and equal instants', () => {
  it('skips a place whose forecast store is a plain object rather than a Map', () => {
    const plain = { id: 1, name: 'Plain', forecastsByDate: {} };
    const events = [sunsetRow()];
    const out = withScopedSolarTimes({
      events, spots: [{ id: 1 }], locations: [plain], formatTimeUk: echo,
    });
    expect(out).toEqual([sunsetRow()]);
  });

  it('never reads a forecast slot for an event type it has no slot for', () => {
    const odd = {
      id: 1,
      name: 'Odd',
      forecastsByDate: new Map([[DATE, { null: { solarEventTime: `${DATE}T17:00:00` } }]]),
    };
    const events = [sunsetRow({ eventType: 'MOON' })];
    const out = withScopedSolarTimes({
      events, spots: [{ id: 1 }], locations: [odd], formatTimeUk: echo,
    });
    expect(out).toEqual([sunsetRow({ eventType: 'MOON' })]);
  });

  it('skips a place with no forecast store at all and still reads the others', () => {
    const none = { id: 1, name: 'None' };
    const [row] = withScopedSolarTimes({
      events: [sunsetRow()],
      spots: [{ id: 1 }, { id: 2 }],
      locations: [none, loc(2, 'B', `${DATE}T17:00:00`)],
      formatTimeUk: echo,
    });
    expect(row.time).toBe(`${DATE}T17:00:00`);
  });

  it('keeps the first-seen printing when two places share one instant', () => {
    const [row] = withScopedSolarTimes({
      events: [sunsetRow()],
      spots: [{ id: 1 }, { id: 2 }],
      locations: [loc(1, 'A', `${DATE}T17:00:00Z`), loc(2, 'B', `${DATE}T17:00:00+00:00`)],
      formatTimeUk: echo,
    });
    expect(row.time).toBe(`${DATE}T17:00:00Z`);
  });

  it('takes a strictly earlier instant seen later', () => {
    const [row] = withScopedSolarTimes({
      events: [sunsetRow()],
      spots: [{ id: 1 }, { id: 2 }],
      locations: [loc(1, 'A', `${DATE}T17:10:00Z`), loc(2, 'B', `${DATE}T17:05:00Z`)],
      formatTimeUk: echo,
    });
    expect(row.time).toBe(`${DATE}T17:05:00Z`);
  });
});
