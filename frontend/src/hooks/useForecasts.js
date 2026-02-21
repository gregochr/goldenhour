import { useState, useEffect, useCallback } from 'react';
import { fetchForecasts, fetchOutcomes } from '../api/forecastApi.js';
import { groupForecastsByDate } from '../utils/conversions.js';

const DEFAULT_LAT = 54.7753;
const DEFAULT_LON = -1.5849;
const DEFAULT_LOCATION_NAME = 'Durham UK';

/**
 * Custom hook that fetches and groups forecast data and actual outcomes
 * for the configured default location.
 *
 * @returns {{
 *   forecastsByDate: Map<string, {sunrise: object|null, sunset: object|null}>,
 *   outcomes: Array<object>,
 *   locationName: string,
 *   loading: boolean,
 *   error: string|null,
 *   refresh: function
 * }}
 */
export function useForecasts() {
  const [forecastsByDate, setForecastsByDate] = useState(new Map());
  const [outcomes, setOutcomes] = useState([]);
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

      const [forecasts, outcomeData] = await Promise.all([
        fetchForecasts(),
        fetchOutcomes(DEFAULT_LAT, DEFAULT_LON, from, to),
      ]);

      setForecastsByDate(groupForecastsByDate(forecasts));
      setOutcomes(outcomeData);
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

  return {
    forecastsByDate,
    outcomes,
    locationName: DEFAULT_LOCATION_NAME,
    loading,
    error,
    refresh: load,
  };
}
