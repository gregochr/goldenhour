import { useState, useEffect, useCallback, useRef } from 'react';
import { fetchForecasts, fetchLocations, fetchAllOutcomes } from '../api/forecastApi.js';
import { groupForecastsByLocation } from '../utils/conversions.js';
import { readSwrCache, writeSwrCache } from '../utils/swrCache.js';
import { useAuth } from '../context/AuthContext.jsx';

/** Stale-while-revalidate window for the cached forecast payload — matches the briefing cache. */
const FORECASTS_CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000;

/**
 * Shapes the three raw API payloads into the per-location view model the map and the Plan tab's
 * location-derived enrichments consume. Pure, so it runs identically on the live fetch and when
 * hydrating synchronously from the instant-paint cache.
 *
 * @param {Array} forecasts - forecast rows from /api/forecast
 * @param {Array} locationMeta - location metadata from /api/locations
 * @param {Array} outcomes - recorded outcomes from /api/outcome/all
 * @returns {Array} per-location objects with a forecastsByDate Map and attached outcomes
 */
function buildLocations(forecasts, locationMeta, outcomes) {
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
  return locationMeta
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
}

/**
 * Reads the cached raw payload if present, fresh enough, and structurally intact. Returns the raw
 * {forecasts, locationMeta, outcomes} object or null — kept separate from {@link buildLocations} so
 * the cheap "do we have a cache?" check (for the initial loading flag) never runs a full rebuild.
 *
 * @param {string} cacheKey - role-specific SWR cache key
 * @returns {{forecasts: Array, locationMeta: Array, outcomes: Array}|null}
 */
function readValidCache(cacheKey) {
  const cached = readSwrCache(cacheKey, FORECASTS_CACHE_MAX_AGE_MS);
  if (
    cached &&
    Array.isArray(cached.forecasts) &&
    Array.isArray(cached.locationMeta) &&
    Array.isArray(cached.outcomes)
  ) {
    return cached;
  }
  return null;
}

/**
 * Custom hook that fetches forecast data and actual outcomes for all configured locations.
 *
 * <p>Stale-while-revalidate: on refresh it hydrates the last-seen payload synchronously so the map
 * and the Plan tab's location-derived enrichments paint instantly, then revalidates in the
 * background and swaps in fresh data. The cache holds the raw (JSON-serialisable) API payloads,
 * role-keyed because the forecast scores differ by role; the {@code forecastsByDate} Maps are
 * rebuilt on hydrate. A failed *background* revalidation keeps the stale data on screen rather than
 * blanking it with an error; a failed *user-initiated* {@code refresh} (an explicit action that
 * expects feedback) does surface the error/retry.
 *
 * @returns {{
 *   locations: Array<{name: string, lat: number, lon: number, forecastsByDate: Map, outcomes: Array}>,
 *   loading: boolean,
 *   error: string|null,
 *   refresh: function
 * }}
 */
export function useForecasts() {
  const { role } = useAuth();
  const cacheKey = `forecasts:${role || 'anon'}`;

  const [locations, setLocations] = useState(() => {
    const cached = readValidCache(cacheKey);
    return cached ? buildLocations(cached.forecasts, cached.locationMeta, cached.outcomes) : [];
  });
  // Instant paint only when the hydrated cache actually yields locations to show. An empty-but-valid
  // cache ({forecasts:[],…} from a fresh deploy or an all-disabled roster) must still show the
  // skeleton, never flash the "no forecasts loaded yet" empty state for a frame (see #287 review).
  const [loading, setLoading] = useState(() => locations.length === 0);
  const [error, setError] = useState(null);
  // True once anything is on screen (cache hydrate or a successful fetch). Guards the loading flash
  // and the error path so a failed revalidation over a hydrated map keeps the stale data.
  const hasDataRef = useRef(locations.length > 0);

  const load = useCallback(async ({ userInitiated = false } = {}) => {
    setError(null);
    // Only show the skeleton when there's nothing to show yet. A revalidation over hydrated or
    // previously-loaded data stays silent, so refreshing the map never blanks it (no jar).
    if (!hasDataRef.current) setLoading(true);
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

      setLocations(buildLocations(forecasts, locationMeta, outcomes));
      hasDataRef.current = true;
      // Best-effort: writeSwrCache silently no-ops if the payload exceeds the storage quota.
      writeSwrCache(cacheKey, { forecasts, locationMeta, outcomes });
    } catch (err) {
      // Surface the error on a cold load (nothing on screen), or when the user explicitly asked to
      // refresh — an explicit action (Run Forecast, drive-time change, manual re-run) expects
      // feedback, so a failure must show an error/retry rather than silently leave stale numbers.
      // A silent BACKGROUND revalidation failure still keeps the stale map (the intended SWR trade).
      if (!hasDataRef.current || userInitiated) {
        setError(
          err.response?.data?.message ||
            err.message ||
            'Failed to load forecast data. Please try again.'
        );
      }
    } finally {
      setLoading(false);
    }
  }, [cacheKey]);

  // Explicit in-app refreshes (the refresh button, post-run reloads, drive-time changes) are
  // user-initiated: a failure surfaces an error/retry rather than silently leaving stale data.
  const refresh = useCallback(() => load({ userInitiated: true }), [load]);

  useEffect(() => {
    // Invoke via an inline async function so the synchronous setState calls at
    // the top of `load` run inside the deferred async boundary rather than in
    // the effect body itself (react-hooks/set-state-in-effect). The mount load is a
    // background revalidation (not user-initiated), so a failure stays silent when cache-hydrated.
    (async () => {
      await load();
    })();
  }, [load]);

  return { locations, loading, error, refresh };
}
