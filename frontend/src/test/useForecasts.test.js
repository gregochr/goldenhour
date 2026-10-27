import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { useForecasts } from '../hooks/useForecasts.js';
import { storageKey, writeSwrCache } from '../utils/swrCache.js';
import { setRewind } from '../utils/rewind.js';

vi.mock('../api/forecastApi', () => ({
  fetchForecasts: vi.fn(),
  fetchForecastsForDate: vi.fn(),
  fetchLocations: vi.fn(),
}));

// useForecasts now reads the auth role to key its instant-paint cache; mock it so the hook can be
// rendered without an AuthProvider, and so individual tests can vary the role.
const { mockUseAuth } = vi.hoisted(() => ({ mockUseAuth: vi.fn(() => ({ role: 'PRO_USER' })) }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: mockUseAuth }));

import {
  fetchForecasts, fetchForecastsForDate, fetchLocations,
} from '../api/forecastApi';

const LANDSCAPE_LOCATION = {
  name: 'Bamburgh Castle',
  lat: 55.609,
  lon: -1.709,
  locationType: ['SEASCAPE'],
  tideType: ['HIGH'],
  solarEventType: ['SUNSET'],
  enabled: true,
};

const WILDLIFE_LOCATION = {
  name: 'Low Barns',
  lat: 54.678,
  lon: -1.752,
  locationType: ['WILDLIFE'],
  tideType: [],
  solarEventType: ['ALLDAY'],
  enabled: true,
};

const AURORA_LOCATION = {
  id: 7,
  name: 'Kielder',
  lat: 55.2,
  lon: -2.6,
  locationType: ['LANDSCAPE'],
  tideType: [],
  solarEventType: ['SUNSET'],
  bortleClass: 3,
  region: { name: 'Northumberland' },
  enabled: true,
};

const DISABLED_LOCATION = {
  name: 'Closed Site',
  lat: 55.0,
  lon: -1.5,
  locationType: ['LANDSCAPE'],
  tideType: [],
  solarEventType: ['SUNSET'],
  enabled: false,
};

const BAMBURGH_FORECAST = {
  locationName: 'Bamburgh Castle',
  locationLat: 55.609,
  locationLon: -1.709,
  targetDate: '2026-03-03',
  targetType: 'SUNSET',
  forecastRunAt: '2026-03-03T06:00:00Z',
  rating: 4,
  fierySkyPotential: 78,
  goldenHourPotential: 62,
};

describe('useForecasts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Each test starts with a cold instant-paint cache so `loading` begins true and the existing
    // `waitFor(loading === false)` assertions synchronise on the fetch, not a prior test's cache.
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'PRO_USER' });
  });

  it('includes wildlife locations that have no forecast rows', async () => {
    fetchForecasts.mockResolvedValue([BAMBURGH_FORECAST]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION, WILDLIFE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    const names = result.current.locations.map((l) => l.name);
    expect(names).toContain('Low Barns');
    expect(names).toContain('Bamburgh Castle');
  });

  it('wildlife location has an empty forecastsByDate map', async () => {
    fetchForecasts.mockResolvedValue([BAMBURGH_FORECAST]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION, WILDLIFE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    const wildlife = result.current.locations.find((l) => l.name === 'Low Barns');
    expect(wildlife).toBeDefined();
    expect(wildlife.forecastsByDate).toBeInstanceOf(Map);
    expect(wildlife.forecastsByDate.size).toBe(0);
  });

  it('preserves locationType metadata from locations API', async () => {
    fetchForecasts.mockResolvedValue([BAMBURGH_FORECAST]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION, WILDLIFE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    const wildlife = result.current.locations.find((l) => l.name === 'Low Barns');
    expect(wildlife.locationType).toEqual(['WILDLIFE']);

    const seascape = result.current.locations.find((l) => l.name === 'Bamburgh Castle');
    expect(seascape.locationType).toEqual(['SEASCAPE']);
  });

  it('excludes disabled locations', async () => {
    fetchForecasts.mockResolvedValue([BAMBURGH_FORECAST]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION, WILDLIFE_LOCATION, DISABLED_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    const names = result.current.locations.map((l) => l.name);
    expect(names).not.toContain('Closed Site');
  });

  it('attaches forecast data to locations that have evaluations', async () => {
    fetchForecasts.mockResolvedValue([BAMBURGH_FORECAST]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    const bamburgh = result.current.locations.find((l) => l.name === 'Bamburgh Castle');
    expect(bamburgh.forecastsByDate.has('2026-03-03')).toBe(true);
    expect(bamburgh.forecastsByDate.get('2026-03-03').sunset.rating).toBe(4);
  });

  it('returns locations even when there are zero forecast rows', async () => {
    fetchForecasts.mockResolvedValue([]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION, WILDLIFE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.locations).toHaveLength(2);
  });

  it('forwards id, bortleClass, and regionName from location metadata', async () => {
    fetchForecasts.mockResolvedValue([]);
    fetchLocations.mockResolvedValue([AURORA_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    const kielder = result.current.locations.find((l) => l.name === 'Kielder');
    expect(kielder.id).toBe(7);
    expect(kielder.bortleClass).toBe(3);
    expect(kielder.regionName).toBe('Northumberland');
  });

  it('makes two requests on load — forecasts and locations', async () => {
    fetchForecasts.mockResolvedValue([BAMBURGH_FORECAST]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION, WILDLIFE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    // The old `GET /api/outcome/all` rode here and fed no reader (outcome-rating-plan.md §4 #7); the
    // reader's own ratings come through `useOutcomeRatings`, mounted beside the map.
    expect(fetchForecasts).toHaveBeenCalledTimes(1);
    expect(fetchLocations).toHaveBeenCalledTimes(1);
  });

  it('sets error and clears loading when the locations fetch rejects on a cold load', async () => {
    fetchForecasts.mockResolvedValue([BAMBURGH_FORECAST]);
    fetchLocations.mockRejectedValue(new Error('locations service unavailable'));

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.error).toBe('locations service unavailable');
    expect(result.current.locations).toEqual([]);
  });

  it('builds a location with no outcomes field (it fed no reader; plan section 4 #7)', async () => {
    fetchForecasts.mockResolvedValue([]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    const bamburgh = result.current.locations.find((l) => l.name === 'Bamburgh Castle');
    expect(bamburgh).not.toHaveProperty('outcomes');
  });

  it('defaults bortleClass to null and regionName to null when absent', async () => {
    fetchForecasts.mockResolvedValue([]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    const bamburgh = result.current.locations.find((l) => l.name === 'Bamburgh Castle');
    expect(bamburgh.bortleClass).toBeNull();
    expect(bamburgh.regionName).toBeNull();
  });

  it('writes the cache without an outcomes key', async () => {
    fetchForecasts.mockResolvedValue([BAMBURGH_FORECAST]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    const stored = JSON.parse(localStorage.getItem(storageKey('forecasts:PRO_USER')));
    expect(Object.keys(stored.value).sort()).toEqual(['forecasts', 'locationMeta']);
  });

  it('hydrates from a payload cached BEFORE the outcomes fetch was removed (it still carries outcomes)', () => {
    writeSwrCache('forecasts:PRO_USER', {
      forecasts: [BAMBURGH_FORECAST],
      locationMeta: [LANDSCAPE_LOCATION],
      outcomes: [{ id: 1, locationName: 'Bamburgh Castle' }],
    });
    fetchForecasts.mockReturnValue(new Promise(() => {}));
    fetchLocations.mockReturnValue(new Promise(() => {}));

    const { result } = renderHook(() => useForecasts());

    // Instant paint survives the upgrade, and the stale field is dropped, not attached.
    expect(result.current.loading).toBe(false);
    expect(result.current.locations.map((l) => l.name)).toEqual(['Bamburgh Castle']);
    expect(result.current.locations[0]).not.toHaveProperty('outcomes');
  });

  it('hydrates synchronously from the role-keyed cache before the fetch resolves', async () => {
    // Prime the cache as if a previous session had loaded this payload for this role.
    writeSwrCache('forecasts:PRO_USER', {
      forecasts: [BAMBURGH_FORECAST],
      locationMeta: [LANDSCAPE_LOCATION],
    });
    // Make the live fetch hang so only the cache can supply data on the first render.
    let resolveForecasts;
    fetchForecasts.mockReturnValue(new Promise((r) => { resolveForecasts = r; }));
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    // Instant paint: no loading flash, and the cached location is already present this tick.
    expect(result.current.loading).toBe(false);
    expect(result.current.locations.map((l) => l.name)).toContain('Bamburgh Castle');
    expect(result.current.locations[0].forecastsByDate).toBeInstanceOf(Map);

    resolveForecasts([BAMBURGH_FORECAST]);
    await waitFor(() => expect(fetchForecasts).toHaveBeenCalledTimes(1));
  });

  it('under an admin rewind the cache is neither painted from nor written to, and the live entry survives the round trip', async () => {
    // A LIVE payload a previous session left behind.
    writeSwrCache('forecasts:PRO_USER', {
      forecasts: [BAMBURGH_FORECAST], locationMeta: [LANDSCAPE_LOCATION],
    });
    const liveEntry = localStorage.getItem(storageKey('forecasts:PRO_USER'));
    expect(liveEntry).not.toBeNull();
    // The REWOUND fetch answers with a different roster, so a write would be visible.
    const rewoundLocation = { ...LANDSCAPE_LOCATION, id: 99, name: 'Rewound Crag' };
    fetchForecasts.mockResolvedValue([{ ...BAMBURGH_FORECAST, locationName: 'Rewound Crag' }]);
    fetchLocations.mockResolvedValue([rewoundLocation]);
    try {
      setRewind('2026-10-04T04:58:00Z');
      const { result } = renderHook(() => useForecasts());

      // Not hydrated: the live payload must not paint a rewound page.
      expect(result.current.loading).toBe(true);
      expect(result.current.locations).toEqual([]);

      await waitFor(() => expect(result.current.loading).toBe(false));
      expect(result.current.locations.map((l) => l.name)).toEqual(['Rewound Crag']);
      // Not written: the live entry is untouched, byte for byte.
      expect(localStorage.getItem(storageKey('forecasts:PRO_USER'))).toBe(liveEntry);
    } finally {
      setRewind(null);
    }
    expect(localStorage.getItem(storageKey('forecasts:PRO_USER'))).toBe(liveEntry);
  });

  it("surfaces the server's error sentence, not axios's generic message, on a refused cold load", async () => {
    const refused = Object.assign(new Error('Request failed with status code 502'), {
      response: { status: 502, data: { error: 'Upstream API error: 503' } },
    });
    fetchForecasts.mockRejectedValue(refused);
    fetchLocations.mockRejectedValue(refused);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.error).toBe('Upstream API error: 503'));
  });

  it('keeps the stale map on a failed revalidation instead of blanking it', async () => {
    writeSwrCache('forecasts:PRO_USER', {
      forecasts: [BAMBURGH_FORECAST],
      locationMeta: [LANDSCAPE_LOCATION],
    });
    fetchForecasts.mockRejectedValue(new Error('backend down'));
    fetchLocations.mockRejectedValue(new Error('backend down'));

    const { result } = renderHook(() => useForecasts());

    // Painted from cache immediately...
    expect(result.current.locations.map((l) => l.name)).toContain('Bamburgh Castle');

    // ...and the failed revalidation leaves the stale data in place, with no error surfaced.
    await waitFor(() => expect(fetchForecasts).toHaveBeenCalled());
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeNull();
    expect(result.current.locations.map((l) => l.name)).toContain('Bamburgh Castle');
  });

  it('keys the cache by role so a LITE session does not read a PRO payload', async () => {
    writeSwrCache('forecasts:PRO_USER', {
      forecasts: [BAMBURGH_FORECAST],
      locationMeta: [LANDSCAPE_LOCATION],
    });
    mockUseAuth.mockReturnValue({ role: 'LITE_USER' });
    fetchForecasts.mockResolvedValue([]);
    fetchLocations.mockResolvedValue([WILDLIFE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    // No cache under the LITE key → cold start (loading true), never the PRO payload.
    expect(result.current.loading).toBe(true);
    expect(result.current.locations).toEqual([]);

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.locations.map((l) => l.name)).toEqual(['Low Barns']);
  });

  it('shows the loading skeleton (not the empty state) when the cache holds an empty payload', () => {
    // A prior fetch that returned nothing (fresh deploy / all-disabled roster) cached empty arrays.
    // That cache is structurally "valid" but yields zero locations — the initial loading flag must
    // stay true so the map shows the skeleton, not a one-frame "no forecasts loaded yet" flash.
    writeSwrCache('forecasts:PRO_USER', { forecasts: [], locationMeta: [] });
    fetchForecasts.mockReturnValue(new Promise(() => {})); // hang so only the cache decides this tick
    fetchLocations.mockReturnValue(new Promise(() => {}));

    const { result } = renderHook(() => useForecasts());

    expect(result.current.loading).toBe(true);
    expect(result.current.locations).toEqual([]);
  });

  it('surfaces an error when a user-initiated refresh fails, keeping the stale data', async () => {
    // First a successful load so data is on screen (hasDataRef becomes true).
    fetchForecasts.mockResolvedValue([BAMBURGH_FORECAST]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION]);

    const { result } = renderHook(() => useForecasts());
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.locations.length).toBeGreaterThan(0);
    expect(result.current.error).toBeNull();

    // An explicit user-initiated refresh that fails must surface an error (unlike a silent
    // background revalidation), while leaving the stale data in place for a retry.
    fetchForecasts.mockRejectedValue(new Error('run reload failed'));
    fetchLocations.mockRejectedValue(new Error('run reload failed'));
    await act(async () => {
      await result.current.refresh();
    });

    expect(result.current.error).toBe('run reload failed');
    expect(result.current.locations.length).toBeGreaterThan(0);
  });
});

/**
 * The older-day fetch (outcome-rating-plan.md §2 #2, §3 O3b step 1): `loadOlderDay(date)` keeps the
 * day's rows in hook state and merges them into `locations` — on every render, so a refresh keeps a
 * loaded T-3 and the date it contributes to `allDates`.
 */
describe('useForecasts — older days', () => {
  const row = (date, type, extra = {}) => ({
    locationName: 'Bamburgh Castle',
    locationLat: 55.609,
    locationLon: -1.709,
    targetDate: date,
    targetType: type,
    forecastRunAt: `${date}T06:00:00Z`,
    rating: 3,
    ...extra,
  });
  const OLDER = '2026-03-01';

  async function loaded() {
    fetchForecasts.mockResolvedValue([row('2026-03-03', 'SUNSET', { rating: 4 })]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION, WILDLIFE_LOCATION]);
    const hook = renderHook(() => useForecasts());
    await waitFor(() => expect(hook.result.current.loading).toBe(false));
    return hook;
  }
  const bamburgh = (result) => result.current.locations.find((l) => l.name === 'Bamburgh Castle');

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'PRO_USER' });
    setRewind(null);
  });

  it('merges the day\'s rows into the locations that hold them, and reports the day as loaded', async () => {
    const { result } = await loaded();
    expect(result.current.olderDayLoading).toBeNull();
    expect(result.current.loadedPastDays).toEqual([]);
    fetchForecastsForDate.mockResolvedValue([row(OLDER, 'SUNSET', { rating: 2 }), row(OLDER, 'SUNRISE')]);

    await act(async () => { await result.current.loadOlderDay(OLDER); });

    expect(fetchForecastsForDate).toHaveBeenCalledWith(OLDER);
    expect(fetchForecastsForDate).toHaveBeenCalledTimes(1);
    const entry = bamburgh(result).forecastsByDate.get(OLDER);
    expect(entry.sunset.rating).toBe(2);
    expect(entry.sunrise).not.toBeNull();
    expect(result.current.loadedPastDays).toEqual([OLDER]);
    expect(result.current.olderDayLoading).toBeNull();
  });

  it('puts the date in olderDayLoading while the request is out, then clears it', async () => {
    const { result } = await loaded();
    let resolve;
    fetchForecastsForDate.mockReturnValue(new Promise((r) => { resolve = r; }));

    let pending;
    act(() => { pending = result.current.loadOlderDay(OLDER); });
    expect(result.current.olderDayLoading).toBe(OLDER);

    await act(async () => { resolve([row(OLDER, 'SUNSET')]); await pending; });
    expect(result.current.olderDayLoading).toBeNull();
  });

  it('hands the rows over in the same render the loading state ends (a reader never sees "done, no rows")', async () => {
    fetchForecasts.mockResolvedValue([row('2026-03-03', 'SUNSET')]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION]);
    const seen = [];
    const { result } = renderHook(() => {
      const value = useForecasts();
      seen.push([value.olderDayLoading, value.loadedPastDays.length]);
      return value;
    });
    await waitFor(() => expect(result.current.loading).toBe(false));
    let resolve;
    fetchForecastsForDate.mockReturnValue(new Promise((r) => { resolve = r; }));

    let pending;
    act(() => { pending = result.current.loadOlderDay(OLDER); });
    await act(async () => { resolve([row(OLDER, 'SUNSET')]); await pending; });

    // From the first render that shows the fetch out, no render has loading cleared with the day
    // not yet merged: the effect that lands the selection reads the render in which it goes null.
    const start = seen.findIndex(([loading]) => loading === OLDER);
    expect(start).toBeGreaterThan(-1);
    expect(seen.slice(start).filter(([loading, n]) => loading === null && n === 0)).toEqual([]);
    expect(seen[seen.length - 1]).toEqual([null, 1]);
  });

  it('records an EMPTY answer as a loaded-empty day: the roster is unchanged and the day still counts', async () => {
    const { result } = await loaded();
    const before = result.current.locations;
    fetchForecastsForDate.mockResolvedValue([]);

    await act(async () => { await result.current.loadOlderDay(OLDER); });

    expect(result.current.loadedPastDays).toEqual([OLDER]);
    expect(result.current.emptyPastDays).toEqual([OLDER]);
    expect(bamburgh(result).forecastsByDate.has(OLDER)).toBe(false);
    expect(result.current.locations.map((l) => [...l.forecastsByDate.keys()]))
      .toEqual(before.map((l) => [...l.forecastsByDate.keys()]));
    expect(result.current.error).toBeNull();
  });

  it('names ONLY the days that came back empty in emptyPastDays, sorted, and none for a day with rows', async () => {
    const { result } = await loaded();
    expect(result.current.emptyPastDays).toEqual([]);

    fetchForecastsForDate.mockResolvedValue([row('2026-03-02', 'SUNSET')]);
    await act(async () => { await result.current.loadOlderDay('2026-03-02'); });
    expect(result.current.emptyPastDays).toEqual([]);

    fetchForecastsForDate.mockResolvedValue([]);
    await act(async () => { await result.current.loadOlderDay('2026-03-01'); });
    expect(result.current.loadedPastDays).toEqual(['2026-03-01', '2026-03-02']);
    expect(result.current.emptyPastDays).toEqual(['2026-03-01']);
  });

  it('a day holding only a hide\'s HOURLY rows is named empty too: it gives no window to step onto', async () => {
    const { result } = await loaded();
    fetchForecastsForDate.mockResolvedValue([row(OLDER, 'HOURLY', { rating: null })]);

    await act(async () => { await result.current.loadOlderDay(OLDER); });

    expect(result.current.loadedPastDays).toEqual([OLDER]);
    expect(result.current.emptyPastDays).toEqual([OLDER]);
  });

  it('a FAILED fetch is in neither list: nothing is recorded, so the same day is offered again', async () => {
    const { result } = await loaded();
    fetchForecastsForDate.mockRejectedValue(new Error('boom'));

    await act(async () => { await result.current.loadOlderDay(OLDER); });

    expect(result.current.loadedPastDays).toEqual([]);
    expect(result.current.emptyPastDays).toEqual([]);
  });

  it('never replaces a (date, type) entry the list payload already carries; it fills the missing type', async () => {
    fetchForecasts.mockResolvedValue([row('2026-03-03', 'SUNSET', { rating: 4 })]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION]);
    const { result } = renderHook(() => useForecasts());
    await waitFor(() => expect(result.current.loading).toBe(false));
    fetchForecastsForDate.mockResolvedValue([
      row('2026-03-03', 'SUNSET', { rating: 1, forecastRunAt: '2026-03-03T12:00:00Z' }),
      row('2026-03-03', 'SUNRISE', { rating: 5 }),
    ]);

    await act(async () => { await result.current.loadOlderDay('2026-03-03'); });

    const entry = bamburgh(result).forecastsByDate.get('2026-03-03');
    expect(entry.sunset.rating).toBe(4);
    expect(entry.sunrise.rating).toBe(5);
  });

  it('ignores a row for a place the roster does not hold, and orders rows however the server sent them', async () => {
    const { result } = await loaded();
    fetchForecastsForDate.mockResolvedValue([
      row(OLDER, 'SUNSET'),
      { ...row(OLDER, 'SUNSET'), locationName: 'Gone Site' },
      row(OLDER, 'SUNRISE'),
    ]);
    await act(async () => { await result.current.loadOlderDay(OLDER); });
    expect(result.current.locations.map((l) => l.name)).not.toContain('Gone Site');
    expect(Object.keys(bamburgh(result).forecastsByDate.get(OLDER)).sort()).toEqual(
      expect.arrayContaining(['sunrise', 'sunset']),
    );
  });

  it('merges a day\'s HOURLY rows: the whole entry on a new date, the list\'s own on an overlap, a fill when the list has none', async () => {
    const hourly = (date, hour, temperature) => ({
      locationName: 'Low Barns',
      locationLat: 54.678,
      locationLon: -1.752,
      targetDate: date,
      targetType: 'HOURLY',
      forecastRunAt: `${date}T05:00:00Z`,
      solarEventTime: `${date}T${hour}:00:00`,
      temperatureCelsius: temperature,
    });
    fetchForecasts.mockResolvedValue([
      row('2026-03-03', 'SUNSET'),
      hourly('2026-03-02', '09', 4),
    ]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION, WILDLIFE_LOCATION]);
    const { result } = renderHook(() => useForecasts());
    await waitFor(() => expect(result.current.loading).toBe(false));
    const barns = () => result.current.locations.find((l) => l.name === 'Low Barns');
    expect(barns().forecastsByDate.get('2026-03-02').hourly).toHaveLength(1);

    fetchForecastsForDate.mockResolvedValueOnce([hourly(OLDER, '08', 1), hourly(OLDER, '09', 2)]);
    await act(async () => { await result.current.loadOlderDay(OLDER); });
    expect(barns().forecastsByDate.get(OLDER).hourly).toHaveLength(2);

    // Overlap: the list holds hours for 03-02, so the older day's hours never replace them.
    fetchForecastsForDate.mockResolvedValueOnce([hourly('2026-03-02', '10', 9), hourly('2026-03-02', '11', 9)]);
    await act(async () => { await result.current.loadOlderDay('2026-03-02'); });
    expect(barns().forecastsByDate.get('2026-03-02').hourly).toHaveLength(1);
  });

  it('KEEPS the loaded day across a refresh that rebuilds the locations from the list payload', async () => {
    const { result } = await loaded();
    fetchForecastsForDate.mockResolvedValue([row(OLDER, 'SUNSET', { rating: 2 })]);
    await act(async () => { await result.current.loadOlderDay(OLDER); });
    expect(bamburgh(result).forecastsByDate.has(OLDER)).toBe(true);

    // The refresh's payload knows nothing of the older day.
    fetchForecasts.mockResolvedValue([row('2026-03-03', 'SUNSET', { rating: 5 })]);
    await act(async () => { await result.current.refresh(); });

    const entry = bamburgh(result).forecastsByDate.get(OLDER);
    expect(entry.sunset.rating).toBe(2);
    expect(bamburgh(result).forecastsByDate.get('2026-03-03').sunset.rating).toBe(5);
    expect(result.current.loadedPastDays).toEqual([OLDER]);
  });

  it('returns the very same locations array when nothing has been loaded (a reader who never steps back)', async () => {
    const { result, rerender } = await loaded();
    const first = result.current.locations;
    rerender();
    expect(result.current.locations).toBe(first);
  });

  it('never writes an older day to the SWR cache', async () => {
    const { result } = await loaded();
    const key = storageKey('forecasts:PRO_USER');
    const before = localStorage.getItem(key);
    fetchForecastsForDate.mockResolvedValue([row(OLDER, 'SUNSET')]);

    await act(async () => { await result.current.loadOlderDay(OLDER); });

    expect(localStorage.getItem(key)).toBe(before);
    expect(String(localStorage.getItem(key))).not.toContain(OLDER);
  });

  it('on a failed fetch leaves the roster unchanged, clears the loading state, reports the error and offers the day again', async () => {
    const { result } = await loaded();
    const before = result.current.locations;
    fetchForecastsForDate.mockRejectedValue(new Error('older day failed'));

    await act(async () => { await result.current.loadOlderDay(OLDER); });

    expect(result.current.error).toBe('older day failed');
    expect(result.current.olderDayLoading).toBeNull();
    expect(result.current.loadedPastDays).toEqual([]);
    expect(result.current.locations).toBe(before);

    fetchForecastsForDate.mockResolvedValue([row(OLDER, 'SUNSET')]);
    await act(async () => { await result.current.loadOlderDay(OLDER); });
    expect(fetchForecastsForDate).toHaveBeenCalledTimes(2);
    expect(result.current.loadedPastDays).toEqual([OLDER]);
  });

  it('does not ask for a day it already holds, nor for a second day while one is out', async () => {
    const { result } = await loaded();
    let resolve;
    fetchForecastsForDate.mockReturnValue(new Promise((r) => { resolve = r; }));

    let first;
    act(() => { first = result.current.loadOlderDay(OLDER); });
    await act(async () => { await result.current.loadOlderDay('2026-02-28'); });
    expect(fetchForecastsForDate).toHaveBeenCalledTimes(1);

    await act(async () => { resolve([row(OLDER, 'SUNSET')]); await first; });
    await act(async () => { await result.current.loadOlderDay(OLDER); });
    expect(fetchForecastsForDate).toHaveBeenCalledTimes(1);
  });

  it('stands down under an admin rewind: no request at all', async () => {
    const { result } = await loaded();
    setRewind('2026-03-02T12:00:00.000Z');
    try {
      await act(async () => { await result.current.loadOlderDay(OLDER); });
      expect(fetchForecastsForDate).not.toHaveBeenCalled();
      expect(result.current.olderDayLoading).toBeNull();
    } finally {
      setRewind(null);
    }
  });

  it('does nothing for a missing date', async () => {
    const { result } = await loaded();
    await act(async () => { await result.current.loadOlderDay(null); });
    expect(fetchForecastsForDate).not.toHaveBeenCalled();
  });
  it('drops a slow earlier response that lands after a newer refresh, so old-pick forecasts never come back', async () => {
    let releaseSlow;
    fetchForecasts.mockReturnValueOnce(new Promise((resolve) => { releaseSlow = resolve; }));
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION]);
    const { result } = renderHook(() => useForecasts());
    // The newer refresh (a sub-region save) answers first.
    fetchForecasts.mockResolvedValueOnce([row('2026-03-03', 'SUNSET', { rating: 5 })]);
    await act(async () => { await result.current.refresh(); });
    expect(bamburgh(result).forecastsByDate.get('2026-03-03').sunset.rating).toBe(5);

    await act(async () => { releaseSlow([row('2026-03-03', 'SUNSET', { rating: 1 })]); });

    expect(bamburgh(result).forecastsByDate.get('2026-03-03').sunset.rating).toBe(5);
  });
});
