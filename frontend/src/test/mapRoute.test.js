import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  parseMapRoute, formatMapRoute, routeWindowStatus, bareAddress, takeMapRoute, consumeMapRoute, __resetMapRouteForTests, MAP_HASH,
} from '../utils/mapRoute.js';

describe('parseMapRoute', () => {
  it('reads a window and a place', () => {
    expect(parseMapRoute('#map/2026-11-24/sunrise/12')).toEqual({
      date: '2026-11-24', eventType: 'SUNRISE', locationId: 12,
    });
  });

  it.each([
    ['sunrise', 'SUNRISE'], ['sunset', 'SUNSET'], ['night', 'ASTRO'], ['aurora', 'AURORA'],
  ])('maps the word %s to %s', (word, eventType) => {
    expect(parseMapRoute(`#map/2026-11-24/${word}`)).toEqual({ date: '2026-11-24', eventType, locationId: null });
  });

  it('accepts a hash with no leading #', () => {
    expect(parseMapRoute('map/2026-11-24/sunset')).toMatchObject({ eventType: 'SUNSET' });
  });

  it('reads the bare #map as the map with no window', () => {
    expect(parseMapRoute('#map')).toEqual({ date: null, eventType: null, locationId: null });
  });

  it.each([
    ['#manage/runs/start'], [''], ['#'], ['#mapping/2026-11-24/sunrise'], ['#token=abc'],
  ])('answers null for %j (not a map address)', (hash) => {
    expect(parseMapRoute(hash)).toBeNull();
  });

  it.each([[null], [undefined], [42], [{}]])('answers null for the non-string %j', (value) => {
    expect(parseMapRoute(value)).toBeNull();
  });

  it.each([
    ['#map/not-a-date/sunrise'],
    ['#map/2026-13-45/sunrise'],
    ['#map/2026-02-30/sunrise'],
    ['#map/2026-11-24/dusk'],
    ['#map/2026-11-24'],
    ['#map/2026-11-24/'],
  ])('keeps the map but drops the window for %s', (hash) => {
    expect(parseMapRoute(hash)).toEqual({ date: null, eventType: null, locationId: null });
  });

  it.each([['abc'], ['0'], ['-3'], ['1.5'], ['99999999999'], ['']])(
    'drops a bad place id %j but keeps the window',
    (id) => {
      expect(parseMapRoute(`#map/2026-11-24/sunrise/${id}`)).toEqual({
        date: '2026-11-24', eventType: 'SUNRISE', locationId: null,
      });
    },
  );

  it('accepts a leap day only in a leap year', () => {
    expect(parseMapRoute('#map/2028-02-29/sunrise').date).toBe('2028-02-29');
    expect(parseMapRoute('#map/2026-02-29/sunrise').date).toBeNull();
  });
});

describe('formatMapRoute', () => {
  it('formats a window', () => {
    expect(formatMapRoute({ date: '2026-11-24', eventType: 'SUNSET' })).toBe('#map/2026-11-24/sunset');
  });

  it('adds a place id', () => {
    expect(formatMapRoute({ date: '2026-11-24', eventType: 'ASTRO', locationId: 7 })).toBe('#map/2026-11-24/night/7');
  });

  it('accepts a string id', () => {
    expect(formatMapRoute({ date: '2026-11-24', eventType: 'AURORA', locationId: '7' })).toBe('#map/2026-11-24/aurora/7');
  });

  it.each([[0], [-1], ['x'], [null], [undefined], [1.5]])('leaves out the bad id %j', (locationId) => {
    expect(formatMapRoute({ date: '2026-11-24', eventType: 'SUNRISE', locationId })).toBe('#map/2026-11-24/sunrise');
  });

  it.each([
    [null], [undefined], [{}],
    [{ date: '2026-11-24', eventType: 'HIGH_WATER' }],
    [{ date: 'nope', eventType: 'SUNRISE' }],
    [{ eventType: 'SUNRISE' }],
  ])('answers the bare #map for %j', (route) => {
    expect(formatMapRoute(route)).toBe(MAP_HASH);
  });

  it('round-trips through parse', () => {
    const route = { date: '2026-12-01', eventType: 'SUNRISE', locationId: 31 };
    expect(parseMapRoute(formatMapRoute(route))).toEqual(route);
  });
});

describe('bareAddress', () => {
  it('is the path and query without the hash', () => {
    expect(bareAddress({ pathname: '/app', search: '?x=1' })).toBe('/app?x=1');
  });

  it('is empty with no location at all', () => {
    expect(bareAddress(null)).toBe('');
  });
});

describe('takeMapRoute', () => {
  beforeEach(() => {
    __resetMapRouteForTests();
    window.history.replaceState(null, '', '/');
  });
  afterEach(() => {
    vi.restoreAllMocks();
    __resetMapRouteForTests();
    window.history.replaceState(null, '', '/');
  });

  it('reads the hash once and rewrites the address to the bare path, copying the state', () => {
    window.history.replaceState({ keep: 1 }, '', '/?q=1#map/2026-11-24/sunrise/5');
    const spy = vi.spyOn(window.history, 'replaceState');
    expect(takeMapRoute()).toEqual({ date: '2026-11-24', eventType: 'SUNRISE', locationId: 5 });
    expect(spy).toHaveBeenCalledWith({ keep: 1 }, '', '/?q=1');
    expect(window.location.hash).toBe('');
    expect(window.location.search).toBe('?q=1');
  });

  it('answers the same route again until it is consumed (a StrictMode double read)', () => {
    window.history.replaceState(null, '', '/#map/2026-11-24/sunset');
    const first = takeMapRoute();
    expect(takeMapRoute()).toBe(first);
    consumeMapRoute();
    expect(takeMapRoute()).toBeNull();
  });

  it('answers null and touches nothing for another hash', () => {
    window.history.replaceState(null, '', '/#manage/runs/start');
    const spy = vi.spyOn(window.history, 'replaceState');
    expect(takeMapRoute()).toBeNull();
    expect(spy).not.toHaveBeenCalled();
    expect(window.location.hash).toBe('#manage/runs/start');
  });

  it('still answers the route when the history call throws', () => {
    window.history.replaceState(null, '', '/#map');
    vi.spyOn(window.history, 'replaceState').mockImplementation(() => { throw new Error('SecurityError'); });
    expect(takeMapRoute()).toEqual({ date: null, eventType: null, locationId: null });
  });
});

describe('routeWindowStatus: a night runs past midnight (the 01:30 case)', () => {
  const facts = { todayStr: '2026-11-24', forecastDates: ['2026-11-24'], windowKeys: new Set() };

  it('treats yesterday\'s aurora and astro nights as current, not passed', () => {
    expect(routeWindowStatus({ date: '2026-11-23', eventType: 'AURORA' }, facts)).toBe('ok');
    expect(routeWindowStatus({ date: '2026-11-23', eventType: 'ASTRO' }, facts)).toBe('ok');
  });

  it('calls a night from two days ago passed', () => {
    expect(routeWindowStatus({ date: '2026-11-22', eventType: 'AURORA' }, facts)).toBe('past');
    expect(routeWindowStatus({ date: '2026-11-22', eventType: 'ASTRO' }, facts)).toBe('past');
  });

  it('still calls yesterday\'s SOLAR window passed', () => {
    expect(routeWindowStatus({ date: '2026-11-23', eventType: 'SUNSET' }, facts)).toBe('past');
  });

  it('crosses a month boundary: on the 1st, the last day of the previous month is yesterday', () => {
    const first = { todayStr: '2026-12-01', forecastDates: null, windowKeys: null };
    expect(routeWindowStatus({ date: '2026-11-30', eventType: 'AURORA' }, first)).toBe('ok');
    expect(routeWindowStatus({ date: '2026-11-29', eventType: 'AURORA' }, first)).toBe('past');
  });

  it('a night address round-trips: format, then parse, names the same night', () => {
    ['AURORA', 'ASTRO'].forEach((eventType) => {
      const hash = formatMapRoute({ date: '2026-11-23', eventType, locationId: null });
      expect(parseMapRoute(hash)).toEqual({ date: '2026-11-23', eventType, locationId: null });
    });
  });
});
