import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchRunDay } from '../api/runsApi.js';
import { apiErrorMessage } from '../utils/apiError.js';
import { ukDateStr } from '../utils/mapDates.js';
import { RUNS_POLL_MS } from './useRunsLive.js';
import { useVisiblePoll } from './useVisiblePoll.js';

/** The key a day is cached under: {@code today} for "follow today", else the ISO date. */
const keyOf = (date) => date ?? 'today';

/**
 * The Day view's data (runs-deep-dive-plan.md §4 D2, F12): {@code GET /api/admin/runs/day} for a date, or for today
 * when {@code date} is null (no {@code date} parameter, so the server's own UK clock decides what today is).
 *
 * <p>It polls every minute only while the day is today (and so can still change) and not while the browser tab is
 * hidden or {@code isPaused()} says so; a past day is fetched once. A change of date starts a fresh load: rows of the
 * old day are never shown under the new one, and an answer for an old date is dropped, as is one that arrives after
 * a newer one. A failed poll keeps what is on screen.
 *
 * <p>A date the server refuses (a 400: before the earliest day, or one the client's clock thought was past) is not
 * shown as an error: {@code onClamp} is told where to go ({@code null} for today, else the earliest day).
 *
 * @param {string|null} date the ISO date, or null for today
 * @param {{enabled?: boolean, isPaused?: () => boolean, onClamp?: (date: string|null) => void}} [options]
 *   {@code enabled}: false makes the hook inert (another view is showing)
 * @returns {{data: object|null, loading: boolean, error: string|null, updatedAt: Date|null, pollCount: number,
 *   refresh: () => void}}
 */
export default function useRunDay(date, { enabled = true, isPaused = undefined, onClamp = undefined } = {}) {
  const key = keyOf(date);
  const [state, setState] = useState({ key: null, data: null, error: null });
  const [updatedAt, setUpdatedAt] = useState(null);
  const [pollCount, setPollCount] = useState(0);
  const liveKey = useRef(key);
  const loadedKey = useRef(null);
  const requestedSeq = useRef(0);
  const appliedSeq = useRef(0);
  const pausedRef = useRef(isPaused);
  const clampRef = useRef(onClamp);

  useEffect(() => {
    liveKey.current = key;
  }, [key]);
  useEffect(() => {
    pausedRef.current = isPaused;
    clampRef.current = onClamp;
  });

  const load = useCallback(async () => {
    const requestKey = key;
    const seq = requestedSeq.current + 1;
    requestedSeq.current = seq;
    try {
      let data;
      try {
        data = await fetchRunDay(date);
      } catch (err) {
        if (err?.response?.status !== 400 || date == null) throw err;
        // The server will not serve this date: ask for today to learn where history starts, and say where the
        // address should point (the earliest day for one before it, else today).
        const today = await fetchRunDay(null);
        if (liveKey.current === requestKey) {
          clampRef.current?.(today.earliestDay && date < today.earliestDay ? today.earliestDay : null);
        }
        return;
      }
      if (liveKey.current !== requestKey || seq < appliedSeq.current) return;
      appliedSeq.current = seq;
      loadedKey.current = requestKey;
      setState({ key: requestKey, data, error: null });
      setUpdatedAt(new Date());
      setPollCount((count) => count + 1);
    } catch (err) {
      if (liveKey.current !== requestKey) return;
      setState((prev) => ({
        key: requestKey,
        data: prev.key === requestKey ? prev.data : null,
        error: apiErrorMessage(err, err?.message || 'Failed to load the day'),
      }));
    }
  }, [key, date]);

  const tick = useCallback(() => {
    if (loadedKey.current === key && pausedRef.current?.()) return;
    load();
  }, [key, load]);

  // Only today can change under the reader. The client's UK date (the real clock, via Intl) decides for an explicit
  // date; a loaded answer's own isToday decides once there is one.
  const current = state.key === key ? state : null;
  const isToday = current?.data ? current.data.isToday : (date == null || date === ukDateStr(new Date()));
  useVisiblePoll(tick, RUNS_POLL_MS, { enabled: enabled && isToday });
  // A past day is fetched once (and again on a change of date): the poll is off, so this loads it.
  useEffect(() => {
    if (enabled && !isToday) load();
  }, [enabled, isToday, load]);

  return {
    data: current?.data ?? null,
    loading: enabled && current == null,
    error: current?.error ?? null,
    updatedAt,
    pollCount,
    refresh: load,
  };
}
