import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import {
  peekForecastDetail, cacheForecastDetail, clearForecastDetailCache, fetchForecasts, fetchForecastsForDate,
  getForecastDetail, refreshTideData, backfillTideData, fetchLocations, addLocation, updateLocation,
  setLocationEnabled, resetLocationFailures, enrichLocation, fetchValleyFloorCandidates, fetchValleyFloorCell,
  geocodePlace,
} from '../api/forecastApi.js';

describe('forecastApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    clearForecastDetailCache();
    for (const verb of ['get', 'post', 'put']) apiClient[verb].mockResolvedValue({ data: 'body' });
  });

  describe('forecast detail cache', () => {
    it('reports a miss for an id never cached', () => {
      expect(peekForecastDetail(1)).toEqual({ cached: false, detail: null });
    });

    it('returns a cached detail', () => {
      cacheForecastDetail(1, { id: 1, text: 'x' });
      expect(peekForecastDetail(1)).toEqual({ cached: true, detail: { id: 1, text: 'x' } });
    });

    it('a cached null is a hit, distinct from never fetched', () => {
      cacheForecastDetail(2, null);
      expect(peekForecastDetail(2)).toEqual({ cached: true, detail: null });
    });

    it('keeps ids separate and clear empties them all', () => {
      cacheForecastDetail(1, 'a');
      cacheForecastDetail(2, 'b');
      expect(peekForecastDetail(2).detail).toBe('b');
      clearForecastDetailCache();
      expect(peekForecastDetail(1).cached).toBe(false);
      expect(peekForecastDetail(2).cached).toBe(false);
    });
  });

  it.each([
    ['fetchForecasts', () => fetchForecasts(), 'get', '/api/forecast', undefined],
    ['fetchForecastsForDate', () => fetchForecastsForDate('2026-11-05'), 'get', '/api/forecast',
      { params: { date: '2026-11-05' } }],
    ['getForecastDetail', () => getForecastDetail(77), 'get', '/api/forecast/77', undefined],
    ['refreshTideData', () => refreshTideData(), 'post', '/api/forecast/run/tide', undefined],
    ['backfillTideData', () => backfillTideData(), 'post', '/api/forecast/run/tide/backfill', undefined],
    ['fetchLocations', () => fetchLocations(), 'get', '/api/locations', undefined],
    ['enrichLocation', () => enrichLocation(54.9, -1.6), 'get', '/api/locations/enrich',
      { params: { lat: 54.9, lon: -1.6 } }],
    ['fetchValleyFloorCandidates', () => fetchValleyFloorCandidates(54.9, -1.6), 'get',
      '/api/locations/valley-floor-candidates', { params: { lat: 54.9, lon: -1.6 } }],
    ['fetchValleyFloorCell', () => fetchValleyFloorCell(54.9, -1.6, 120), 'get',
      '/api/locations/valley-floor-cell', { params: { lat: 54.9, lon: -1.6, elevation: 120 } }],
  ])('%s uses %s %s and returns the data', async (_n, call, verb, url, config) => {
    expect(await call()).toBe('body');
    expect(apiClient[verb].mock.calls[0]).toEqual(config === undefined ? [url] : [url, config]);
  });

  it.each([
    ['addLocation', () => addLocation({ name: 'N' }), 'post', '/api/locations', { name: 'N' }],
    ['updateLocation', () => updateLocation(5, { name: 'M' }), 'put', '/api/locations/5', { name: 'M' }],
    ['setLocationEnabled', () => setLocationEnabled(5, false), 'put', '/api/locations/5/enabled',
      { enabled: false }],
  ])('%s sends its body and returns the data', async (_n, call, verb, url, body) => {
    expect(await call()).toBe('body');
    expect(apiClient[verb]).toHaveBeenCalledWith(url, body);
  });

  it('resetLocationFailures PUTs a null body with the name as a query param and resolves to nothing', async () => {
    expect(await resetLocationFailures('Bamburgh Castle')).toBeUndefined();
    expect(apiClient.put).toHaveBeenCalledWith('/api/locations/reset-failures', null,
      { params: { name: 'Bamburgh Castle' } });
  });

  it('propagates an axios failure', async () => {
    apiClient.get.mockRejectedValue(new Error('500'));
    await expect(fetchForecasts()).rejects.toThrow('500');
  });

  describe('geocodePlace', () => {
    const originalFetch = globalThis.fetch;
    afterEach(() => { globalThis.fetch = originalFetch; });

    it('searches Nominatim with " UK" appended and the name URL-encoded, and parses the first hit', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [
          { lat: '54.9', lon: '-1.6', display_name: 'Durham & Co, England' },
          { lat: '0', lon: '0', display_name: 'ignored' },
        ],
      });
      expect(await geocodePlace('Durham & Co')).toEqual({ lat: 54.9, lon: -1.6, displayName: 'Durham & Co, England' });
      expect(globalThis.fetch).toHaveBeenCalledWith(
        'https://nominatim.openstreetmap.org/search?q=Durham%20%26%20Co%20UK&format=json&limit=1',
        { headers: { Accept: 'application/json' } });
    });

    it('throws when the HTTP response is not ok', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({ ok: false });
      await expect(geocodePlace('X')).rejects.toThrow('Geocoding request failed');
    });

    it.each([[[]], [null]])('throws naming the place when there are no results (%j)', async (results) => {
      globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => results });
      await expect(geocodePlace('Atlantis')).rejects.toThrow('No results found for "Atlantis"');
    });
  });
});
