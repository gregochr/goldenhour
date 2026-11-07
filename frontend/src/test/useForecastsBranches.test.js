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

const { mockUseAuth } = vi.hoisted(() => ({ mockUseAuth: vi.fn(() => ({ role: 'PRO_USER' })) }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: mockUseAuth }));

import { fetchForecasts, fetchForecastsForDate, fetchLocations } from '../api/forecastApi';

const BARE_LOCATION = { id: 3, name: 'Bare Site', lat: 55.1, lon: -1.2 };

const row = (date, type, extra = {}) => ({
  locationName: 'Bare Site',
  locationLat: 55.1,
  locationLon: -1.2,
  targetDate: date,
  targetType: type,
  forecastRunAt: `${date}T06:00:00Z`,
  rating: 3,
  ...extra,
});

describe('useForecasts — defaults and fallbacks', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    setRewind(null);
    mockUseAuth.mockReturnValue({ role: 'PRO_USER' });
  });

  it('gives a location with no type metadata the documented defaults', async () => {
    fetchForecasts.mockResolvedValue([]);
    fetchLocations.mockResolvedValue([BARE_LOCATION]);

    const { result } = renderHook(() => useForecasts());
    await waitFor(() => expect(result.current.loading).toBe(false));

    const [loc] = result.current.locations;
    expect(loc.locationType).toEqual([]);
    expect(loc.tideType).toEqual([]);
    expect(loc.solarEventType).toEqual(['SUNRISE', 'SUNSET']);
    expect(loc.bortleClass).toBeNull();
    expect(loc.regionName).toBeNull();
    expect(loc.subRegionId).toBeNull();
    expect(loc.subRegionName).toBeNull();
  });

  it('keys the instant-paint cache under "anon" when there is no role', async () => {
    mockUseAuth.mockReturnValue({ role: null });
    fetchForecasts.mockResolvedValue([row('2026-03-03', 'SUNSET')]);
    fetchLocations.mockResolvedValue([BARE_LOCATION]);

    const { result } = renderHook(() => useForecasts());
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(localStorage.getItem(storageKey('forecasts:anon'))).not.toBeNull();
  });

  it('hydrates from the anon cache when there is no role', async () => {
    mockUseAuth.mockReturnValue({ role: undefined });
    writeSwrCache('forecasts:anon', {
      forecasts: [row('2026-03-03', 'SUNSET')],
      locationMeta: [BARE_LOCATION],
    });
    fetchForecasts.mockReturnValue(new Promise(() => {}));
    fetchLocations.mockReturnValue(new Promise(() => {}));

    const { result } = renderHook(() => useForecasts());

    expect(result.current.loading).toBe(false);
    expect(result.current.locations.map((l) => l.name)).toEqual(['Bare Site']);
  });

  it('falls back to a generic message when a cold load fails with no message at all', async () => {
    fetchForecasts.mockRejectedValue({});
    fetchLocations.mockResolvedValue([BARE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.error).toBe('Failed to load forecast data. Please try again.'));
    expect(result.current.loading).toBe(false);
  });

  it('falls back to a generic message when an older-day fetch fails with no message at all', async () => {
    fetchForecasts.mockResolvedValue([row('2026-03-03', 'SUNSET')]);
    fetchLocations.mockResolvedValue([BARE_LOCATION]);
    const { result } = renderHook(() => useForecasts());
    await waitFor(() => expect(result.current.loading).toBe(false));
    fetchForecastsForDate.mockRejectedValue({});

    await act(async () => { await result.current.loadOlderDay('2026-03-01'); });

    expect(result.current.error).toBe('Failed to load the earlier day. Please try again.');
    expect(result.current.loadedPastDays).toEqual([]);
  });

  it('treats a non-array older-day answer as a loaded, empty day', async () => {
    fetchForecasts.mockResolvedValue([row('2026-03-03', 'SUNSET')]);
    fetchLocations.mockResolvedValue([BARE_LOCATION]);
    const { result } = renderHook(() => useForecasts());
    await waitFor(() => expect(result.current.loading).toBe(false));
    fetchForecastsForDate.mockResolvedValue(null);

    await act(async () => { await result.current.loadOlderDay('2026-03-01'); });

    expect(result.current.loadedPastDays).toEqual(['2026-03-01']);
    expect(result.current.emptyPastDays).toEqual(['2026-03-01']);
    expect(result.current.error).toBeNull();
  });

  it('leaves a date the list already holds untouched when the older day adds nothing to it', async () => {
    const hourly = (date, hour) => row(date, 'HOURLY', { solarEventTime: `${date}T${hour}:00:00` });
    fetchForecasts.mockResolvedValue([row('2026-03-02', 'SUNSET'), hourly('2026-03-02', '09')]);
    fetchLocations.mockResolvedValue([BARE_LOCATION]);
    const { result } = renderHook(() => useForecasts());
    await waitFor(() => expect(result.current.loading).toBe(false));
    const before = result.current.locations[0].forecastsByDate.get('2026-03-02');
    fetchForecastsForDate.mockResolvedValue([row('2026-03-02', 'SUNSET', { rating: 1 })]);

    await act(async () => { await result.current.loadOlderDay('2026-03-02'); });

    expect(result.current.locations[0].forecastsByDate.get('2026-03-02')).toBe(before);
    expect(result.current.locations[0].forecastsByDate.get('2026-03-02').sunset.rating).toBe(3);
  });
});
