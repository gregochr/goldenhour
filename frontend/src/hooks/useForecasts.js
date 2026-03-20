import { useState, useEffect, useCallback } from 'react';
import { fetchForecasts, fetchLocations, fetchOutcomes } from '../api/forecastApi.js';
import { groupForecastsByLocation } from '../utils/conversions.js';

/**
 * Custom hook that fetches forecast data and actual outcomes for all configured locations.
 *
 * @returns {{
 *   locations: Array<{name: string, lat: number, lon: number, forecastsByDate: Map, outcomes: Array}>,
 *   loading: boolean,
 *   error: string|null,
 *   refresh: function
 * }}
 */
export function useForecasts() {
  const [locations, setLocations] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const now = new Date();
      const from = now.toISOString().slice(0, 10);
      const to = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000)
        .toISOString()
        .slice(0, 10);

      const [forecasts, locationMeta] = await Promise.all([fetchForecasts(), fetchLocations()]);
      const forecastGroups = groupForecastsByLocation(forecasts);
      const forecastByName = Object.fromEntries(forecastGroups.map((g) => [g.name, g]));

      // Start from the full location list so locations without forecast rows
      // (e.g. pure-WILDLIFE) still appear on the map.
      const allLocations = locationMeta
        .filter((l) => l.enabled !== false)
        .map((l) => ({
          name: l.name,
          lat: l.lat,
          lon: l.lon,
          forecastsByDate: forecastByName[l.name]?.forecastsByDate ?? new Map(),
          locationType: l.locationType ?? [],
          tideType: l.tideType ?? [],
          solarEventType: l.solarEventType ?? ['SUNRISE', 'SUNSET'],
          driveDurationMinutes: l.driveDurationMinutes ?? null,
        }));

      const outcomeResults = await Promise.all(
        allLocations.map((loc) => fetchOutcomes(loc.lat, loc.lon, from, to))
      );

      setLocations(
        allLocations.map((loc, i) => ({
          ...loc,
          outcomes: outcomeResults[i],
        }))
      );
    } catch (err) {
      setError(
        err.response?.data?.message ||
          err.message ||
          'Failed to load forecast data. Please try again.'
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return { locations, loading, error, refresh: load };
}
