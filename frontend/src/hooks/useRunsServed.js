import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchRunCost, fetchRunTiming } from '../api/runsApi.js';
import { apiErrorMessage } from '../utils/apiError.js';

/**
 * The shared shape of a Runs view that is fetched when its parameters change and again on Refresh, never on a timer
 * (the Cost and Timing views change slowly and a minute-by-minute poll would only redraw them): one served answer per
 * {@code key}. A change of key starts a fresh load (the old key's answer is never shown under the new one), an answer
 * for an old key or one that arrives after a newer one is dropped, and a failed refresh of an answer already on screen
 * keeps it (the error is reported beside it).
 *
 * @param {string} key what the request is for; a change of it is a new load
 * @param {() => Promise<object>} fetcher asks the server for that key
 * @param {boolean} enabled false makes the hook inert (another view is showing)
 * @returns {{data: object|null, loading: boolean, error: string|null, updatedAt: Date|null, refresh: () => void}}
 */
function useServed(key, fetcher, enabled) {
  const [state, setState] = useState({ key: null, data: null, error: null, updatedAt: null });
  const liveKey = useRef(key);
  const fetcherRef = useRef(fetcher);
  const requested = useRef(0);
  const applied = useRef(0);

  useEffect(() => {
    liveKey.current = key;
    fetcherRef.current = fetcher;
  });

  const load = useCallback(async () => {
    const requestKey = key;
    requested.current += 1;
    const seq = requested.current;
    try {
      const data = await fetcherRef.current();
      if (liveKey.current !== requestKey || seq < applied.current) return;
      applied.current = seq;
      setState({ key: requestKey, data, error: null, updatedAt: new Date() });
    } catch (err) {
      if (liveKey.current !== requestKey || seq < applied.current) return;
      setState((prev) => ({
        key: requestKey,
        data: prev.key === requestKey ? prev.data : null,
        updatedAt: prev.key === requestKey ? prev.updatedAt : null,
        error: apiErrorMessage(err, err?.message || 'Failed to load'),
      }));
    }
  }, [key]);

  useEffect(() => {
    if (enabled) load();
  }, [enabled, load]);

  const current = state.key === key ? state : null;
  return {
    data: current?.data ?? null,
    loading: enabled && current == null,
    error: current?.error ?? null,
    updatedAt: current?.updatedAt ?? null,
    refresh: load,
  };
}

/**
 * The Cost trends view's data ({@code GET /api/admin/runs/cost}) for a period.
 *
 * @param {number|'all'} days 7, 14, 30, 60, 90 or {@code 'all'}
 * @param {{enabled?: boolean}} [options] {@code enabled: false} makes the hook inert
 * @returns {{data: object|null, loading: boolean, error: string|null, updatedAt: Date|null, refresh: () => void}}
 */
export function useRunsCost(days, { enabled = true } = {}) {
  return useServed(`cost:${days}`, () => fetchRunCost(days), enabled);
}

/**
 * The Timing view's data ({@code GET /api/admin/runs/timing}) for a type, range and measure.
 *
 * @param {string} type the display type
 * @param {number} days 30, 60 or 120
 * @param {'cycle'|'ready'} measure the measure
 * @param {{enabled?: boolean}} [options] {@code enabled: false} makes the hook inert
 * @returns {{data: object|null, loading: boolean, error: string|null, updatedAt: Date|null, refresh: () => void}}
 */
export function useRunsTiming(type, days, measure, { enabled = true } = {}) {
  return useServed(`timing:${type}:${days}:${measure}`, () => fetchRunTiming({ type, days, measure }), enabled);
}
