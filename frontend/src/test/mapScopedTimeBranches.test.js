import { describe, it, expect } from 'vitest';
import { withScopedSolarTimes } from '../utils/mapScopedTime.js';

const DATE = '2026-10-13';

const fmt = (utc) => (utc ? utc.slice(11, 16) : null);

function loc(id, name, sunset, extra = {}) {
  return {
    id,
    name,
    forecastsByDate: new Map([[DATE, { sunset: sunset ? { solarEventTime: sunset } : null }]]),
    ...extra,
  };
}

const ROW = {
  id: 'solar', kind: 'solar', date: DATE, eventType: 'SUNSET', time: '18:09', served: true,
};

const run = (events, spots, locations) => withScopedSolarTimes({
  events, spots, locations, formatTimeUk: fmt,
});

describe('withScopedSolarTimes input edges', () => {
  it('returns an empty list for non-array events and the rows untouched for an empty pool', () => {
    expect(run(null, [{ id: 1 }], [])).toEqual([]);
    const rows = [ROW];
    expect(run(rows, [], [loc(1, 'A', `${DATE}T17:00:00`)])).toBe(rows);
    expect(run(rows, null, [loc(1, 'A', `${DATE}T17:00:00`)])).toBe(rows);
  });

  it('returns the rows untouched when no spot resolves to a location', () => {
    const rows = [ROW];
    expect(run(rows, [{ id: 99, name: 'Ghost' }, null], [loc(1, 'A', `${DATE}T17:00:00`)])).toBe(rows);
    expect(run(rows, [{ id: 1 }], null)).toBe(rows);
  });

  it('skips null locations and indexes by name when a place has no id', () => {
    const named = { name: 'Nameonly', forecastsByDate: new Map([[DATE, { sunset: { solarEventTime: `${DATE}T17:20:00` } }]]) };
    const [row] = run([ROW], [{ name: 'Nameonly' }], [null, named]);
    expect(row.time).toBe('17:20');
  });

  it('resolves a spot by name when its id matches no location', () => {
    const [row] = run([ROW], [{ id: 77, name: 'A' }], [loc(1, 'A', `${DATE}T17:05:00`)]);
    expect(row.time).toBe('17:05');
  });

  it('indexes a location with an id but no name by id alone', () => {
    const [row] = run([ROW], [{ id: 5 }], [loc(5, '', `${DATE}T17:07:00`)]);
    expect(row.time).toBe('17:07');
  });
});

describe('withScopedSolarTimes row eligibility', () => {
  const places = [loc(1, 'A', `${DATE}T17:10:00`)];
  const spots = [{ id: 1 }];

  it('leaves non-solar rows, dateless rows and unknown event types alone', () => {
    const astro = { ...ROW, kind: 'astro' };
    const dateless = { ...ROW, date: undefined };
    const odd = { ...ROW, eventType: 'NOON' };
    const out = run([astro, dateless, odd, null], spots, places);
    expect(out[0]).toBe(astro);
    expect(out[1]).toBe(dateless);
    expect(out[2]).toBe(odd);
    expect(out[3]).toBeNull();
  });

  it('leaves a withdrawn filler alone but narrows a past row', () => {
    const filler = { ...ROW, served: false };
    const past = { ...ROW, served: false, past: true };
    const out = run([filler, past], spots, places);
    expect(out[0]).toBe(filler);
    expect(out[1].time).toBe('17:10');
  });

  it('keeps the same row when the scoped time equals it, or the scope holds no timed place', () => {
    const same = { ...ROW, time: '17:10' };
    expect(run([same], spots, places)[0]).toBe(same);
    const untimed = [loc(1, 'A', null)];
    expect(run([ROW], spots, untimed)[0]).toBe(ROW);
  });

  it('ignores unparseable times and places without a forecast for the date', () => {
    const bad = loc(1, 'A', 'not-a-time');
    const noForecast = { id: 2, name: 'B', forecastsByDate: new Map() };
    const noMap = { id: 3, name: 'C' };
    expect(run([ROW], [{ id: 1 }, { id: 2 }, { id: 3 }], [bad, noForecast, noMap])[0]).toBe(ROW);
  });

  it('takes the earliest among several places, whichever order they come in', () => {
    const early = loc(1, 'A', `${DATE}T17:00:00`);
    const late = loc(2, 'B', `${DATE}T17:30:00`);
    expect(run([ROW], [{ id: 1 }, { id: 2 }], [early, late])[0].time).toBe('17:00');
    expect(run([ROW], [{ id: 2 }, { id: 1 }], [early, late])[0].time).toBe('17:00');
  });

  it('reads the sunrise slot for a sunrise row', () => {
    const place = {
      id: 1, name: 'A', forecastsByDate: new Map([[DATE, { sunrise: { solarEventTime: `${DATE}T06:30:00` } }]]),
    };
    const sunrise = { ...ROW, eventType: 'SUNRISE', time: '07:00' };
    expect(run([sunrise], [{ id: 1 }], [place])[0].time).toBe('06:30');
  });
});
