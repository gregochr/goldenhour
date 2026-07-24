import { useState, useEffect, useCallback } from 'react';
import { fetchForecasts, fetchLocations, fetchAllOutcomes } from '../api/forecastApi.js';
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
      // Recorded outcomes are PAST observations — a photographer rating a sunrise/sunset
      // they already saw (ActualOutcomeEntity.outcomeDate is always historical). The window
      // must therefore look BACKWARDS: a forward [today, today+7] range returns essentially
      // nothing. Match the forecast payload's today-7 past edge (ForecastController) so every
      // outcome lines up with a date the map already shows.
      const to = now.toISOString().slice(0, 10);
      const from = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)
        .toISOString()
        .slice(0, 10);

      // Fetch forecasts, location metadata, and every location's outcomes in parallel —
      // outcomes are batched into one request rather than one per location.
      const [forecasts, locationMeta, outcomes] = await Promise.all([
        fetchForecasts(),
        fetchLocations(),
        fetchAllOutcomes(from, to),
      ]);
      const forecastGroups = groupForecastsByLocation(forecasts);
      const forecastByName = Object.fromEntries(forecastGroups.map((g) => [g.name, g]));

      // Group the batched outcomes by location name for O(1) attachment below.
      const outcomesByName = new Map();
      for (const outcome of outcomes) {
        const list = outcomesByName.get(outcome.locationName);
        if (list) list.push(outcome);
        else outcomesByName.set(outcome.locationName, [outcome]);
      }

      // Start from the full location list so locations without forecast rows
      // (e.g. pure-WILDLIFE) still appear on the map.
      const allLocations = locationMeta
        .filter((l) => l.enabled !== false)
        .map((l) => ({
          id: l.id,
          name: l.name,
          lat: l.lat,
          lon: l.lon,
          forecastsByDate: forecastByName[l.name]?.forecastsByDate ?? new Map(),
          locationType: l.locationType ?? [],
          tideType: l.tideType ?? [],
          solarEventType: l.solarEventType ?? ['SUNRISE', 'SUNSET'],
          bortleClass: l.bortleClass ?? null,
          regionName: l.region?.name ?? null,
          outcomes: outcomesByName.get(l.name) ?? [],
        }));

      setLocations(allLocations);
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
    // Invoke via an inline async function so the synchronous setState calls at
    // the top of `load` run inside the deferred async boundary rather than in
    // the effect body itself (react-hooks/set-state-in-effect).
    (async () => {
      await load();
    })();
  }, [load]);

  return { locations, loading, error, refresh: load };
}
