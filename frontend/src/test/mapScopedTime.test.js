/**
 * Tests for `utils/mapScopedTime.js` — the Map tab's window time taken over the places in scope.
 *
 * Covers: the earliest in-scope sunset replacing the catalogue-wide served time (the 18:09 vs
 * 18:14 report of 2026-10-13); places outside the scope never setting it; the sunrise/sunset slot
 * choice; night rows untouched; the served time kept when the scope holds no timed place; and the
 * lookup by id before name.
 */
import { describe, it, expect } from 'vitest';
import { withScopedSolarTimes } from '../utils/mapScopedTime.js';
import { formatEventTimeUk } from '../utils/conversions.js';

const DATE = '2026-10-13';

function loc(id, name, { sunrise = null, sunset = null } = {}) {
  return {
    id,
    name,
    forecastsByDate: new Map([[DATE, {
      sunrise: sunrise ? { solarEventTime: sunrise } : null,
      sunset: sunset ? { solarEventTime: sunset } : null,
      hourly: [],
    }]]),
  };
}

const ANGEL = loc(1, 'Angel of the North', { sunrise: `${DATE}T06:31:00`, sunset: `${DATE}T17:14:00` });
const PENSHAW = loc(2, 'Penshaw Monument', { sunrise: `${DATE}T06:30:00`, sunset: `${DATE}T17:13:00` });
const HOLY_ISLAND = loc(3, 'Holy Island', { sunrise: `${DATE}T06:33:00`, sunset: `${DATE}T17:09:00` });
const LOCATIONS = [ANGEL, PENSHAW, HOLY_ISLAND];

const SUNSET_ROW = {
  id: `solar:${DATE}:SUNSET`, kind: 'solar', date: DATE, eventType: 'SUNSET', time: '18:09',
};
const SUNRISE_ROW = {
  id: `solar:${DATE}:SUNRISE`, kind: 'solar', date: DATE, eventType: 'SUNRISE', time: '07:33',
};
const ASTRO_ROW = {
  id: `astro:${DATE}:ASTRO`, kind: 'astro', date: DATE, eventType: 'ASTRO', time: '19:40',
};

function run(events, spots) {
  return withScopedSolarTimes({
    events, spots, locations: LOCATIONS, formatTimeUk: formatEventTimeUk,
  });
}

describe('withScopedSolarTimes', () => {
  it('takes the earliest sunset among in-scope places, not the catalogue-wide served time', () => {
    const [row] = run([SUNSET_ROW], [{ id: 1, name: 'Angel of the North' }, { id: 2, name: 'Penshaw Monument' }]);
    // 17:13 UTC is 18:13 BST — Penshaw, the earliest in scope; Holy Island's 18:09 is out of scope.
    expect(row.time).toBe('18:13');
    expect(row.id).toBe(SUNSET_ROW.id);
  });

  it('still reports the catalogue-earliest time when that place is in scope', () => {
    const [row] = run([SUNSET_ROW], [{ id: 1, name: 'Angel of the North' }, { id: 3, name: 'Holy Island' }]);
    expect(row.time).toBe('18:09');
  });

  it('reads the sunrise slot for a sunrise row', () => {
    const [row] = run([SUNRISE_ROW], [{ id: 1, name: 'Angel of the North' }]);
    expect(row.time).toBe('07:31');
  });

  it('leaves night rows unchanged', () => {
    const rows = run([ASTRO_ROW], [{ id: 1, name: 'Angel of the North' }]);
    expect(rows[0]).toBe(ASTRO_ROW);
  });

  it('keeps the served time when no in-scope place has a forecast row for the window', () => {
    const untimed = loc(9, 'Nowhere');
    const rows = withScopedSolarTimes({
      events: [SUNSET_ROW], spots: [{ id: 9, name: 'Nowhere' }], locations: [untimed],
      formatTimeUk: formatEventTimeUk,
    });
    expect(rows[0]).toBe(SUNSET_ROW);
  });

  it('keeps every row as it was when the scope is empty', () => {
    const events = [SUNSET_ROW, SUNRISE_ROW];
    expect(run(events, [])).toBe(events);
  });

  it('matches a spot by id before name', () => {
    // The id names Penshaw (18:13); the name names a DIFFERENT place, the Angel (18:14). A name-first
    // lookup would print 18:14.
    const [row] = run([SUNSET_ROW], [{ id: 2, name: 'Angel of the North' }]);
    expect(row.time).toBe('18:13');
  });

  it('falls back to the name when a spot has no id', () => {
    const [row] = run([SUNSET_ROW], [{ id: null, name: 'Angel of the North' }]);
    expect(row.time).toBe('18:14');
  });

  it('leaves a filler row (not served by the briefing) as it was', () => {
    const filler = { ...SUNRISE_ROW, served: false, time: '' };
    const rows = run([filler], [{ id: 1, name: 'Angel of the North' }]);
    expect(rows[0]).toBe(filler);
  });

  it('returns the same row object when the scoped time equals the served one', () => {
    const served = { ...SUNSET_ROW, time: '18:14' };
    const [row] = run([served], [{ id: 1, name: 'Angel of the North' }]);
    expect(row).toBe(served);
  });
});
