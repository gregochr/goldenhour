import { useState, useEffect, useCallback } from 'react';
import { fetchForecasts, fetchOutcomes } from '../api/forecastApi.js';
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

      const forecasts = await fetchForecasts();
      const locationGroups = groupForecastsByLocation(forecasts);

      const outcomeResults = await Promise.all(
        locationGroups.map((loc) => fetchOutcomes(loc.lat, loc.lon, from, to))
      );

      setLocations(
        locationGroups.map((loc, i) => ({ ...loc, outcomes: outcomeResults[i] }))
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
