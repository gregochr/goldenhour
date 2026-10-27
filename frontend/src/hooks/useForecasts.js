import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { fetchForecasts, fetchForecastsForDate, fetchLocations } from '../api/forecastApi.js';
import { groupForecastsByLocation } from '../utils/conversions.js';
import { cacheGeneration, readSwrCache, writeSwrCache } from '../utils/swrCache.js';
import { getRewindTo } from '../utils/rewind.js';
import { useAuth } from '../context/AuthContext.jsx';
import { apiErrorMessage } from '../utils/apiError.js';

/** Stale-while-revalidate window for the cached forecast payload — matches the briefing cache. */
const FORECASTS_CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000;

/**
 * Shapes the two raw API payloads into the per-location view model the map and the Plan tab's
 * location-derived enrichments consume. Pure, so it runs identically on the live fetch and when
 * hydrating synchronously from the instant-paint cache.
 *
 * <p>There is no {@code outcomes} on a location any more (outcome-rating-plan.md §4 #7): the old
 * {@code GET /api/outcome/all} fed no reader since the outcome UI died (2026-02-27), and the
 * reader's own ratings now arrive through {@code useOutcomeRatings}, mounted beside the map.
 *
 * @param {Array} forecasts - forecast rows from /api/forecast
 * @param {Array} locationMeta - location metadata from /api/locations
 * @returns {Array} per-location objects with a forecastsByDate Map
 */
function buildLocations(forecasts, locationMeta) {
  const forecastGroups = groupForecastsByLocation(forecasts);
  const forecastByName = Object.fromEntries(forecastGroups.map((g) => [g.name, g]));

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
      // The place's served sub-region (sub-regions-plan S5a): a role-free fact the Map's picks chrome counts.
      subRegionId: l.subRegionId ?? null,
      subRegionName: l.subRegionName ?? null,
    }));
}

/**
 * Joins the older days the reader has fetched onto the locations the list payload built
 * (outcome-rating-plan.md §2 #2 "Older-day fetch"). Pure: it is recomputed whenever the hook's
 * result (a `useMemo` over the list payload and the fetched days), so the merge is re-applied to
 * EVERY rebuilt {@code locations} array. A refresh (a forecast
 * run, a drive-time change) replaces the list payload and must not make a loaded T-3 vanish, which
 * would drop its date from {@code allDates}, make the map refuse a selection on it, and leave the
 * window control offering a day the reader already fetched.
 *
 * <p>Merge without replace: an older day's rows fill a (date, type) the list payload does not carry
 * and never overwrite one it does (the overlap with the list's own past days is deliberate, and the
 * list wins). A place the roster no longer holds is ignored. Returns the same array when nothing is
 * loaded, so a reader that never steps back sees the identity it always did.
 *
 * <p>A filter/map over served rows, the already-licensed class (CLAUDE.md Backend-heavy): it derives
 * no rating and decides nothing.
 *
 * @param {Array} locations the per-location view models {@link buildLocations} produced
 * @param {Map<string, Array>} olderDays date to that day's raw forecast rows (possibly empty)
 * @returns {Array} the locations with the older days' entries merged in
 */
function mergeOlderDays(locations, olderDays) {
  if (olderDays.size === 0) return locations;
  const extraByName = new Map();
  for (const rows of olderDays.values()) {
    for (const group of groupForecastsByLocation(rows)) {
      const entries = extraByName.get(group.name) ?? new Map();
      for (const [date, entry] of group.forecastsByDate) entries.set(date, entry);
      extraByName.set(group.name, entries);
    }
  }
  return locations.map((loc) => {
    const extra = extraByName.get(loc.name);
    if (!extra) return loc;
    let merged = null;
    for (const [date, entry] of extra) {
      const have = loc.forecastsByDate.get(date);
      if (!have) {
        merged = merged ?? new Map(loc.forecastsByDate);
        merged.set(date, entry);
        continue;
      }
      const sunrise = have.sunrise ?? entry.sunrise;
      const sunset = have.sunset ?? entry.sunset;
      const hourly = have.hourly?.length ? have.hourly : (entry.hourly ?? []);
      if (sunrise === have.sunrise && sunset === have.sunset && hourly === have.hourly) continue;
      merged = merged ?? new Map(loc.forecastsByDate);
      merged.set(date, { ...have, sunrise, sunset, hourly });
    }
    return merged ? { ...loc, forecastsByDate: merged } : loc;
  });
}

/**
 * Reads the cached raw payload if present, fresh enough, and structurally intact. Returns the raw
 * {forecasts, locationMeta} object or null — kept separate from {@link buildLocations} so the cheap
 * "do we have a cache?" check (for the initial loading flag) never runs a full rebuild.
 *
 * <p>⚠️ A payload cached before the old outcomes fetch was removed still carries an {@code outcomes}
 * array; it is simply ignored (12 hours at most, role-keyed), so an upgrade does not blank the
 * instant paint. The shape must NOT require it any more, or every reader would paint cold once.
 *
 * @param {string} cacheKey - role-specific SWR cache key
 * @returns {{forecasts: Array, locationMeta: Array}|null}
 */
function readValidCache(cacheKey) {
  const cached = readSwrCache(cacheKey, FORECASTS_CACHE_MAX_AGE_MS);
  if (
    cached &&
    Array.isArray(cached.forecasts) &&
    Array.isArray(cached.locationMeta)
  ) {
    return cached;
  }
  return null;
}

/**
 * Custom hook that fetches forecast data for all configured locations.
 *
 * <p>Stale-while-revalidate: on refresh it hydrates the last-seen payload synchronously so the map
 * and the Plan tab's location-derived enrichments paint instantly, then revalidates in the
 * background and swaps in fresh data. The cache holds the raw (JSON-serialisable) API payloads,
 * role-keyed because the forecast scores differ by role; the {@code forecastsByDate} Maps are
 * rebuilt on hydrate. A failed *background* revalidation keeps the stale data on screen rather than
 * blanking it with an error; a failed *user-initiated* {@code refresh} (an explicit action that
 * expects feedback) does surface the error/retry.
 *
 * <p><b>Older days</b> (outcome-rating-plan.md §2 #2): {@code loadOlderDay(date)} fetches one stored
 * past day ({@code GET /api/forecast?date=}), keeps the raw rows in hook state and merges them into
 * {@code locations} on every render, so a refresh keeps them. Never written to the SWR cache (that
 * payload is the list endpoint's, revalidated as a whole; an older day is re-fetched next session). An
 * empty answer is a loaded-empty entry, still counts as loaded, and is named in {@code emptyPastDays}
 * with any day that holds no sunrise or sunset row (a hide's HOURLY rows alone give no window), so the
 * Map tab can say the day has no stored forecast (§4 #15 as amended 2026-10-17). A failed fetch leaves the roster
 * unchanged and reports through {@code error}. It stands down under an admin rewind.
 *
 * @returns {{
 *   locations: Array<{name: string, lat: number, lon: number, forecastsByDate: Map}>,
 *   loading: boolean,
 *   error: string|null,
 *   refresh: function,
 *   loadOlderDay: function(string): Promise<void>,
 *   olderDayLoading: string|null,
 *   loadedPastDays: string[],
 *   emptyPastDays: string[]
 * }}
 */
export function useForecasts() {
  const { role } = useAuth();
  const cacheKey = `forecasts:${role || 'anon'}`;

  const [baseLocations, setLocations] = useState(() => {
    const cached = readValidCache(cacheKey);
    return cached ? buildLocations(cached.forecasts, cached.locationMeta) : [];
  });
  // Instant paint only when the hydrated cache actually yields locations to show. An empty-but-valid
  // cache ({forecasts:[],…} from a fresh deploy or an all-disabled roster) must still show the
  // skeleton, never flash the "no forecasts loaded yet" empty state for a frame (see #287 review).
  const [loading, setLoading] = useState(() => baseLocations.length === 0);
  const [error, setError] = useState(null);
  // True once anything is on screen (cache hydrate or a successful fetch). Guards the loading flash
  // and the error path so a failed revalidation over a hydrated map keeps the stale data.
  const hasDataRef = useRef(baseLocations.length > 0);

  // The older days fetched on demand, date to raw rows. State, not a ref: the merged `locations` and
  // `loadedPastDays` below derive from it. The refs mirror it for the guards, which must read the
  // answer of the call in flight, not of the last render.
  const [olderDays, setOlderDays] = useState(() => new Map());
  const [olderDayLoading, setOlderDayLoading] = useState(null);
  const loadedDaysRef = useRef(new Set());
  const loadingDayRef = useRef(null);

  // Request numbering: a response older than one already applied is dropped whole. Without it a slow
  // pre-save request landing after the post-save refresh puts back forecasts shaped by the old
  // sub-region picks (and re-writes them to the cache).
  const requestedRef = useRef(0);
  const appliedRef = useRef(0);

  const load = useCallback(async ({ userInitiated = false } = {}) => {
    requestedRef.current += 1;
    const request = requestedRef.current;
    setError(null);
    // Only show the skeleton when there's nothing to show yet. A revalidation over hydrated or
    // previously-loaded data stays silent, so refreshing the map never blanks it (no jar).
    if (!hasDataRef.current) setLoading(true);
    // Captured BEFORE the fetch: if the cache is cleared while these requests are in flight
    // (logout, session expiry), the write below is refused rather than re-planting this
    // account's payload after the sweep.
    const gen = cacheGeneration();
    try {
      // Forecasts and location metadata in parallel. (The old recorded-outcomes request that rode
      // here — `GET /api/outcome/all` — is gone: nothing read it; outcome-rating-plan.md §4 #7.)
      const [forecasts, locationMeta] = await Promise.all([
        fetchForecasts(),
        fetchLocations(),
      ]);

      if (request < appliedRef.current) return; // a newer answer is already on screen
      appliedRef.current = request;
      setLocations(buildLocations(forecasts, locationMeta));
      hasDataRef.current = true;
      // Best-effort: writeSwrCache silently no-ops if the payload exceeds the storage quota,
      // and refuses the write outright if the cache was cleared since `gen` was captured.
      writeSwrCache(cacheKey, { forecasts, locationMeta }, gen);
    } catch (err) {
      // Surface the error on a cold load (nothing on screen), or when the user explicitly asked to
      // refresh — an explicit action (Run Forecast, drive-time change, manual re-run) expects
      // feedback, so a failure must show an error/retry rather than silently leave stale numbers.
      // A silent BACKGROUND revalidation failure still keeps the stale map (the intended SWR trade).
      if (!hasDataRef.current || userInitiated) {
        setError(apiErrorMessage(err, err?.message || 'Failed to load forecast data. Please try again.'));
      }
    } finally {
      setLoading(false);
    }
  }, [cacheKey]);

  // Explicit in-app refreshes (the refresh button, post-run reloads, drive-time changes) are
  // user-initiated: a failure surfaces an error/retry rather than silently leaving stale data.
  const refresh = useCallback(() => load({ userInitiated: true }), [load]);

  /**
   * Fetches one older stored day and merges it in. A no-op for a day already held, while another
   * older day is loading, and under an admin rewind (a rewound GET would be answered for the
   * rewound clock, and the Map tab offers no Load row then, plan section 5 #6). The date is the
   * caller's to choose within T-4..T-1 (the pane's {@code olderDayState}); the server refuses the rest.
   */
  const loadOlderDay = useCallback(async (date) => {
    if (!date || getRewindTo() || loadedDaysRef.current.has(date) || loadingDayRef.current) return;
    loadingDayRef.current = date;
    setOlderDayLoading(date);
    setError(null);
    try {
      const rows = await fetchForecastsForDate(date);
      loadedDaysRef.current.add(date);
      // The rows and the end of the loading state land in the same batched render, so a reader of
      // `olderDayLoading` going null always finds the day's rows already merged.
      setOlderDays((prev) => new Map(prev).set(date, Array.isArray(rows) ? rows : []));
    } catch (err) {
      // User-initiated, so it speaks (unlike a background revalidation) and leaves the roster
      // exactly as it was: nothing is recorded as loaded, so the same day is offered again.
      setError(apiErrorMessage(err, err?.message || 'Failed to load the earlier day. Please try again.'));
    } finally {
      loadingDayRef.current = null;
      setOlderDayLoading(null);
    }
  }, []);

  const locations = useMemo(() => mergeOlderDays(baseLocations, olderDays), [baseLocations, olderDays]);
  const loadedPastDays = useMemo(() => [...olderDays.keys()].sort(), [olderDays]);
  // The loaded days with no sunrise or sunset row — a subset of `loadedPastDays`, read off the same state
  // rather than re-derived from `allDates` (a day with no windows never reaches that list). ⚠️ Not
  // `rows.length === 0`: the endpoint also serves a hide's HOURLY comfort rows for a past day, and a day
  // holding only those has no window to step onto — the same silent no-op, which
  // `conversions.colourForecastDates` already treats as no colour day.
  const emptyPastDays = useMemo(
    () => [...olderDays]
      .filter(([, rows]) => !rows.some((r) => r.targetType === 'SUNRISE' || r.targetType === 'SUNSET'))
      .map(([date]) => date)
      .sort(),
    [olderDays],
  );

  useEffect(() => {
    // Invoke via an inline async function so the synchronous setState calls at
    // the top of `load` run inside the deferred async boundary rather than in
    // the effect body itself (react-hooks/set-state-in-effect). The mount load is a
    // background revalidation (not user-initiated), so a failure stays silent when cache-hydrated.
    (async () => {
      await load();
    })();
  }, [load]);

  return {
    locations, loading, error, refresh, loadOlderDay, olderDayLoading, loadedPastDays, emptyPastDays,
  };
}
