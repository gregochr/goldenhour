import { useCallback, useEffect, useState } from 'react';
import { fetchRunCalendar } from '../api/runsApi.js';
import { apiErrorMessage } from '../utils/apiError.js';

/**
 * One month for the Day view's calendar ({@code GET /api/admin/runs/calendar}), fetched while the calendar is open
 * and only for the month it shows. A month shown before is kept (paging back is instant) and re-fetched when the
 * calendar next opens, so a day's spend is never stale for long. An answer for another month never lands on this one.
 *
 * @param {string} month the month as {@code YYYY-MM}
 * @param {boolean} enabled whether the calendar is open
 * @returns {{data: object|null, loading: boolean, error: string|null, retry: () => void}}
 */
export default function useRunCalendar(month, enabled) {
  const [state, setState] = useState({});
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!enabled) return;
    fetchRunCalendar(month)
      .then((data) => setState((prev) => ({ ...prev, [month]: { data, error: null } })))
      .catch((err) => setState((prev) => ({
        ...prev,
        [month]: {
          data: prev[month]?.data ?? null,
          error: apiErrorMessage(err, err?.message || 'Failed to load the month'),
        },
      })));
  }, [month, enabled, attempt]);

  const retry = useCallback(() => {
    setState((prev) => {
      const next = { ...prev };
      delete next[month];
      return next;
    });
    setAttempt((n) => n + 1);
  }, [month]);

  const entry = state[month];
  return {
    data: entry?.data ?? null,
    loading: enabled && entry == null,
    error: entry?.error ?? null,
    retry,
  };
}
