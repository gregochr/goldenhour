import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useForecasts } from '../hooks/useForecasts.js';

vi.mock('../api/forecastApi', () => ({
  fetchForecasts: vi.fn(),
  fetchLocations: vi.fn(),
  fetchOutcomes: vi.fn(),
}));

import { fetchForecasts, fetchLocations, fetchOutcomes } from '../api/forecastApi';

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
    fetchOutcomes.mockResolvedValue([]);
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

  it('defaults bortleClass to null and regionName to null when absent', async () => {
    fetchForecasts.mockResolvedValue([]);
    fetchLocations.mockResolvedValue([LANDSCAPE_LOCATION]);

    const { result } = renderHook(() => useForecasts());

    await waitFor(() => expect(result.current.loading).toBe(false));

    const bamburgh = result.current.locations.find((l) => l.name === 'Bamburgh Castle');
    expect(bamburgh.bortleClass).toBeNull();
    expect(bamburgh.regionName).toBeNull();
  });
});
